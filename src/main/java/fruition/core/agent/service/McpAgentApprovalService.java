package fruition.core.agent.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fruition.core.agent.dto.AgentRunApproveRequest;
import fruition.core.agent.dto.AgentToolExecuteRequest;
import fruition.core.agent.repository.PipelineAgentRunStatusRequester;
import fruition.core.document.domain.Document;
import fruition.core.document.dto.DocumentContentSaveResponse;
import fruition.core.document.service.McpDocumentDataGateway;
import fruition.core.mcp.McpAccessClient;
import fruition.core.mcp.McpAuthorizationException;
import fruition.core.mcp.McpIdentity;
import fruition.core.mcp.McpProposalActor;
import fruition.core.usage.service.CreditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Document-owned MCP approval/reservation/receipt path. */
@Service
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpAgentApprovalService {
    private static final String SCHEMA = "mcp.agent-approval.v1";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final McpAccessClient access;
    private final McpDocumentDataGateway documents;
    private final CreditService credits;
    private final PipelineAgentRunStatusRequester requester;
    private final String unitPrice;
    private final String planCap;
    private final String currency;
    private final String priceVersion;

    public McpAgentApprovalService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper mapper,
                                   McpAccessClient access, McpDocumentDataGateway documents, CreditService credits,
                                   PipelineAgentRunStatusRequester requester,
                                   @Value("${app.mcp.approval.unit-price-minor:}") String unitPrice,
                                   @Value("${app.mcp.approval.plan-cap-minor:}") String planCap,
                                   @Value("${app.mcp.approval.currency:}") String currency,
                                   @Value("${app.mcp.approval.price-version:}") String priceVersion) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.mapper = mapper;
        this.access = access;
        this.documents = documents;
        this.credits = credits;
        this.requester = requester;
        this.unitPrice = unitPrice;
        this.planCap = planCap;
        this.currency = currency;
        this.priceVersion = priceVersion;
    }

    public static boolean isMcpRun(JsonNode run) {
        return run != null && "MCP_CLIENT".equals(run.path("origin").asText());
    }

    public JsonNode approve(String workspaceId, String userId, String runId,
                            AgentRunApproveRequest request, JsonNode run) {
        Approval existing = findByRun(runId);
        Proposal proposal = proposal(workspaceId, userId, runId, request, run, existing == null);
        Approval approval;
        if (existing != null) {
            requireSameIntent(existing, proposal);
            if (Set.of("CANCELLED", "HALTED", "PURGED").contains(existing.lifecycleStatus())) {
                throw new McpApprovalException(HttpStatus.CONFLICT, "MCP_APPROVAL_FENCED");
            }
            approval = existing;
        } else {
            McpIdentity identity = access.authorizeWebApproval(proposal.actor());
            requireIdentity(identity, proposal.actor());
            authorizeOperations(proposal.operations(), identity, true);
            approval = createReserved(proposal, identity.clientName());
        }
        markForwardUnknown(approval.approvalId());
        try {
            JsonNode response = requester.approveMcp(workspaceId, userId, runId, request.planVersion(),
                    request.operationHash(), approval.operationIds(), approval.approvalId(), approval.grantId(),
                    approval.clientId(), approval.policyGeneration());
            markForwardAccepted(approval.approvalId());
            return project(response, findById(approval.approvalId()));
        } catch (RuntimeException failure) {
            // The remote outcome is ambiguous. The same stable approval/reservation is reused on retry.
            throw failure;
        }
    }

    public JsonNode projectRun(String workspaceId, String userId, JsonNode run) {
        if (!isMcpRun(run)) return run;
        Approval approval = findByRun(run.path("id").asText());
        if (approval != null) return project(run, approval);
        try {
            return quote(workspaceId, userId, run);
        } catch (RuntimeException unavailable) {
            // History and recovery stay readable when current grant authorization or price config is unavailable.
            return run;
        }
    }

    public void cancelRun(String runId) {
        transaction.executeWithoutResult(tx -> {
            jdbc.update("UPDATE mcp_agent_approvals SET lifecycle_status = 'CANCELLED', cancelled_at = now(), "
                    + "settlement_status = 'PENDING_RECONCILIATION', updated_at = now() "
                    + "WHERE run_id = ? AND lifecycle_status NOT IN ('PURGED', 'CANCELLED')", runId);
        });
        Approval approval = findByRun(runId);
        if (approval != null) reconcile(approval.approvalId());
    }

    public Execution authorizeExecution(AgentToolExecuteRequest request, String toolName) {
        requireMcpExecutionFields(request);
        Approval approval = findById(request.approvalId());
        if (approval == null || !approval.runId().equals(request.runId())
                || !approval.workspaceId().equals(request.workspaceId())
                || !approval.userId().equals(request.userId())
                || !approval.grantId().equals(request.grantId())
                || !approval.clientId().equals(request.clientId())
                || approval.policyGeneration() != request.policyGeneration()
                || approval.planVersion() != request.planVersion()
                || !approval.operationHash().equals(request.operationHash())
                || !approval.operationIds().contains(request.operationId())
                || Set.of("CANCELLED", "HALTED", "PURGED").contains(approval.lifecycleStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Approved MCP execution does not match.");
        }
        Receipt replay = receipt(approval.approvalId(), request.operationId());
        if (replay != null) return new Execution(approval, replay.attemptId(), null, replay);
        if (!hasFullReservation(approval))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MCP approval reservation is unavailable.");
        McpProposalActor actor = actor(approval, toolName);
        McpIdentity identity;
        try {
            identity = access.authorizeOperationBoundary(actor, approval.policyGeneration());
        } catch (McpAuthorizationException denied) {
            if (Set.of("GRANT_INACTIVE", "CLIENT_DISABLED", "POLICY_DISABLED",
                    "POLICY_GENERATION_MISMATCH", "PURGED").contains(denied.code())) {
                markHalted(approval.approvalId(), denied.code());
            }
            throw denied;
        }
        requireIdentity(identity, actor);
        authorizeOperation(toolName, request.arguments(), identity, true);
        UUID attemptId = uuid(request.attemptId(), "attempt_id");
        transaction.executeWithoutResult(tx -> {
            int inserted = jdbc.update("INSERT INTO mcp_document_edit_attempts "
                            + "(attempt_id, approval_id, operation_id, status) VALUES (?, ?::uuid, ?, 'STARTED') "
                            + "ON CONFLICT (attempt_id) DO NOTHING", attemptId,
                    UUID.fromString(approval.approvalId()), request.operationId());
            if (inserted == 0) {
                Map<String, Object> row = jdbc.queryForMap("SELECT approval_id, operation_id FROM mcp_document_edit_attempts "
                        + "WHERE attempt_id = ?", attemptId);
                if (!UUID.fromString(approval.approvalId()).equals(row.get("approval_id"))
                        || !request.operationId().equals(row.get("operation_id"))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "MCP attempt idempotency conflict.");
                }
            }
            jdbc.update("UPDATE mcp_agent_approvals SET settlement_status = 'EXECUTING', updated_at = now() "
                    + "WHERE approval_id = ?", UUID.fromString(approval.approvalId()));
        });
        return new Execution(approval, attemptId, identity, null);
    }

    /** Must be called inside the same PostgreSQL transaction as the document mutation. */
    public Receipt recordReceipt(Execution execution, String operationId, String documentId,
                                 DocumentContentSaveResponse saved) {
        Approval approval = execution.approval();
        long beforeVersion = saved.changed() ? Math.subtractExact(saved.currentVersion(), 1) : saved.currentVersion();
        return recordReceipt(execution, operationId, "apply_document_edit", "DOCUMENT", documentId,
                beforeVersion, saved.currentVersion(), saved.changed(), saved.updatedAt());
    }

    /** Must be called inside the same PostgreSQL transaction as the corresponding mutation. */
    public Receipt recordReceipt(Execution execution, String operationId, String toolName, String resourceType,
                                 String resourceId, Long beforeVersion, long afterVersion,
                                 boolean changed, Instant committedAt) {
        if (execution.replay() != null) return execution.replay();
        Approval approval = execution.approval();
        String outcome = changed ? "COMMITTED" : "NO_CHANGE";
        UUID receiptId = UUID.randomUUID();
        jdbc.update("INSERT INTO mcp_document_edit_receipts "
                        + "(receipt_id, approval_id, operation_id, attempt_id, workspace_id, user_id, grant_id, client_id, "
                        + "tool_name, outcome, changed, resource_type, resource_id, before_version, after_version, committed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (approval_id, operation_id) DO NOTHING",
                receiptId, UUID.fromString(approval.approvalId()), operationId, execution.attemptId(), approval.workspaceId(),
                approval.userId(), UUID.fromString(approval.grantId()), approval.clientId(), toolName, outcome, changed,
                resourceType, resourceId, beforeVersion, afterVersion, java.sql.Timestamp.from(committedAt));
        Receipt receipt = receipt(approval.approvalId(), operationId);
        if (receipt == null || !receipt.attemptId().equals(execution.attemptId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MCP operation already has a different receipt.");
        }
        return receipt;
    }

    public void executionFinished(Execution execution) {
        reconcile(execution.approval().approvalId());
    }

    public void executionFailed(Execution execution, RuntimeException failure) {
        if (execution.replay() != null) return;
        transaction.executeWithoutResult(tx -> jdbc.update("UPDATE mcp_document_edit_attempts "
                        + "SET status = 'FAILED', failure_code = ?, updated_at = now() "
                        + "WHERE attempt_id = ? AND NOT EXISTS (SELECT 1 FROM mcp_document_edit_receipts r "
                        + "WHERE r.attempt_id = mcp_document_edit_attempts.attempt_id)",
                failure.getClass().getSimpleName(), execution.attemptId()));
        reconcile(execution.approval().approvalId());
    }

    private void markHalted(String approvalId, String reason) {
        transaction.executeWithoutResult(tx -> jdbc.update("UPDATE mcp_agent_approvals "
                        + "SET lifecycle_status = 'HALTED', settlement_status = 'PENDING_RECONCILIATION', "
                        + "halt_reason = ?, updated_at = now() WHERE approval_id = ?::uuid "
                        + "AND lifecycle_status NOT IN ('CANCELLED', 'PURGED')",
                reason, approvalId));
        reconcile(approvalId);
    }

    public ReceiptStatus lookup(ReceiptLookup lookup) {
        Approval approval = findById(lookup.approvalId());
        if (approval == null || !approval.operationIds().contains(lookup.operationId())
                || !approval.workspaceId().equals(lookup.workspaceId())
                || !approval.userId().equals(lookup.userId())
                || !approval.grantId().equals(lookup.grantId())
                || !approval.clientId().equals(lookup.clientId())) {
            return new ReceiptStatus("NOT_FOUND", null);
        }
        Receipt receipt = receipt(lookup.approvalId(), lookup.operationId());
        if (receipt != null) return new ReceiptStatus(receipt.outcome(), receipt);
        List<String> attempts = jdbc.queryForList("SELECT status FROM mcp_document_edit_attempts "
                + "WHERE approval_id = ? AND operation_id = ? ORDER BY updated_at DESC",
                String.class, UUID.fromString(lookup.approvalId()), lookup.operationId());
        if (attempts.contains("UNKNOWN")) return new ReceiptStatus("UNKNOWN", null);
        if (attempts.contains("STARTED")) return new ReceiptStatus("IN_PROGRESS", null);
        if (attempts.contains("FAILED")) return new ReceiptStatus("FAILED", null);
        return new ReceiptStatus("NOT_FOUND", null);
    }

    public ApprovalStatus lookupApproval(ApprovalLookup lookup) {
        Approval approval = findById(lookup.approvalId());
        if (approval == null
                || !approval.workspaceId().equals(lookup.workspaceId())
                || !approval.userId().equals(lookup.userId())
                || !approval.grantId().equals(lookup.grantId())
                || !approval.clientId().equals(lookup.clientId())) {
            return new ApprovalStatus("NOT_FOUND", null, null, null, null);
        }
        if (!approval.runId().equals(lookup.runId())
                || approval.planVersion() != lookup.planVersion()
                || !approval.operationHash().equals(lookup.operationHash())
                || !approval.operationIds().equals(lookup.operationIds())
                || approval.policyGeneration() != lookup.policyGeneration()) {
            return status("MISMATCH", "IDEMPOTENCY_CONFLICT", approval);
        }
        if ("CANCELLED".equals(approval.lifecycleStatus())) return status("FENCED", "USER_CANCEL", approval);
        if ("HALTED".equals(approval.lifecycleStatus())) return status("FENCED", approval.haltReason(), approval);
        if ("PURGED".equals(approval.lifecycleStatus())) return status("FENCED", "PURGED", approval);
        if ("SETTLED".equals(approval.settlementStatus())) return status("SETTLED", "NO_FUTURE_EXECUTION", approval);
        Long open = jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM credit_entries "
                        + "WHERE run_id = ? AND user_id = ? AND type IN ('reserve','release') "
                        + "AND reservation_type = 'MCP_APPROVAL'",
                Long.class, "mcp:" + approval.approvalId(), approval.userId());
        return open != null && open == approval.reservedCreditMilli()
                ? status("VALID", null, approval)
                : status("UNKNOWN", "RESERVATION_UNKNOWN", approval);
    }

    private static ApprovalStatus status(String status, String reason, Approval approval) {
        return new ApprovalStatus(status, reason, approval.lifecycleStatus(), approval.forwardStatus(),
                approval.settlementStatus());
    }

    private boolean hasFullReservation(Approval approval) {
        Long open = jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM credit_entries "
                        + "WHERE run_id = ? AND user_id = ? AND type IN ('reserve','release') "
                        + "AND reservation_type = 'MCP_APPROVAL'",
                Long.class, "mcp:" + approval.approvalId(), approval.userId());
        return open != null && open == approval.reservedCreditMilli();
    }

    @Scheduled(initialDelay = 180_000, fixedDelayString = "${app.mcp.approval.reconcile-interval-ms:60000}")
    public void reconcilePending() {
        jdbc.queryForList("SELECT approval_id::text FROM mcp_agent_approvals "
                + "WHERE settlement_status <> 'SETTLED' ORDER BY updated_at LIMIT 100", String.class)
                .forEach(this::reconcile);
    }

    public void reconcile(String approvalId) {
        reconcile(UUID.fromString(approvalId));
    }

    private void reconcile(UUID approvalId) {
        Approval approval = findById(approvalId.toString());
        if (approval == null || "SETTLED".equals(approval.settlementStatus())) return;
        long changed = jdbc.queryForObject("SELECT count(*) FROM mcp_document_edit_receipts "
                + "WHERE approval_id = ? AND outcome = 'COMMITTED' AND changed", Long.class, approvalId);
        long chargedMinor = Math.min(Math.multiplyExact(changed, approval.unitPriceMinor()), approval.planCapMinor());
        long chargeCreditMilli = Math.multiplyExact(chargedMinor, 1000L);
        long receipts = jdbc.queryForObject("SELECT count(*) FROM mcp_document_edit_receipts WHERE approval_id = ?",
                Long.class, approvalId);
        boolean pendingAttempt = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 "
                + "FROM mcp_document_edit_attempts a WHERE a.approval_id = ? AND a.status IN ('STARTED', 'UNKNOWN') "
                + "AND NOT EXISTS (SELECT 1 FROM mcp_document_edit_receipts r WHERE r.attempt_id = a.attempt_id))",
                Boolean.class, approvalId));
        boolean terminal = receipts == approval.operationIds().size()
                || (Set.of("CANCELLED", "HALTED", "PURGED").contains(approval.lifecycleStatus()) && !pendingAttempt);
        credits.settleMcpApproval(approval.approvalId(), approval.userId(), chargeCreditMilli, terminal);
        jdbc.update("UPDATE mcp_agent_approvals SET charged_minor = ?, settlement_status = ?, updated_at = now() "
                        + "WHERE approval_id = ?", chargedMinor,
                terminal ? "SETTLED" : "PENDING_RECONCILIATION", approvalId);
    }

    private Approval createReserved(Proposal proposal, String clientName) {
        return transaction.execute(tx -> {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "mcp-approval:" + proposal.runId());
            Approval existing = findByRun(proposal.runId());
            if (existing != null) {
                requireSameIntent(existing, proposal);
                return existing;
            }
            Price price = price(proposal.operationIds().size());
            requireReviewedPrice(proposal.expectedPrice(), price);
            String approvalId = UUID.randomUUID().toString();
            String canonical = canonicalHash(approvalId, proposal, price);
            jdbc.update("INSERT INTO mcp_agent_approvals (approval_id, run_id, workspace_id, user_id, plan_version, "
                            + "operation_hash, operation_ids, canonical_hash, grant_id, client_id, client_name, policy_generation, origin, "
                            + "approval_mode, unit_price_minor, plan_cap_minor, currency, price_version, maximum_charge_minor, "
                            + "reserved_credit_milli, lifecycle_status, forward_status, settlement_status) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, 'MCP_CLIENT', 'EXPLICIT_WEB', ?, ?, 'KRW', ?, ?, ?, "
                            + "'RESERVED', 'NOT_SENT', 'RESERVED')",
                    UUID.fromString(approvalId), proposal.runId(), proposal.workspaceId(), proposal.userId(),
                    proposal.planVersion(), proposal.operationHash(), json(proposal.operationIds()), canonical,
                    UUID.fromString(proposal.actor().grantId()), proposal.actor().clientId(), clientName,
                    proposal.actor().policyGeneration(), price.unitPriceMinor(), price.planCapMinor(), price.priceVersion(),
                    price.maximumChargeMinor(), price.reservedCreditMilli());
            credits.reserveMcpApproval(approvalId, proposal.userId(), price.reservedCreditMilli());
            return findById(approvalId);
        });
    }

    private Proposal proposal(String workspaceId, String userId, String runId,
                              AgentRunApproveRequest request, JsonNode run, boolean requireAwaiting) {
        if (!isMcpRun(run) || !runId.equals(run.path("id").asText())
                || !workspaceId.equals(run.path("workspace_id").asText())
                || (requireAwaiting && !"awaiting_approval".equals(run.path("status").asText()))
                || request.planVersion() != run.path("plan").path("version").asInt()
                || !request.operationHash().equals(run.path("plan").path("operation_hash").asText())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MCP plan changed or is not approvable.");
        }
        JsonNode operations = run.path("plan").path("operations");
        if (!operations.isArray() || operations.isEmpty() || operations.size() > 20)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MCP plan must contain 1 to 20 operations.");
        List<String> selected = AgentTurnService.selectedOperationIds(run, request.operationIds());
        List<String> operationIds = new java.util.ArrayList<>();
        List<JsonNode> selectedOperations = new java.util.ArrayList<>();
        Set<String> selectedSet = selected == null ? null : Set.copyOf(selected);
        for (JsonNode operation : operations) {
            String tool = operation.path("tool_name").asText();
            if (!Set.of("create_folder", "rename_folder", "move_folder", "move_document",
                    "rename_document", "create_document", "apply_document_edit").contains(tool))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported MCP plan operation.");
            if (selectedSet == null || selectedSet.contains(operation.path("id").asText())) {
                operationIds.add(operation.path("id").asText());
                selectedOperations.add(operation);
            }
        }
        McpProposalActor actor = new McpProposalActor(run.path("invocation_id").asText(),
                run.path("grant_id").asText(), userId, workspaceId, run.path("client_id").asText(),
                run.path("policy_generation").asLong(), run.path("origin").asText(),
                run.path("spec_version").asText(), run.path("tool_name").asText());
        return new Proposal(runId, workspaceId, userId, request.planVersion(), request.operationHash(),
                operationIds, selectedOperations, actor,
                request.expectedPriceSnapshot());
    }

    private Price price(int operationCount) {
        if (!"KRW".equals(currency) || priceVersion == null || priceVersion.isBlank()) {
            throw new McpApprovalException(HttpStatus.SERVICE_UNAVAILABLE, "MCP_PRICE_UNCONFIGURED");
        }
        try {
            long unit = Long.parseLong(unitPrice);
            long cap = Long.parseLong(planCap);
            if (unit < 0 || cap < 0) throw new NumberFormatException();
            long maximum = Math.min(Math.multiplyExact(operationCount, unit), cap);
            return new Price(unit, cap, priceVersion, maximum, Math.multiplyExact(maximum, 1000L));
        } catch (NumberFormatException | ArithmeticException e) {
            throw new McpApprovalException(HttpStatus.SERVICE_UNAVAILABLE, "MCP_PRICE_UNCONFIGURED");
        }
    }

    private static void requireReviewedPrice(AgentRunApproveRequest.McpPriceSnapshot reviewed, Price current) {
        if (reviewed == null
                || reviewed.unitPriceMinor() == null || reviewed.planCapMinor() == null
                || reviewed.maximumChargeMinor() == null
                || reviewed.unitPriceMinor() != current.unitPriceMinor()
                || reviewed.planCapMinor() != current.planCapMinor()
                || !"KRW".equals(reviewed.currency())
                || !current.priceVersion().equals(reviewed.priceVersion())
                || reviewed.maximumChargeMinor() != current.maximumChargeMinor()) {
            throw new McpApprovalException(HttpStatus.CONFLICT, "MCP_PRICE_CHANGED");
        }
    }

    private String canonicalHash(String approvalId, Proposal proposal, Price price) {
        Map<String, Object> snapshot = new TreeMap<>();
        snapshot.put("currency", "KRW");
        snapshot.put("maximum_charge_minor", price.maximumChargeMinor());
        snapshot.put("plan_cap_minor", price.planCapMinor());
        snapshot.put("price_version", price.priceVersion());
        snapshot.put("unit_price_minor", price.unitPriceMinor());
        Map<String, Object> body = new TreeMap<>();
        body.put("approval_id", approvalId);
        body.put("approval_mode", "EXPLICIT_WEB");
        body.put("client_id", proposal.actor().clientId());
        body.put("grant_id", proposal.actor().grantId());
        body.put("operation_hash", proposal.operationHash());
        body.put("plan_version", proposal.planVersion());
        body.put("policy_generation", proposal.actor().policyGeneration());
        body.put("price_snapshot", snapshot);
        body.put("run_id", proposal.runId());
        body.put("schema_version", SCHEMA);
        body.put("selected_operation_ids_in_plan_order", proposal.operationIds());
        try {
            byte[] bytes = mapper.writeValueAsBytes(body);
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot canonicalize MCP approval.", e);
        }
    }

    private void requireSameIntent(Approval existing, Proposal proposal) {
        if (!existing.workspaceId().equals(proposal.workspaceId()) || !existing.userId().equals(proposal.userId())
                || existing.planVersion() != proposal.planVersion()
                || !existing.operationHash().equals(proposal.operationHash())
                || !existing.operationIds().equals(proposal.operationIds())
                || !existing.grantId().equals(proposal.actor().grantId())
                || !existing.clientId().equals(proposal.actor().clientId())
                || existing.policyGeneration() != proposal.actor().policyGeneration()
                || !matches(existing, proposal.expectedPrice())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MCP approval idempotency conflict.");
        }
    }

    private static boolean matches(Approval approval, AgentRunApproveRequest.McpPriceSnapshot expected) {
        return expected != null
                && expected.unitPriceMinor() != null && expected.planCapMinor() != null
                && expected.maximumChargeMinor() != null
                && expected.unitPriceMinor() == approval.unitPriceMinor()
                && expected.planCapMinor() == approval.planCapMinor()
                && approval.currency().equals(expected.currency())
                && approval.priceVersion().equals(expected.priceVersion())
                && expected.maximumChargeMinor() == approval.maximumChargeMinor();
    }

    private void markForwardUnknown(String approvalId) {
        jdbc.update("UPDATE mcp_agent_approvals SET forward_status = 'UNKNOWN', updated_at = now() "
                + "WHERE approval_id = ? AND lifecycle_status = 'RESERVED'", UUID.fromString(approvalId));
    }

    private void markForwardAccepted(String approvalId) {
        jdbc.update("UPDATE mcp_agent_approvals SET forward_status = 'ACCEPTED', lifecycle_status = 'FORWARDED', "
                + "updated_at = now() WHERE approval_id = ? AND lifecycle_status = 'RESERVED'", UUID.fromString(approvalId));
    }

    private JsonNode project(JsonNode run, Approval approval) {
        if (run == null || !run.isObject()) return run;
        ObjectNode projected = ((ObjectNode) run).deepCopy();
        ObjectNode settlement = projected.putObject("mcp_settlement");
        settlement.put("approval_id", approval.approvalId());
        settlement.put("execution_status", approval.lifecycleStatus());
        settlement.put("forward_status", approval.forwardStatus());
        settlement.put("settlement_status", approval.settlementStatus());
        settlement.put("charged_minor", approval.chargedMinor());
        settlement.put("maximum_charge_minor", approval.maximumChargeMinor());
        settlement.put("currency", approval.currency());
        settlement.put("client_name", approval.clientName());
        ObjectNode snapshot = settlement.putObject("price_snapshot");
        snapshot.put("unit_price_minor", approval.unitPriceMinor());
        snapshot.put("plan_cap_minor", approval.planCapMinor());
        snapshot.put("currency", approval.currency());
        snapshot.put("price_version", approval.priceVersion());
        snapshot.put("maximum_charge_minor", approval.maximumChargeMinor());
        return projected;
    }

    private JsonNode quote(String workspaceId, String userId, JsonNode run) {
        if (!"awaiting_approval".equals(run.path("status").asText())) return run;
        JsonNode operations = run.path("plan").path("operations");
        if (!operations.isArray() || operations.isEmpty() || operations.size() > 20) return run;
        McpProposalActor actor = new McpProposalActor(run.path("invocation_id").asText(),
                run.path("grant_id").asText(), userId, workspaceId,
                run.path("client_id").asText(), run.path("policy_generation").asLong(),
                run.path("origin").asText(), run.path("spec_version").asText(), run.path("tool_name").asText());
        McpIdentity identity = access.authorizeWebApproval(actor);
        requireIdentity(identity, actor);
        List<JsonNode> defaultOperations = new java.util.ArrayList<>();
        for (JsonNode operation : operations)
            if (!operation.has("suggested_selected") || operation.path("suggested_selected").asBoolean(true))
                defaultOperations.add(operation);
        if (defaultOperations.isEmpty()) return run;
        authorizeOperations(defaultOperations, identity, true);
        Price price = price(defaultOperations.size());
        ObjectNode projected = ((ObjectNode) run).deepCopy();
        ObjectNode quote = projected.putObject("mcp_approval_quote");
        quote.put("client_name", identity.clientName());
        quote.put("selected_operation_count", defaultOperations.size());
        ObjectNode snapshot = quote.putObject("price_snapshot");
        snapshot.put("unit_price_minor", price.unitPriceMinor());
        snapshot.put("plan_cap_minor", price.planCapMinor());
        snapshot.put("currency", "KRW");
        snapshot.put("price_version", price.priceVersion());
        snapshot.put("maximum_charge_minor", price.maximumChargeMinor());
        return projected;
    }

    private void authorizeOperations(List<JsonNode> operations, McpIdentity identity, boolean checkVersion) {
        for (JsonNode operation : operations) {
            String tool = operation.path("tool_name").asText();
            JsonNode arguments = operation.path("arguments");
            authorizeOperation(tool, arguments, identity, false);
            if (!Set.of("create_folder", "create_document").contains(tool)) {
                long baseVersion = operation.path("base_version").asLong(-1);
                if (baseVersion < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid base version.");
                requireTarget(tool, operation.path("target_id").asText(), identity, checkVersion ? baseVersion : null);
            }
        }
    }

    private void authorizeOperation(String tool, JsonNode arguments, McpIdentity identity, boolean checkVersion) {
        if (arguments == null || !arguments.isObject())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid operation arguments.");
        if (!Set.of("create_folder", "rename_folder", "move_folder", "move_document",
                "rename_document", "create_document", "apply_document_edit").contains(tool))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported MCP operation.");
        if (!Set.of("create_folder", "create_document").contains(tool)) {
            String target = arguments.path(Set.of("rename_folder", "move_folder").contains(tool)
                    ? "folder_id" : "document_id").asText();
            JsonNode baseNode = arguments.path("base_version");
            if (checkVersion && (!baseNode.canConvertToLong() || baseNode.longValue() < 1))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid base version.");
            Long base = checkVersion ? baseNode.longValue() : null;
            requireTarget(tool, target, identity, base);
        }
        String destinationKey = switch (tool) {
            case "create_folder", "move_folder" -> "parent_folder_id";
            case "create_document", "move_document" -> "folder_id";
            default -> null;
        };
        if (destinationKey != null && arguments.path(destinationKey).isTextual())
            requireFolder(identity, arguments.path(destinationKey).asText());
    }

    private void requireTarget(String tool, String targetId, McpIdentity identity, Long baseVersion) {
        if (Set.of("rename_folder", "move_folder").contains(tool)) {
            FolderState folder = folder(targetId, identity.workspaceId());
            var viewer = documents.viewer(identity);
            if (!viewer.canEditFolder(folder.id())) throw new McpAuthorizationException(403, "PERMISSION_REVOKED");
            if ("move_folder".equals(tool) && folder.parentId() != null
                    && !viewer.canEditFolder(folder.parentId()))
                throw new McpAuthorizationException(403, "PERMISSION_REVOKED");
            if (baseVersion != null && folder.currentVersion() != baseVersion)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "MCP target version changed.");
            return;
        }
        Document document = documents.find(identity.workspaceId(), targetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found."));
        documents.requireEdit(document, identity);
        if ("move_document".equals(tool) && document.getFolderId() != null
                && !documents.viewer(identity).canEditFolder(document.getFolderId()))
            throw new McpAuthorizationException(403, "PERMISSION_REVOKED");
        if (baseVersion != null && document.getCurrentVersion() != baseVersion)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MCP target version changed.");
    }

    private void requireFolder(McpIdentity identity, String rawId) {
        FolderState folder = folder(rawId, identity.workspaceId());
        if (!documents.viewer(identity).canEditFolder(folder.id()))
            throw new McpAuthorizationException(403, "PERMISSION_REVOKED");
    }

    private FolderState folder(String rawId, String workspaceId) {
        UUID id = uuid(rawId, "folder_id");
        List<FolderState> rows = jdbc.query("SELECT id, parent_folder_id, current_version FROM folders "
                        + "WHERE id = ? AND workspace_id = ? AND deleted_at IS NULL",
                (rs, ignored) -> new FolderState(rs.getObject("id", UUID.class),
                        rs.getObject("parent_folder_id", UUID.class), rs.getLong("current_version")), id, workspaceId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found.");
        return rows.getFirst();
    }

    private Approval findByRun(String runId) {
        List<Approval> rows = jdbc.query("SELECT * FROM mcp_agent_approvals WHERE run_id = ?", this::approval, runId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Approval findById(String approvalId) {
        UUID id;
        try { id = UUID.fromString(approvalId); } catch (Exception e) { return null; }
        List<Approval> rows = jdbc.query("SELECT * FROM mcp_agent_approvals WHERE approval_id = ?", this::approval, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Approval approval(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
        try {
            List<String> operationIds = mapper.readValue(rs.getString("operation_ids"),
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return new Approval(rs.getString("approval_id"), rs.getString("run_id"), rs.getString("workspace_id"),
                    rs.getString("user_id"), rs.getInt("plan_version"), rs.getString("operation_hash"), operationIds,
                    rs.getString("grant_id"), rs.getString("client_id"), rs.getString("client_name"),
                    rs.getLong("policy_generation"),
                    rs.getLong("unit_price_minor"), rs.getLong("plan_cap_minor"), rs.getString("currency"),
                    rs.getString("price_version"), rs.getLong("maximum_charge_minor"),
                    rs.getString("lifecycle_status"), rs.getString("forward_status"),
                    rs.getString("settlement_status"), rs.getLong("charged_minor"),
                    rs.getLong("reserved_credit_milli"), rs.getString("halt_reason"));
        } catch (java.io.IOException e) {
            throw new java.sql.SQLException(e);
        }
    }

    private Receipt receipt(String approvalId, String operationId) {
        return receipt(UUID.fromString(approvalId), operationId);
    }

    private Receipt receipt(UUID approvalId, String operationId) {
        List<Receipt> rows = jdbc.query("SELECT * FROM mcp_document_edit_receipts "
                        + "WHERE approval_id = ? AND operation_id = ?", (rs, ignored) -> new Receipt(
                        rs.getString("receipt_id"), rs.getString("approval_id"), rs.getString("operation_id"),
                        UUID.fromString(rs.getString("attempt_id")), rs.getString("workspace_id"),
                        rs.getString("user_id"), rs.getString("grant_id"), rs.getString("client_id"),
                        rs.getString("tool_name"), rs.getString("outcome"), rs.getBoolean("changed"),
                        rs.getString("resource_type"), rs.getString("resource_id"),
                        rs.getObject("before_version", Long.class),
                        rs.getLong("after_version"), rs.getTimestamp("committed_at").toInstant()),
                approvalId, operationId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static void requireIdentity(McpIdentity identity, McpProposalActor actor) {
        if (identity == null || !actor.userId().equals(identity.userId())
                || !actor.workspaceId().equals(identity.workspaceId())
                || !actor.grantId().equals(identity.grantId())
                || !actor.clientId().equals(identity.clientId())
                || actor.policyGeneration() != identity.policyGeneration()) {
            throw new McpApprovalException(HttpStatus.FORBIDDEN, "MCP_IDENTITY_MISMATCH");
        }
    }

    private static McpProposalActor actor(Approval approval, String toolName) {
        return new McpProposalActor(UUID.randomUUID().toString(), approval.grantId(), approval.userId(),
                approval.workspaceId(), approval.clientId(), approval.policyGeneration(), "MCP_CLIENT",
                "2025-11-25", toolName);
    }

    private static void requireMcpExecutionFields(AgentToolExecuteRequest request) {
        if (request.approvalId() == null || request.attemptId() == null || request.grantId() == null
                || request.clientId() == null || request.policyGeneration() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MCP execution authorization is incomplete.");
        }
    }

    private static UUID uuid(String value, String field) {
        try { return UUID.fromString(value); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be UUID."); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public record Execution(Approval approval, UUID attemptId, McpIdentity identity, Receipt replay) {}
    private record Proposal(String runId, String workspaceId, String userId, int planVersion,
                            String operationHash, List<String> operationIds, List<JsonNode> operations,
                            McpProposalActor actor, AgentRunApproveRequest.McpPriceSnapshot expectedPrice) {}
    private record FolderState(UUID id, UUID parentId, long currentVersion) {}
    private record Price(long unitPriceMinor, long planCapMinor, String priceVersion,
                         long maximumChargeMinor, long reservedCreditMilli) {}
    public record Approval(String approvalId, String runId, String workspaceId, String userId, int planVersion,
                           String operationHash, List<String> operationIds, String grantId, String clientId,
                           String clientName, long policyGeneration, long unitPriceMinor, long planCapMinor, String currency,
                           String priceVersion, long maximumChargeMinor, String lifecycleStatus,
                           String forwardStatus, String settlementStatus, long chargedMinor,
                           long reservedCreditMilli, String haltReason) {}
    public record Receipt(@JsonProperty("receipt_id") String receiptId,
                          @JsonProperty("approval_id") String approvalId,
                          @JsonProperty("operation_id") String operationId,
                          @JsonProperty("attempt_id") UUID attemptId,
                          @JsonProperty("workspace_id") String workspaceId,
                          @JsonProperty("user_id") String userId,
                          @JsonProperty("grant_id") String grantId,
                          @JsonProperty("client_id") String clientId,
                          @JsonProperty("tool_name") String toolName,
                          String outcome, boolean changed,
                          @JsonProperty("resource_type") String resourceType,
                          @JsonProperty("resource_id") String resourceId,
                          @JsonProperty("before_version") Long beforeVersion,
                          @JsonProperty("after_version") long afterVersion,
                          @JsonProperty("committed_at") Instant committedAt) {}
    public record ReceiptLookup(String approvalId, String operationId, String workspaceId, String userId,
                                String grantId, String clientId) {}
    public record ReceiptStatus(String status, Receipt receipt) {}
    public record ApprovalLookup(String approvalId, String runId, String workspaceId, String userId,
                                 int planVersion, String operationHash, List<String> operationIds,
                                 String grantId, String clientId, long policyGeneration) {}
    public record ApprovalStatus(String status, String reason,
                                 @JsonProperty("lifecycle_status") String lifecycleStatus,
                                 @JsonProperty("forward_status") String forwardStatus,
                                 @JsonProperty("settlement_status") String settlementStatus) {}

    public static class McpApprovalException extends RuntimeException {
        private final HttpStatus status;
        public McpApprovalException(HttpStatus status, String message) { super(message); this.status = status; }
        public HttpStatus status() { return status; }
    }
}
