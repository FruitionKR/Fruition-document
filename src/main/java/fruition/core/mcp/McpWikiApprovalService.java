package fruition.core.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.service.McpWikiOperationRegistrar;
import fruition.core.document.service.AiMarkdownSanitizer;
import fruition.core.usage.service.CreditService;
import fruition.core.wiki.service.McpWikiStateGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Document-owned immutable wiki quote, approval, reservation, result and fee reconciliation. */
@Service
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpWikiApprovalService {
    private static final Set<String> PREVIEW_FIELDS = Set.of("schema_version", "draft_id", "draft_kind",
            "draft_version", "draft_hash", "source_snapshots", "target_pages", "public_state_hash", "changes", "complete");
    private static final Set<String> SOURCE_FIELDS = Set.of("snapshot_id", "document_id", "source_version", "source_content_hash");
    private static final Set<String> TARGET_FIELDS = Set.of("page_id", "version", "content_hash");
    private static final Set<String> CHANGE_FIELDS = Set.of("change_id", "kind", "action", "page_id", "subject_id",
            "before_text", "after_text", "evidence");
    private static final Set<String> EVIDENCE_FIELDS = Set.of("snapshot_id", "block_id", "quote");
    private static final Set<String> CHANGE_KINDS = Set.of("SOURCE_PAGE", "CONCEPT_STATEMENT", "EVIDENCE_INVALIDATION",
            "PAGE_DELETE", "ENTRY", "LINK", "REFERENCE", "REVIVAL_LINK");
    private static final Set<String> CHANGE_ACTIONS = Set.of("ADD", "UPDATE", "REMOVE", "DELETE", "REVIVE");
    private static final Set<String> RESULT_FIELDS = Set.of("schema_version", "approval_id", "operation_id",
            "draft_id", "state", "changed", "event_id", "published_reference_hash", "committed_at", "error_code");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final McpAccessClient access;
    private final McpWikiSourceService sources;
    private final McpWikiDraftClient ai;
    private final McpWikiStateGateway wiki;
    private final WorkspaceAccessGuard webAccess;
    private final CreditService credits;
    private final McpWikiOperationRegistrar operations;
    private final PriceConfig prices;
    private final String maintenanceTopic;

    public McpWikiApprovalService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper mapper,
                                  McpAccessClient access, McpWikiSourceService sources, McpWikiDraftClient ai,
                                  McpWikiStateGateway wiki, WorkspaceAccessGuard webAccess, CreditService credits,
                                  McpWikiOperationRegistrar operations,
                                  @Value("${app.maintenance.command-topic}") String maintenanceTopic,
                                  @Value("${app.mcp.wiki.currency:}") String currency,
                                  @Value("${app.mcp.wiki.ingest.unit-price-minor:}") String ingestUnitPrice,
                                  @Value("${app.mcp.wiki.ingest.unit-size:}") String ingestUnitSize,
                                  @Value("${app.mcp.wiki.ingest.unit:}") String ingestUnit,
                                  @Value("${app.mcp.wiki.ingest.formula:}") String ingestFormula,
                                  @Value("${app.mcp.wiki.ingest.price-version:}") String ingestVersion,
                                  @Value("${app.mcp.wiki.lint.fixed-price-minor:}") String lintFixedPrice,
                                  @Value("${app.mcp.wiki.lint.unit:}") String lintUnit,
                                  @Value("${app.mcp.wiki.lint.formula:}") String lintFormula,
                                  @Value("${app.mcp.wiki.lint.price-version:}") String lintVersion) {
        this.jdbc = jdbc; this.transaction = transaction; this.mapper = mapper; this.access = access;
        this.sources = sources; this.ai = ai; this.wiki = wiki; this.webAccess = webAccess; this.credits = credits;
        this.operations = operations; this.maintenanceTopic = maintenanceTopic;
        this.prices = new PriceConfig(currency, ingestUnitPrice, ingestUnitSize, ingestUnit, ingestFormula,
                ingestVersion, lintFixedPrice, lintUnit, lintFormula, lintVersion);
    }

    /** Called only after finalize/lint returned a complete immutable draft. */
    public void registerDraft(McpIdentity identity, String taskId, JsonNode preview) {
        Preview value = preview(preview);
        requireIdentity(identity, value);
        Quote quote = quote(identity, value);
        transaction.executeWithoutResult(tx -> {
            int inserted = jdbc.update("""
                    INSERT INTO mcp_wiki_drafts
                    (draft_id,task_id,draft_kind,draft_version,draft_hash,public_state_hash,workspace_id,user_id,
                     grant_id,client_id,policy_generation,source_snapshots,preview,pricing_model,currency,price_version,
                     quantity,price_unit,price_formula,maximum_charge_minor)
                    VALUES (?::uuid,?::uuid,?,?,?,?,?,?,?::uuid,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?,?)
                    ON CONFLICT (draft_id) DO NOTHING
                    """, value.draftId(), uuid(taskId), value.kind(), value.version(), value.draftHash(),
                    value.publicStateHash(), identity.workspaceId(), identity.userId(), uuid(identity.grantId()),
                    identity.clientId(), identity.policyGeneration(), json(value.sources()), json(preview), quote.model(), quote.currency(),
                    quote.version(), quote.quantity(), quote.unit(), quote.formula(), quote.maximumMinor());
            Draft stored = find(value.draftId());
            if (stored == null || !stored.sameDraft(value) || !stored.sameActor(identity) || !stored.quote().equals(quote)) {
                throw conflict("MCP_WIKI_DRAFT_CONFLICT");
            }
        });
    }

    public Map<String, Object> detail(String workspaceId, String userId, String draftId) {
        webAccess.requireMember(workspaceId, userId);
        Draft draft = owned(workspaceId, userId, draftId);
        snapshotClientName(draft);
        draft = owned(workspaceId, userId, draftId);
        if (draft.preview() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP_WIKI_DRAFT_NOT_FOUND");
        return external(draft, draft.preview());
    }

    public Map<String, Object> approve(String workspaceId, String userId, ApprovalRequest request) {
        webAccess.requireMember(workspaceId, userId);
        Draft draft = owned(workspaceId, userId, request.draftId());
        requireApprovalBody(draft, request);
        if (draft.approvalId() != null) {
            if (!approvalHash(request).equals(draft.canonicalApprovalHash())) {
                throw conflict("MCP_WIKI_APPROVAL_CONFLICT");
            }
            reconcile(draft.approvalId());
            return detail(workspaceId, userId, draft.draftId());
        }
        McpProposalActor actor = actor(draft, "apply_wiki_draft");
        McpIdentity identity = access.authorizeWikiWebApproval(actor, permission(draft.kind()));
        if (identity.clientName() == null || identity.clientName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MCP_CLIENT_METADATA_UNAVAILABLE");
        }
        JsonNode internal = ai.getPreview(draft.draftId(), draft.version(), workspaceId, userId);
        Preview value = preview(internal);
        if (!draft.sameDraft(value)) throw conflict("MCP_WIKI_PREVIEW_CHANGED");
        checkSources(identity, value.sources());
        checkTargets(workspaceId, value.targets());
        ApprovalReservation reservation = transaction.execute(tx -> approveLocked(draft, request, identity.clientName()));
        Draft approved = reservation.draft();
        if (!reservation.created()) {
            reconcile(approved.approvalId());
            return detail(workspaceId, userId, approved.draftId());
        }
        try {
            JsonNode result = ai.post("/internal/mcp/wiki/drafts/" + approved.draftId() + "/apply",
                    applyRequest(approved));
            acceptResult(approved, result);
        } catch (RuntimeException uncertain) {
            jdbc.update("UPDATE mcp_wiki_drafts SET publication_status='UNKNOWN',settlement_status='PENDING_RECONCILIATION',"
                    + "publication_error_code='FORWARD_UNKNOWN',updated_at=now() WHERE draft_id=?::uuid", approved.draftId());
            throw uncertain;
        }
        return detail(workspaceId, userId, draft.draftId());
    }

    public Map<String, Object> reject(String workspaceId, String userId, String draftId) {
        webAccess.requireMember(workspaceId, userId);
        Draft draft = owned(workspaceId, userId, draftId);
        if (draft.approvalId() != null) throw conflict("MCP_WIKI_ALREADY_APPROVED");
        jdbc.update("UPDATE mcp_wiki_drafts SET approval_status='REJECTED',approval_reason='USER_REJECTED',updated_at=now() "
                + "WHERE draft_id=?::uuid AND approval_status='AWAITING'", draftId);
        cancelAi(draft);
        return detail(workspaceId, userId, draftId);
    }

    public Map<String, Object> cancel(String workspaceId, String userId, String draftId) {
        webAccess.requireMember(workspaceId, userId);
        Draft draft = owned(workspaceId, userId, draftId);
        transaction.executeWithoutResult(tx -> jdbc.update("UPDATE mcp_wiki_drafts SET approval_status='INVALIDATED',"
                + "approval_reason='USER_CANCEL',publication_status=CASE WHEN publication_status='NOT_STARTED' THEN 'HALTED' "
                + "ELSE publication_status END,settlement_status=CASE WHEN approval_id IS NULL THEN 'RELEASED' "
                + "ELSE 'PENDING_RECONCILIATION' END,updated_at=now() WHERE draft_id=?::uuid", draftId));
        cancelAi(draft);
        if (draft.approvalId() != null) reconcile(draft.approvalId());
        return detail(workspaceId, userId, draftId);
    }

    public ApprovalStatus lookup(ApprovalLookup lookup) {
        Draft draft = find(lookup.draftId());
        if (draft == null || draft.approvalId() == null) return status("NOT_FOUND", "APPROVAL_NOT_FOUND");
        if (!draft.approvalId().equals(lookup.approvalId()) || !draft.operationId().equals(lookup.operationId())
                || !draft.sameLookup(lookup)) return status("INVALID", "IDEMPOTENCY_CONFLICT");
        if (Set.of("INVALIDATED", "REJECTED").contains(draft.approvalStatus())) {
            return status("FENCED", draft.approvalReason());
        }
        if (!fullReservation(draft)) return status("UNKNOWN", "RESERVATION_UNKNOWN");
        McpIdentity identity;
        try {
            identity = access.authorizeWikiOperationBoundary(actor(draft, "apply_wiki_draft"),
                    draft.policyGeneration(), permission(draft.kind()));
        } catch (McpAuthorizationException denied) {
            if (Set.of("CLIENT_DISABLED", "POLICY_DISABLED", "POLICY_GENERATION_MISMATCH", "PURGED",
                    "SECURITY_REVOKED", "ADMIN_REVOKED").contains(denied.code())) {
                return status("FENCED", denied.code());
            }
            return denied.status() >= 500 ? status("UNKNOWN", "AUTHZ_UNAVAILABLE")
                    : status("INVALID", "PERMISSION_REVOKED");
        }
        try {
            checkSources(identity, draft.sources());
        } catch (ResponseStatusException | McpAuthorizationException changed) {
            return status("INVALID", "SOURCE_CHANGED");
        } catch (RuntimeException unavailable) {
            return status("UNKNOWN", "SOURCE_UNAVAILABLE");
        }
        return status("VALID", null);
    }

    public void acceptPublication(PublicationEvent event) {
        validateEvent(event);
        String canonical = hash(event);
        transaction.executeWithoutResult(tx -> {
            Draft draft = findByApproval(event.approvalId());
            if (draft == null || !draft.operationId().equals(event.operationId())
                    || !draft.draftId().equals(event.draftId()) || !draft.workspaceId().equals(event.workspaceId())
                    || !draft.userId().equals(event.userId()) || !draft.grantId().equals(event.grantId())
                    || !draft.clientId().equals(event.clientId()) || !draft.kind().equals(event.kind())) {
                throw conflict("MCP_WIKI_PUBLICATION_CONFLICT");
            }
            List<Map<String, Object>> existing = jdbc.queryForList(
                    "SELECT canonical_hash FROM mcp_wiki_publication_inbox WHERE event_id=?::uuid OR (approval_id=?::uuid AND operation_id=?)",
                    event.eventId(), event.approvalId(), event.operationId());
            if (!existing.isEmpty()) {
                if (existing.stream().anyMatch(row -> canonical.equals(row.get("canonical_hash")))) return;
                throw conflict("MCP_WIKI_PUBLICATION_CONFLICT");
            }
            jdbc.update("""
                    INSERT INTO mcp_wiki_publication_inbox
                    (event_id,approval_id,operation_id,draft_id,canonical_hash,outcome,changed,published_reference_hash,committed_at)
                    VALUES (?::uuid,?::uuid,?,?::uuid,?,?,?,?,?::timestamptz)
                    """, event.eventId(), event.approvalId(), event.operationId(), event.draftId(), canonical,
                    event.outcome(), event.changed(), event.publishedReferenceHash(), event.committedAt());
            if (event.changed()) {
                // 발행은 AI DB에 직접 커밋돼 Kafka 신호가 없다. 0대로 줄어든 maintenance worker를 깨워 새 페이지를 임베딩하게 한다.
                // 같은 트랜잭션의 AI command outbox에 넣어 inbox 중복이면 쌓지 않고 롤백되면 함께 사라진다.
                // 과금 실행이 아니라 ai_task_runs는 만들지 않는다(AiCommandOutboxWriter.enqueueJoined와 같은 저장).
                String runId = "mcp-index:" + event.eventId();
                jdbc.update("INSERT INTO ai_command_outbox(id,run_id,topic,message_key,payload,created_at) "
                                + "VALUES (gen_random_uuid()::text,?,?,?,?,now())", runId, maintenanceTopic,
                        event.workspaceId(), mapper.valueToTree(linked("kind", "mcp_publication_index", "run_id", runId,
                                "workspace_id", event.workspaceId())).toString());
            }
            long chargeMinor = event.changed() ? draft.quote().maximumMinor() : 0;
            credits.settleMcpApproval(draft.approvalId(), draft.userId(), Math.multiplyExact(chargeMinor, 1000L), true);
            jdbc.update("UPDATE mcp_wiki_drafts SET publication_status=?,changed=?,published_reference_hash=?,"
                            + "publication_committed_at=?::timestamptz,publication_error_code=NULL,charged_minor=?,"
                            + "settlement_status='SETTLED',updated_at=now() WHERE draft_id=?::uuid",
                    event.outcome(), event.changed(), event.publishedReferenceHash(), event.committedAt(),
                    chargeMinor, event.draftId());
        });
    }

    @Scheduled(initialDelay = 180_000, fixedDelayString = "${app.mcp.wiki.reconcile-interval-ms:60000}")
    public void reconcilePending() {
        jdbc.queryForList("SELECT approval_id::text FROM mcp_wiki_drafts WHERE approval_id IS NOT NULL "
                        + "AND settlement_status IN ('RESERVED','PENDING_RECONCILIATION') ORDER BY updated_at LIMIT 100",
                String.class).forEach(this::reconcile);
    }

    public void reconcile(String approvalId) {
        Draft draft = findByApproval(approvalId);
        if (draft == null || "SETTLED".equals(draft.settlementStatus())) return;
        JsonNode result;
        String state;
        try {
            result = ai.getResult(draft.approvalId(), draft.operationId(), draft.workspaceId(), draft.userId());
            state = resultState(draft, result);
        } catch (RuntimeException unavailable) {
            jdbc.update("UPDATE mcp_wiki_drafts SET publication_status='UNKNOWN',settlement_status='PENDING_RECONCILIATION',"
                    + "publication_error_code='RESULT_UNAVAILABLE',updated_at=now() WHERE draft_id=?::uuid", draft.draftId());
            return;
        }
        if (Set.of("PUBLISHED", "NO_CHANGE").contains(state)) {
            acceptPublication(new PublicationEvent(result.path("event_id").asText(), "mcp.wiki.publication.v1",
                    draft.approvalId(), draft.operationId(), draft.draftId(), draft.workspaceId(), draft.userId(),
                    draft.grantId(), draft.clientId(), draft.kind(), state, result.path("changed").asBoolean(),
                    result.path("published_reference_hash").asText(), result.path("committed_at").asText()));
        } else if (Set.of("CONFLICTED", "HALTED").contains(state)) {
            transaction.executeWithoutResult(tx -> {
                credits.settleMcpApproval(draft.approvalId(), draft.userId(), 0, true);
                jdbc.update("UPDATE mcp_wiki_drafts SET publication_status=?,publication_error_code=?,"
                                + "settlement_status='RELEASED',updated_at=now() WHERE draft_id=?::uuid",
                        state, nullableText(result.get("error_code")), draft.draftId());
            });
        } else {
            jdbc.update("UPDATE mcp_wiki_drafts SET publication_status='UNKNOWN',settlement_status='PENDING_RECONCILIATION',"
                            + "publication_error_code=?,updated_at=now() WHERE draft_id=?::uuid",
                    "NOT_FOUND".equals(state) ? "NOT_FOUND" : nullableText(result.get("error_code")), draft.draftId());
        }
    }

    private ApprovalReservation approveLocked(Draft draft, ApprovalRequest request, String clientName) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "mcp-wiki-approval:" + draft.draftId());
        Draft current = find(draft.draftId());
        requireApprovalBody(current, request);
        String canonical = approvalHash(request);
        if (current.approvalId() != null) {
            if (!canonical.equals(current.canonicalApprovalHash())) throw conflict("MCP_WIKI_APPROVAL_CONFLICT");
            return new ApprovalReservation(current, false);
        }
        String approvalId = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        long reserved = Math.multiplyExact(current.quote().maximumMinor(), 1000L);
        OperationType operationType = "INGEST".equals(current.kind()) ? OperationType.ingest : OperationType.lint;
        String targetDocumentId = operationType == OperationType.ingest
                ? current.sources().getFirst().documentId() : null;
        operations.registerProcessing(operationId, current.workspaceId(), current.userId(),
                operationType, targetDocumentId, Instant.now());
        jdbc.update("UPDATE mcp_wiki_drafts SET approval_id=?::uuid,operation_id=?,canonical_approval_hash=?,client_name=?,"
                        + "approval_status='EXECUTING',publication_status='APPLYING',settlement_status='RESERVED',"
                        + "reserved_credit_milli=?,updated_at=now() WHERE draft_id=?::uuid AND approval_id IS NULL",
                approvalId, operationId, canonical, clientName, reserved, current.draftId());
        credits.reserveMcpApproval(approvalId, current.userId(), reserved);
        return new ApprovalReservation(find(current.draftId()), true);
    }

    private String approvalHash(ApprovalRequest request) {
        return hash(Map.of("schema_version", "mcp.wiki-web-approval.v1", "draft_id", request.draftId(),
                "draft_version", request.draftVersion(), "draft_hash", request.draftHash(),
                "public_state_hash", request.publicStateHash()));
    }

    private Map<String, Object> applyRequest(Draft draft) {
        return linked("schema_version", "mcp.wiki-apply.v1", "actor", actorMap(actor(draft, "apply_wiki_draft")),
                "approval_id", draft.approvalId(), "operation_id", draft.operationId(), "draft_id", draft.draftId(),
                "draft_version", draft.version(), "draft_hash", draft.draftHash(),
                "public_state_hash", draft.publicStateHash(), "approval_mode", "EXPLICIT_WEB");
    }

    private void acceptResult(Draft draft, JsonNode result) {
        String state = resultState(draft, result);
        jdbc.update("UPDATE mcp_wiki_drafts SET publication_status=?,publication_error_code=?,"
                        + "settlement_status=CASE WHEN ? IN ('PUBLISHED','NO_CHANGE') THEN settlement_status "
                        + "ELSE 'PENDING_RECONCILIATION' END,updated_at=now() WHERE draft_id=?::uuid",
                Set.of("APPLYING", "PUBLISHED", "NO_CHANGE", "CONFLICTED", "HALTED", "UNKNOWN").contains(state)
                        ? state : "UNKNOWN", nullableText(result.get("error_code")), state, draft.draftId());
        if (Set.of("PUBLISHED", "NO_CHANGE", "CONFLICTED", "HALTED").contains(state)) {
            reconcile(draft.approvalId());
        }
    }

    private String resultState(Draft draft, JsonNode result) {
        if (!exact(result, RESULT_FIELDS) || !"mcp.wiki-result.v1".equals(result.path("schema_version").asText())
                || !draft.approvalId().equals(result.path("approval_id").asText())
                || !draft.operationId().equals(result.path("operation_id").asText())) {
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
        String state = result.path("state").asText();
        if (!Set.of("APPLYING", "PUBLISHED", "NO_CHANGE", "CONFLICTED", "HALTED", "UNKNOWN", "NOT_FOUND")
                .contains(state)) throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        if ("NOT_FOUND".equals(state)) {
            if (!result.path("draft_id").isNull() || !result.path("changed").isNull()
                    || !result.path("event_id").isNull() || !result.path("published_reference_hash").isNull()
                    || !result.path("committed_at").isNull() || !result.path("error_code").isNull()) {
                throw new McpToolException("DEPENDENCY_UNAVAILABLE");
            }
            return state;
        }
        if (!draft.draftId().equals(result.path("draft_id").asText())) {
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
        if (Set.of("PUBLISHED", "NO_CHANGE").contains(state)) {
            if (!result.path("changed").isBoolean() || result.path("changed").asBoolean() != "PUBLISHED".equals(state)
                    || !uuid4(result.path("event_id").asText())
                    || !hashValue(result.path("published_reference_hash").asText())
                    || !result.path("error_code").isNull()) throw new McpToolException("DEPENDENCY_UNAVAILABLE");
            try { Instant.parse(result.path("committed_at").asText()); }
            catch (RuntimeException invalid) { throw new McpToolException("DEPENDENCY_UNAVAILABLE"); }
        }
        return state;
    }

    private Map<String, Object> external(Draft draft, Preview preview) {
        Map<String, String> documentBySnapshot = new LinkedHashMap<>();
        List<Map<String, Object>> sourceDocuments = new ArrayList<>();
        for (Source source : preview.sources()) {
            documentBySnapshot.put(source.snapshotId(), source.documentId());
            sourceDocuments.add(linked("document_id", source.documentId(), "source_version", source.version(),
                    "source_content_hash", source.hash()));
        }
        List<Map<String, Object>> changes = new ArrayList<>();
        for (JsonNode change : preview.changes()) {
            List<Map<String, Object>> evidence = new ArrayList<>();
            for (JsonNode item : change.path("evidence")) evidence.add(linked(
                    "document_id", documentBySnapshot.get(item.path("snapshot_id").asText()),
                    "block_id", item.path("block_id").asText(), "quote", safe(item.path("quote").asText())));
            changes.add(linked("change_id", change.path("change_id").asText(), "kind", change.path("kind").asText(),
                    "action", change.path("action").asText(), "page_id", change.path("page_id").asText(),
                    "subject_id", change.path("subject_id").asText(), "before_text", safeNullable(change.get("before_text")),
                    "after_text", safeNullable(change.get("after_text")), "evidence", evidence));
        }
        Map<String, Object> quote = linked("pricing_model", draft.quote().model(), "currency", draft.quote().currency(),
                "price_version", draft.quote().version(), "quantity", draft.quote().quantity(),
                "unit", draft.quote().unit(), "formula", draft.quote().formula(),
                "maximum_charge_minor", draft.quote().maximumMinor());
        Map<String, Object> safePreview = linked("schema_version", "mcp.wiki-preview.v1", "complete", true,
                "source_documents", sourceDocuments, "target_pages", preview.targets().stream().map(t -> linked(
                        "page_id", t.pageId(), "version", t.version(), "content_hash", t.hash())).toList(), "changes", changes);
        return linked("schema_version", "mcp.wiki-web-draft.v1", "draft_id", draft.draftId(),
                "draft_kind", draft.kind(), "draft_version", draft.version(), "draft_hash", draft.draftHash(),
                "public_state_hash", draft.publicStateHash(), "client_name", draft.clientName(), "quote", quote,
                "preview", safePreview,
                "approval", linked("status", draft.approvalStatus(), "reason", draft.approvalReason()),
                "publication", linked("status", draft.publicationStatus(), "changed", draft.changed(),
                        "published_reference_hash", draft.publishedReferenceHash(), "committed_at", draft.committedAt(),
                        "error_code", draft.publicationError()),
                "settlement", linked("status", draft.settlementStatus(), "charged_minor", draft.chargedMinor()));
    }

    private void snapshotClientName(Draft draft) {
        if (draft.clientName() != null || !"AWAITING".equals(draft.approvalStatus())) return;
        try {
            McpIdentity current = access.authorizeWikiWebApproval(actor(draft, "apply_wiki_draft"), permission(draft.kind()));
            if (current.clientName() != null && !current.clientName().isBlank()) {
                jdbc.update("UPDATE mcp_wiki_drafts SET client_name=?,updated_at=now() WHERE draft_id=?::uuid AND client_name IS NULL",
                        current.clientName(), draft.draftId());
            }
        } catch (RuntimeException ignored) {
            // Display/history is lawful after disconnect; approval itself still performs this check fail-closed.
        }
    }

    private Quote quote(McpIdentity identity, Preview preview) {
        prices.requireConfigured(preview.kind());
        if ("LINT".equals(preview.kind())) {
            checkSources(identity, preview.sources());
            return new Quote("LINT_FIXED_APPLY", "KRW", prices.lintVersion,
                    1, prices.lintUnit, prices.lintFormula, positive(prices.lintFixedPrice, "lint fixed price"));
        }
        long quantity = 0;
        for (Source source : preview.sources()) {
            McpWikiSourceService.Response current = sources.read(identity, source.documentId());
            if (current.sourceVersion() != source.version() || !current.sourceContentHash().equals(source.hash())) {
                throw conflict("SOURCE_CHANGED");
            }
            for (McpWikiSourceService.Block block : current.blocks()) {
                quantity = Math.addExact(quantity, block.text().getBytes(StandardCharsets.UTF_8).length);
            }
        }
        long unitSize = positive(prices.ingestUnitSize, "ingest unit size");
        long units = Math.addExact(quantity, unitSize - 1) / unitSize;
        long maximum = Math.multiplyExact(units, positive(prices.ingestUnitPrice, "ingest unit price"));
        return new Quote("INGEST_SOURCE_QUANTITY", "KRW", prices.ingestVersion, quantity,
                prices.ingestUnit, prices.ingestFormula, maximum);
    }

    private void checkSources(McpIdentity identity, List<Source> expected) {
        for (Source source : expected) {
            McpWikiSourceService.Response current = sources.read(identity, source.documentId());
            if (current.sourceVersion() != source.version() || !current.sourceContentHash().equals(source.hash())) {
                throw conflict("SOURCE_CHANGED");
            }
        }
    }

    private void checkTargets(String workspaceId, List<Target> expected) {
        for (Target target : expected) {
            var page = wiki.page(workspaceId, target.pageId()).orElseThrow(() -> conflict("PUBLIC_STATE_CHANGED"));
            String body = page.markdown() == null ? "" : page.markdown();
            if (page.updatedAt() == null || pageVersion(page.updatedAt()) != target.version()
                    || !target.hash().equals(hashBytes(body.getBytes(StandardCharsets.UTF_8)))) {
                throw conflict("PUBLIC_STATE_CHANGED");
            }
        }
    }

    static long pageVersion(Instant updatedAt) {
        return Math.addExact(Math.multiplyExact(updatedAt.getEpochSecond(), 1_000_000L), updatedAt.getNano() / 1_000L);
    }

    private Preview preview(JsonNode node) {
        if (!exact(node, PREVIEW_FIELDS) || !"mcp.wiki-preview.v1".equals(node.path("schema_version").asText())
                || !node.path("complete").asBoolean(false) || !Set.of("INGEST", "LINT").contains(node.path("draft_kind").asText())
                || node.path("draft_version").asLong() < 1 || !uuid4(node.path("draft_id").asText())
                || !hashValue(node.path("draft_hash").asText()) || !hashValue(node.path("public_state_hash").asText())
                || !node.path("source_snapshots").isArray() || node.path("source_snapshots").isEmpty()
                || !node.path("target_pages").isArray() || !node.path("changes").isArray()) {
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
        List<Source> sources = new ArrayList<>();
        Set<String> snapshotIds = new java.util.HashSet<>(), documentIds = new java.util.HashSet<>();
        for (JsonNode item : node.path("source_snapshots")) {
            if (!exact(item, SOURCE_FIELDS) || !uuid4(item.path("snapshot_id").asText())
                    || item.path("source_version").asLong() < 1 || !hashValue(item.path("source_content_hash").asText())
                    || item.path("document_id").asText().isBlank()
                    || !snapshotIds.add(item.path("snapshot_id").asText()) || !documentIds.add(item.path("document_id").asText())) {
                throw new McpToolException("DEPENDENCY_UNAVAILABLE");
            }
            sources.add(new Source(item.path("snapshot_id").asText(), item.path("document_id").asText(),
                    item.path("source_version").asLong(), item.path("source_content_hash").asText()));
        }
        if ("INGEST".equals(node.path("draft_kind").asText()) && sources.size() != 1) {
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
        List<Target> targets = new ArrayList<>();
        Set<String> pageIds = new java.util.HashSet<>(), changeIds = new java.util.HashSet<>();
        for (JsonNode item : node.path("target_pages")) {
            if (!exact(item, TARGET_FIELDS) || item.path("page_id").asText().isBlank()
                    || item.path("version").asLong() < 1 || !hashValue(item.path("content_hash").asText())
                    || !pageIds.add(item.path("page_id").asText())) throw new McpToolException("DEPENDENCY_UNAVAILABLE");
            targets.add(new Target(item.path("page_id").asText(), item.path("version").asLong(),
                    item.path("content_hash").asText()));
        }
        List<JsonNode> changes = new ArrayList<>();
        for (JsonNode change : node.path("changes")) {
            if (!exact(change, CHANGE_FIELDS) || !CHANGE_KINDS.contains(change.path("kind").asText())
                    || !CHANGE_ACTIONS.contains(change.path("action").asText())
                    || change.path("page_id").asText().isBlank() || change.path("subject_id").asText().isBlank()
                    || !change.path("evidence").isArray() || !changeIds.add(change.path("change_id").asText())) {
                throw new McpToolException("DEPENDENCY_UNAVAILABLE");
            }
            for (JsonNode evidence : change.path("evidence")) if (!exact(evidence, EVIDENCE_FIELDS)
                    || !snapshotIds.contains(evidence.path("snapshot_id").asText())
                    || evidence.path("block_id").asText().isBlank() || evidence.path("quote").asText().isBlank()) {
                throw new McpToolException("DEPENDENCY_UNAVAILABLE");
            }
            changes.add(change);
        }
        if (!changes.stream().map(c -> c.path("change_id").asText()).toList().equals(
                changes.stream().map(c -> c.path("change_id").asText()).sorted().toList())) {
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
        return new Preview(node.path("draft_id").asText(), node.path("draft_kind").asText(),
                node.path("draft_version").asLong(), node.path("draft_hash").asText(),
                node.path("public_state_hash").asText(), List.copyOf(sources), List.copyOf(targets), List.copyOf(changes));
    }

    private Draft find(String draftId) {
        List<Draft> rows = jdbc.query("SELECT * FROM mcp_wiki_drafts WHERE draft_id=?::uuid", this::draft, draftId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Draft findByApproval(String approvalId) {
        List<Draft> rows = jdbc.query("SELECT * FROM mcp_wiki_drafts WHERE approval_id=?::uuid", this::draft, approvalId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Draft owned(String workspaceId, String userId, String draftId) {
        Draft draft = find(draftId);
        if (draft == null || !workspaceId.equals(draft.workspaceId()) || !userId.equals(draft.userId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP_WIKI_DRAFT_NOT_FOUND");
        }
        return draft;
    }

    private Draft draft(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
        try {
            List<Source> sourceValues = mapper.readValue(rs.getString("source_snapshots"), new TypeReference<>() {});
            return new Draft(rs.getString("draft_id"), rs.getString("task_id"), rs.getString("draft_kind"),
                    rs.getLong("draft_version"), rs.getString("draft_hash"), rs.getString("public_state_hash"),
                    rs.getString("workspace_id"), rs.getString("user_id"), rs.getString("grant_id"),
                    rs.getString("client_id"), rs.getString("client_name"), rs.getLong("policy_generation"),
                    List.copyOf(sourceValues), rs.getString("preview") == null ? null : preview(mapper.readTree(rs.getString("preview"))),
                    new Quote(rs.getString("pricing_model"), rs.getString("currency"),
                    rs.getString("price_version"), rs.getLong("quantity"), rs.getString("price_unit"),
                    rs.getString("price_formula"), rs.getLong("maximum_charge_minor")), rs.getString("approval_id"),
                    rs.getString("operation_id"), rs.getString("canonical_approval_hash"), rs.getString("approval_status"),
                    rs.getString("approval_reason"), rs.getString("publication_status"), rs.getString("settlement_status"),
                    rs.getLong("charged_minor"), (Boolean) rs.getObject("changed"), rs.getString("published_reference_hash"),
                    rs.getTimestamp("publication_committed_at") == null ? null : rs.getTimestamp("publication_committed_at").toInstant(),
                    rs.getString("publication_error_code"), rs.getLong("reserved_credit_milli"));
        } catch (Exception e) { throw new java.sql.SQLException(e); }
    }

    private boolean fullReservation(Draft draft) {
        Long open = jdbc.queryForObject("SELECT coalesce(sum(amount),0) FROM credit_entries WHERE run_id=? AND user_id=? "
                        + "AND type IN ('reserve','release') AND reservation_type='MCP_APPROVAL'", Long.class,
                "mcp:" + draft.approvalId(), draft.userId());
        return open != null && open == draft.reservedCreditMilli();
    }

    private void cancelAi(Draft draft) {
        try { ai.cancel(draft.taskId(), draft.workspaceId(), draft.userId()); }
        catch (RuntimeException ignored) { /* local fence is authoritative; reconciliation remains pending. */ }
    }

    private void requireApprovalBody(Draft draft, ApprovalRequest request) {
        if (draft == null || !draft.draftId().equals(request.draftId()) || draft.version() != request.draftVersion()
                || !draft.draftHash().equals(request.draftHash()) || !draft.publicStateHash().equals(request.publicStateHash())
                || !"AWAITING".equals(draft.approvalStatus()) && draft.approvalId() == null) {
            throw conflict("MCP_WIKI_APPROVAL_CONFLICT");
        }
    }

    private static void requireIdentity(McpIdentity identity, Preview preview) {
        if (identity == null || identity.workspaceId() == null || identity.userId() == null) {
            throw new McpToolException("PERMISSION_REVOKED");
        }
    }

    private static String permission(String kind) { return "LINT".equals(kind) ? "wiki.lint" : "wiki.ingest"; }
    private static McpProposalActor actor(Draft draft, String tool) {
        return new McpProposalActor(UUID.randomUUID().toString(), draft.grantId(), draft.userId(), draft.workspaceId(),
                draft.clientId(), draft.policyGeneration(), "MCP_CLIENT", "2025-11-25", tool);
    }
    private Map<String, Object> actorMap(McpProposalActor actor) { return mapper.convertValue(actor, new TypeReference<>() {}); }
    private static ApprovalStatus status(String status, String reason) { return new ApprovalStatus("mcp.wiki-approval-status.v1", status, reason); }
    private static ResponseStatusException conflict(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
    private static boolean exact(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) return false;
        for (String field : fields) if (!node.has(field)) return false;
        return true;
    }
    private static boolean hashValue(String value) { return value != null && value.matches("sha256:[0-9a-f]{64}"); }
    private static boolean uuid4(String value) { try { UUID id=UUID.fromString(value); return id.version()==4 && id.toString().equals(value); } catch(Exception e){return false;} }
    private static UUID uuid(String value) { try { return UUID.fromString(value); } catch(Exception e){throw new IllegalArgumentException("UUID required",e);} }
    private static String nullableText(JsonNode value) { return value == null || value.isNull() ? null : value.asText(); }
    private static String safe(String value) { return AiMarkdownSanitizer.sanitize(value == null ? "" : value); }
    private static String safeNullable(JsonNode value) { return value == null || value.isNull() ? null : safe(value.asText()); }
    private static long positive(String value, String field) { try { long v=Long.parseLong(value); if(v<1) throw new NumberFormatException(); return v; } catch(Exception e){throw new McpToolException("DEPENDENCY_UNAVAILABLE");} }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch(Exception e){throw new IllegalStateException(e);} }
    private String hash(Object value) { try { return hashBytes(mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value)); } catch(Exception e){throw new IllegalStateException(e);} }
    private static String hashBytes(byte[] value) { try { return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch(Exception e){throw new IllegalStateException(e);} }
    private static Map<String,Object> linked(Object... values) { Map<String,Object> out=new LinkedHashMap<>(); for(int i=0;i<values.length;i+=2) out.put((String)values[i],values[i+1]); return out; }

    private void validateEvent(PublicationEvent e) {
        if (e == null || !"mcp.wiki.publication.v1".equals(e.eventType()) || !uuid4(e.eventId())
                || !uuid4(e.approvalId()) || !uuid4(e.draftId()) || !uuid4(e.grantId())
                || !bounded(e.workspaceId(),255) || !bounded(e.userId(),255) || !bounded(e.clientId(),2048)
                || !Set.of("INGEST","LINT").contains(e.kind())
                || !Set.of("PUBLISHED","NO_CHANGE").contains(e.outcome()) || e.changed() != "PUBLISHED".equals(e.outcome())
                || !hashValue(e.publishedReferenceHash()) || e.operationId()==null || e.operationId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PUBLICATION_EVENT");
        }
        Instant.parse(e.committedAt());
    }
    private static boolean bounded(String value,int max){return value!=null&&!value.isBlank()&&value.equals(value.trim())&&value.length()<=max;}

    private static final class PriceConfig {
        final String currency, ingestUnitPrice, ingestUnitSize, ingestUnit, ingestFormula, ingestVersion,
                lintFixedPrice, lintUnit, lintFormula, lintVersion;
        PriceConfig(String... v){currency=v[0];ingestUnitPrice=v[1];ingestUnitSize=v[2];ingestUnit=v[3];ingestFormula=v[4];
            ingestVersion=v[5];lintFixedPrice=v[6];lintUnit=v[7];lintFormula=v[8];lintVersion=v[9];}
        void requireConfigured(String kind){
            List<String> required="LINT".equals(kind)?List.of(currency,lintFixedPrice,lintUnit,lintFormula,lintVersion)
                    :List.of(currency,ingestUnitPrice,ingestUnitSize,ingestUnit,ingestFormula,ingestVersion);
            if(!"KRW".equals(currency)||required.stream().anyMatch(s->s==null||s.isBlank())) throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
    }
    private record Preview(String draftId,String kind,long version,String draftHash,String publicStateHash,
                           List<Source> sources,List<Target> targets,List<JsonNode> changes) {}
    public record Source(@com.fasterxml.jackson.annotation.JsonProperty("snapshot_id") String snapshotId,
                         @com.fasterxml.jackson.annotation.JsonProperty("document_id") String documentId,
                         @com.fasterxml.jackson.annotation.JsonProperty("source_version") long version,
                         @com.fasterxml.jackson.annotation.JsonProperty("source_content_hash") String hash) {}
    private record Target(String pageId,long version,String hash) {}
    private record Quote(String model,String currency,String version,long quantity,String unit,String formula,long maximumMinor) {}
    private record ApprovalReservation(Draft draft,boolean created) {}
    private record Draft(String draftId,String taskId,String kind,long version,String draftHash,String publicStateHash,
                         String workspaceId,String userId,String grantId,String clientId,String clientName,long policyGeneration,
                         List<Source> sources,Preview preview,Quote quote,String approvalId,String operationId,String canonicalApprovalHash,
                         String approvalStatus,String approvalReason,String publicationStatus,String settlementStatus,long chargedMinor,
                         Boolean changed,String publishedReferenceHash,Instant committedAt,String publicationError,long reservedCreditMilli) {
        boolean sameDraft(Preview p){return draftId.equals(p.draftId())&&kind.equals(p.kind())&&version==p.version()
                &&draftHash.equals(p.draftHash())&&publicStateHash.equals(p.publicStateHash())&&sources.equals(p.sources())
                &&preview!=null&&preview.equals(p);}
        boolean sameActor(McpIdentity i){return workspaceId.equals(i.workspaceId())&&userId.equals(i.userId())
                &&grantId.equals(i.grantId())&&clientId.equals(i.clientId())&&policyGeneration==i.policyGeneration();}
        boolean sameLookup(ApprovalLookup l){return draftId.equals(l.draftId())&&version==l.draftVersion()
                &&draftHash.equals(l.draftHash())&&publicStateHash.equals(l.publicStateHash())&&workspaceId.equals(l.workspaceId())
                &&userId.equals(l.userId())&&grantId.equals(l.grantId())&&clientId.equals(l.clientId())
                &&policyGeneration==l.policyGeneration()&&sources.equals(l.sourceSnapshots());}
    }
    public record ApprovalRequest(String draftId,long draftVersion,String draftHash,String publicStateHash) {}
    public record ApprovalLookup(String approvalId,String operationId,String draftId,long draftVersion,String draftHash,
                                 String publicStateHash,String workspaceId,String userId,String grantId,String clientId,
                                 long policyGeneration,List<Source> sourceSnapshots) {}
    public record ApprovalStatus(@com.fasterxml.jackson.annotation.JsonProperty("schema_version") String schemaVersion,
                                 String status,String reason) {}
    public record PublicationEvent(@com.fasterxml.jackson.annotation.JsonProperty("event_id") String eventId,
                                   @com.fasterxml.jackson.annotation.JsonProperty("event_type") String eventType,
                                   @com.fasterxml.jackson.annotation.JsonProperty("approval_id") String approvalId,
                                   @com.fasterxml.jackson.annotation.JsonProperty("operation_id") String operationId,
                                   @com.fasterxml.jackson.annotation.JsonProperty("draft_id") String draftId,
                                   @com.fasterxml.jackson.annotation.JsonProperty("workspace_id") String workspaceId,
                                   @com.fasterxml.jackson.annotation.JsonProperty("user_id") String userId,
                                   @com.fasterxml.jackson.annotation.JsonProperty("grant_id") String grantId,
                                   @com.fasterxml.jackson.annotation.JsonProperty("client_id") String clientId,
                                   String kind,String outcome,boolean changed,
                                   @com.fasterxml.jackson.annotation.JsonProperty("published_reference_hash") String publishedReferenceHash,
                                   @com.fasterxml.jackson.annotation.JsonProperty("committed_at") String committedAt) {}
}
