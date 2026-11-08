package fruition.core.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.agent.dto.AgentRunApproveRequest;
import fruition.core.agent.exception.PipelineAgentNoResponseException;
import fruition.core.agent.exception.InvalidAgentTurnRequestException;
import fruition.core.agent.repository.PipelineAgentRunStatusRequester;
import fruition.core.agent.repository.AgentRunCommandRepository;
import fruition.core.document.domain.Document;
import fruition.core.document.dto.DocumentContentSaveResponse;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.service.DocumentService;
import fruition.core.document.service.FolderService;
import fruition.core.document.service.McpDocumentDataGateway;
import fruition.core.mcp.McpAccessClient;
import fruition.core.mcp.McpAuthorizationException;
import fruition.core.mcp.McpIdentity;
import fruition.core.usage.service.CreditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "test.isolated-database=i5_mcp_approval")
@Import(TestcontainersConfiguration.class)
class McpAgentApprovalServiceIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper mapper;
    @Autowired CreditService credits;
    @Autowired DocumentService documentService;
    @Autowired FolderService folderService;
    @Autowired StringRedisTemplate redis;
    @Autowired AgentRunCommandRepository runCommands;

    private McpAccessClient access;
    private McpDocumentDataGateway documents;
    private PipelineAgentRunStatusRequester requester;
    private McpAgentApprovalService service;
    private final String user = "user_i5_" + UUID.randomUUID();
    private final String workspace = "ws_i5";
    private final String grant = UUID.randomUUID().toString();
    private final String client = "https://client.example/i5";

    @BeforeEach
    void setUp() {
        access = mock(McpAccessClient.class);
        documents = mock(McpDocumentDataGateway.class);
        requester = mock(PipelineAgentRunStatusRequester.class);
        service = new McpAgentApprovalService(jdbc, new TransactionTemplate(manager), mapper, access, documents,
                credits, requester, "1", "1", "KRW", "i5-test-v1");
        McpIdentity identity = new McpIdentity(user, workspace, client, grant, 2,
                Set.of("write_propose"), "MEMBER", Set.of(), "Test MCP Client");
        when(access.authorizeWebApproval(any())).thenReturn(identity);
        when(access.authorizeOperationBoundary(any(), anyLong())).thenReturn(identity);
        Document document = mock(Document.class);
        when(document.getCurrentVersion()).thenReturn(1L);
        when(documents.find(anyString(), anyString())).thenReturn(java.util.Optional.of(document));
    }

    @Test
    void quoteBindsTrustedClientAndExactReviewedPriceBeforeReservation() throws Exception {
        JsonNode run = run("agent_" + "d".repeat(32));

        JsonNode projected = service.projectRun(workspace, user, run);
        assertThat(projected.path("mcp_approval_quote").path("client_name").asText())
                .isEqualTo("Test MCP Client");
        assertThat(projected.path("mcp_approval_quote").path("selected_operation_count").asInt()).isOne();
        assertThat(projected.path("mcp_approval_quote").path("price_snapshot").path("price_version").asText())
                .isEqualTo("i5-test-v1");

        AgentRunApproveRequest changed = new AgentRunApproveRequest(1, "c".repeat(64), java.util.List.of("op-1"),
                new AgentRunApproveRequest.McpPriceSnapshot(1L, 1L, "KRW", "changed", 1L));
        assertThatThrownBy(() -> service.approve(workspace, user, run.path("id").asText(), changed, run))
                .isInstanceOf(McpAgentApprovalService.McpApprovalException.class)
                .hasMessage("MCP_PRICE_CHANGED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_agent_approvals WHERE user_id = ?",
                Integer.class, user)).isZero();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM mcp_document_edit_receipts WHERE user_id = ?", user);
        jdbc.update("DELETE FROM mcp_document_edit_attempts WHERE approval_id IN "
                + "(SELECT approval_id FROM mcp_agent_approvals WHERE user_id = ?)", user);
        jdbc.update("DELETE FROM mcp_agent_approvals WHERE user_id = ?", user);
        jdbc.update("DELETE FROM folders WHERE workspace_id = ?", workspace);
        jdbc.update("DELETE FROM credit_entries WHERE user_id = ?", user);
        jdbc.update("DELETE FROM credit_accounts WHERE user_id = ?", user);
    }

    @Test
    void fullReservationIsAtomicAndInsufficientFundsLeavesNoApproval() throws Exception {
        JsonNode run = run("agent_" + "a".repeat(32));
        when(requester.approveMcp(anyString(), anyString(), anyString(), anyInt(), anyString(), anyList(),
                anyString(), anyString(), anyString(), anyLong())).thenReturn(run);

        assertThatThrownBy(() -> service.approve(workspace, user, run.path("id").asText(), request(), run))
                .isInstanceOf(CreditService.InsufficientCreditException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_agent_approvals WHERE user_id = ?",
                Integer.class, user)).isZero();
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();
    }

    @Test
    void adminSecurityDenialHaltsAndReleasesReservation() throws Exception {
        credits.post(user, "grant", 2_000, "halt-grant:" + user, "test");
        JsonNode run = run("agent_" + "7".repeat(32));
        when(requester.approveMcp(anyString(), anyString(), anyString(), anyInt(), anyString(), anyList(),
                anyString(), anyString(), anyString(), anyLong())).thenReturn(run);
        JsonNode accepted = service.approve(workspace, user, run.path("id").asText(), request(), run);
        assertThat(accepted.path("mcp_settlement").path("forward_status").asText()).isEqualTo("ACCEPTED");
        String approvalId = approvalId(run.path("id").asText());
        when(access.authorizeOperationBoundary(any(), anyLong()))
                .thenThrow(new McpAuthorizationException(403, "POLICY_GENERATION_MISMATCH"));

        assertThatThrownBy(() -> service.authorizeExecution(execute(run, approvalId, "op-1", 1),
                "apply_document_edit"))
                .isInstanceOf(McpAuthorizationException.class);
        assertThat(jdbc.queryForObject("SELECT lifecycle_status FROM mcp_agent_approvals WHERE approval_id = ?::uuid",
                String.class, approvalId)).isEqualTo("HALTED");
        assertThat(service.lookupApproval(approvalLookup(run, approvalId, "op-1")))
                .extracting(McpAgentApprovalService.ApprovalStatus::status,
                        McpAgentApprovalService.ApprovalStatus::reason)
                .containsExactly("FENCED", "POLICY_GENERATION_MISMATCH");
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();
        assertThatThrownBy(() -> service.approve(workspace, user, run.path("id").asText(), request(), run))
                .isInstanceOf(McpAgentApprovalService.McpApprovalException.class)
                .hasMessage("MCP_APPROVAL_FENCED");
    }

    @Test
    void unknownAuthorizationBlocksMutationAndKeepsReservationPending() throws Exception {
        credits.post(user, "grant", 2_000, "unknown-grant:" + user, "test");
        JsonNode run = run("agent_" + "8".repeat(32));
        when(requester.approveMcp(anyString(), anyString(), anyString(), anyInt(), anyString(), anyList(),
                anyString(), anyString(), anyString(), anyLong())).thenReturn(run);
        service.approve(workspace, user, run.path("id").asText(), request(), run);
        String approvalId = approvalId(run.path("id").asText());
        when(access.authorizeOperationBoundary(any(), anyLong()))
                .thenThrow(new McpAuthorizationException(503, "AUTHZ_UNAVAILABLE"));

        assertThatThrownBy(() -> service.authorizeExecution(execute(run, approvalId, "op-1", 1),
                "apply_document_edit"))
                .isInstanceOf(McpAuthorizationException.class);
        assertThat(jdbc.queryForObject("SELECT lifecycle_status FROM mcp_agent_approvals WHERE approval_id = ?::uuid",
                String.class, approvalId)).isEqualTo("FORWARDED");
        assertThat(service.lookupApproval(approvalLookup(run, approvalId, "op-1")).status()).isEqualTo("VALID");
        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(1_000);
    }

    @Test
    void responseLossRetryReusesApprovalAndReservationAndReceiptSettlesOnce() throws Exception {
        credits.post(user, "grant", 2_000, "grant:" + user, "test");
        JsonNode run = run("agent_" + "b".repeat(32));
        when(requester.approveMcp(anyString(), anyString(), anyString(), anyInt(), anyString(), anyList(),
                anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new PipelineAgentNoResponseException("lost"))
                .thenReturn(run);

        assertThatThrownBy(() -> service.approve(workspace, user, run.path("id").asText(), request(), run))
                .isInstanceOf(PipelineAgentNoResponseException.class);
        service.approve(workspace, user, run.path("id").asText(), request(), run);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_agent_approvals WHERE user_id = ?",
                Integer.class, user)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries WHERE user_id = ? "
                + "AND reservation_type = 'MCP_APPROVAL' AND type = 'reserve'", Integer.class, user)).isOne();

        String approvalId = jdbc.queryForObject("SELECT approval_id::text FROM mcp_agent_approvals WHERE user_id = ?",
                String.class, user);
        assertThat(service.lookupApproval(approvalLookup(run, approvalId, "op-1")).status()).isEqualTo("VALID");
        var executionArguments = mapper.createObjectNode().put("document_id", "doc_i5").put("base_version", 1);
        var execute = new fruition.core.agent.dto.AgentToolExecuteRequest(run.path("id").asText(), workspace, user,
                "plan-1", 1, "c".repeat(64), "op-1", "write-1", executionArguments, approvalId,
                UUID.randomUUID().toString(), grant, client, 2L);
        var execution = service.authorizeExecution(execute, "apply_document_edit");
        TransactionTemplate tx = new TransactionTemplate(manager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            service.recordReceipt(execution, "op-1", "doc_i5",
                    new DocumentContentSaveResponse("doc_i5", 2, "hash", Instant.now(), true));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_document_edit_receipts WHERE approval_id = ?::uuid",
                Integer.class, approvalId)).isZero();

        tx.executeWithoutResult(status -> service.recordReceipt(execution, "op-1", "doc_i5",
                new DocumentContentSaveResponse("doc_i5", 2, "hash", Instant.now(), true)));
        service.executionFinished(execution);
        service.executionFinished(execution);
        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(1_000);
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();
        service.cancelRun(run.path("id").asText());
        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(1_000);
        assertThat(service.lookup(new McpAgentApprovalService.ReceiptLookup(
                approvalId, "op-1", workspace, user, grant, client)).status()).isEqualTo("COMMITTED");
        assertThat(service.lookupApproval(approvalLookup(run, approvalId, "op-1")).status()).isEqualTo("FENCED");
        assertThat(service.lookup(new McpAgentApprovalService.ReceiptLookup(
                approvalId, "op-1", "other", user, grant, client)).status()).isEqualTo("NOT_FOUND");
    }

    @Test
    void actualDocumentMutationAndReceiptCommitRollbackAndNoChangeTogether() throws Exception {
        redis.opsForValue().set("authz:role:v2:" + workspace + ":" + user,
                "{\"role\":\"OWNER\",\"permissions\":[]}");
        String base = "# I5\n\nbase";
        String edited = "# I5\n\nedited";
        String documentId = documentService.createMarkdown(workspace, user, UUID.randomUUID().toString(),
                new MarkdownDocumentCreateRequest("I5", base, null)).id();
        credits.post(user, "grant", 5_000, "actual-grant:" + user, "test");

        JsonNode run = run("agent_" + "e".repeat(32), documentId);
        when(requester.approveMcp(anyString(), anyString(), anyString(), anyInt(), anyString(), anyList(),
                anyString(), anyString(), anyString(), anyLong())).thenReturn(run);
        service.approve(workspace, user, run.path("id").asText(), request(), run);
        String approvalId = approvalId(run.path("id").asText());
        var execution = service.authorizeExecution(execute(run, approvalId, "op-1", 1),
                "apply_document_edit");
        TransactionTemplate tx = new TransactionTemplate(manager);
        runCommands.prepareToolApply("actual-run-1", workspace, user, documentId, 1, "op-1", edited);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            DocumentContentSaveResponse saved = documentService.saveAgentContentInCurrentTransaction(
                    workspace, user, documentId, edited, 1L, "actual-write-1", "op-1");
            service.recordReceipt(execution, "op-1", documentId, saved);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT revision FROM document_edit_states WHERE document_id = ?",
                Long.class, documentId)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_document_edit_receipts WHERE approval_id = ?::uuid",
                Integer.class, approvalId)).isZero();

        tx.executeWithoutResult(status -> {
            DocumentContentSaveResponse saved = documentService.saveAgentContentInCurrentTransaction(
                    workspace, user, documentId, edited, 1L, "actual-write-1", "op-1");
            service.recordReceipt(execution, "op-1", documentId, saved);
        });
        assertThat(service.lookup(new McpAgentApprovalService.ReceiptLookup(
                approvalId, "op-1", workspace, user, grant, client)).status()).isEqualTo("COMMITTED");

        JsonNode noopRun = run("agent_" + "f".repeat(32), documentId, "op-2", 2);
        Document versionTwo = mock(Document.class);
        when(versionTwo.getCurrentVersion()).thenReturn(2L);
        when(documents.find(anyString(), anyString())).thenReturn(java.util.Optional.of(versionTwo));
        service.approve(workspace, user, noopRun.path("id").asText(), request("op-2"), noopRun);
        String noopApproval = approvalId(noopRun.path("id").asText());
        var noopExecution = service.authorizeExecution(execute(noopRun, noopApproval, "op-2", 2),
                "apply_document_edit");
        runCommands.prepareToolApply("actual-run-2", workspace, user, documentId, 2, "op-2", edited);
        tx.executeWithoutResult(status -> {
            DocumentContentSaveResponse saved = documentService.saveAgentContentInCurrentTransaction(
                    workspace, user, documentId, edited, 2L, "actual-write-2", "op-2");
            service.recordReceipt(noopExecution, "op-2", documentId, saved);
        });
        assertThat(service.lookup(new McpAgentApprovalService.ReceiptLookup(
                noopApproval, "op-2", workspace, user, grant, client)).status()).isEqualTo("NO_CHANGE");
    }

    @Test
    void partialSelectionUsesPlanOrderFullMaximumReserveAndChargesOnlyChangedReceipts() throws Exception {
        var priced = new McpAgentApprovalService(jdbc, new TransactionTemplate(manager), mapper, access, documents,
                credits, requester, "2", "5", "KRW", "i6-test-v1");
        credits.post(user, "grant", 10_000, "partial-grant:" + user, "test");
        JsonNode run = multiRun("agent_" + "6".repeat(32));
        when(requester.approveMcp(anyString(), anyString(), anyString(), anyInt(), anyString(), anyList(),
                anyString(), anyString(), anyString(), anyLong())).thenReturn(run);

        JsonNode quote = priced.projectRun(workspace, user, run);
        assertThat(quote.path("mcp_approval_quote").path("selected_operation_count").asInt()).isEqualTo(2);
        assertThat(quote.path("mcp_approval_quote").path("price_snapshot")
                .path("maximum_charge_minor").asLong()).isEqualTo(4);
        var dependencyMissing = new AgentRunApproveRequest(1, "c".repeat(64), java.util.List.of("op-2"),
                new AgentRunApproveRequest.McpPriceSnapshot(2L, 5L, "KRW", "i6-test-v1", 2L));
        assertThatThrownBy(() -> priced.approve(workspace, user, run.path("id").asText(), dependencyMissing, run))
                .isInstanceOf(InvalidAgentTurnRequestException.class);

        var reviewed = new AgentRunApproveRequest(1, "c".repeat(64), java.util.List.of("op-2", "op-1"),
                new AgentRunApproveRequest.McpPriceSnapshot(2L, 5L, "KRW", "i6-test-v1", 4L));
        priced.approve(workspace, user, run.path("id").asText(), reviewed, run);
        String approvalId = approvalId(run.path("id").asText());
        assertThat(jdbc.queryForObject("SELECT operation_ids::text FROM mcp_agent_approvals WHERE approval_id = ?::uuid",
                String.class, approvalId)).isEqualTo("[\"op-1\", \"op-2\"]");
        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(4_000);

        var createArguments = mapper.createObjectNode().put("name", "Folder").putNull("parent_folder_id");
        var createRequest = executionRequest(run, approvalId, "op-1", createArguments);
        var createExecution = priced.authorizeExecution(createRequest, "create_folder");
        new TransactionTemplate(manager).executeWithoutResult(status -> priced.recordReceipt(createExecution, "op-1",
                "create_folder", "FOLDER", UUID.randomUUID().toString(), null, 1, true, Instant.now()));
        priced.executionFinished(createExecution);

        var renameArguments = mapper.createObjectNode().put("document_id", "doc_i5").put("base_version", 1)
                .put("display_name", "Same");
        var renameRequest = executionRequest(run, approvalId, "op-2", renameArguments);
        var renameExecution = priced.authorizeExecution(renameRequest, "rename_document");
        new TransactionTemplate(manager).executeWithoutResult(status -> priced.recordReceipt(renameExecution, "op-2",
                "rename_document", "DOCUMENT", "doc_i5", 1L, 1, false, Instant.now()));
        priced.executionFinished(renameExecution);

        assertThat(jdbc.queryForObject("SELECT charged_minor FROM mcp_agent_approvals WHERE approval_id = ?::uuid",
                Long.class, approvalId)).isEqualTo(2);
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();
        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(8_000);
    }

    @Test
    void actualFolderCreateAndReceiptRollbackCommitAndReplayAsOneBoundary() throws Exception {
        credits.post(user, "grant", 2_000, "folder-grant:" + user, "test");
        JsonNode run = createFolderRun("agent_" + "5".repeat(32));
        when(requester.approveMcp(anyString(), anyString(), anyString(), anyInt(), anyString(), anyList(),
                anyString(), anyString(), anyString(), anyLong())).thenReturn(run);
        service.approve(workspace, user, run.path("id").asText(), request(), run);
        String approvalId = approvalId(run.path("id").asText());
        var arguments = mapper.createObjectNode().put("name", "Atomic folder").putNull("parent_folder_id");
        var execution = service.authorizeExecution(executionRequest(run, approvalId, "op-1", arguments),
                "create_folder");
        TransactionTemplate tx = new TransactionTemplate(manager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            var created = folderService.createAgentInCurrentTransaction(workspace, "Atomic folder", null);
            service.recordReceipt(execution, "op-1", "create_folder", "FOLDER", created.id().toString(),
                    null, created.currentVersion(), true, created.updatedAt());
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM folders WHERE workspace_id = ?", Integer.class,
                workspace)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_document_edit_receipts WHERE approval_id = ?::uuid",
                Integer.class, approvalId)).isZero();

        AtomicReference<McpAgentApprovalService.Receipt> committed = new AtomicReference<>();
        tx.executeWithoutResult(status -> {
            var created = folderService.createAgentInCurrentTransaction(workspace, "Atomic folder", null);
            committed.set(service.recordReceipt(execution, "op-1", "create_folder", "FOLDER",
                    created.id().toString(), null, created.currentVersion(), true, created.updatedAt()));
        });
        service.executionFinished(execution);
        assertThat(committed.get().beforeVersion()).isNull();
        assertThat(committed.get().afterVersion()).isOne();
        assertThat(committed.get().resourceId()).isEqualTo(jdbc.queryForObject(
                "SELECT id::text FROM folders WHERE workspace_id = ?", String.class, workspace));

        var replay = service.authorizeExecution(executionRequest(run, approvalId, "op-1", arguments),
                "create_folder");
        assertThat(replay.replay()).isEqualTo(committed.get());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM folders WHERE workspace_id = ?", Integer.class,
                workspace)).isOne();
    }

    private AgentRunApproveRequest request() {
        return request("op-1");
    }

    private AgentRunApproveRequest request(String operationId) {
        return new AgentRunApproveRequest(1, "c".repeat(64), java.util.List.of(operationId),
                new AgentRunApproveRequest.McpPriceSnapshot(1L, 1L, "KRW", "i5-test-v1", 1L));
    }

    private JsonNode run(String runId) throws Exception {
        return run(runId, "doc_i5");
    }

    private JsonNode run(String runId, String documentId) throws Exception {
        return run(runId, documentId, "op-1");
    }

    private JsonNode run(String runId, String documentId, String operationId) throws Exception {
        return run(runId, documentId, operationId, 1);
    }

    private JsonNode run(String runId, String documentId, String operationId, long baseVersion) throws Exception {
        return mapper.readTree("""
                {"id":"%s","workspace_id":"%s","status":"awaiting_approval","origin":"MCP_CLIENT",
                 "grant_id":"%s","client_id":"%s","policy_generation":2,
                 "invocation_id":"%s","spec_version":"2025-11-25","tool_name":"propose_document_edit",
                 "plan":{"id":"plan-1","version":1,"operation_hash":"%s","status":"awaiting_approval",
                   "operations":[{"id":"%s","tool_name":"apply_document_edit","depends_on":[],
                     "target_id":"%s","base_version":%d,
                     "arguments":{"document_id":"%s","base_version":%d}}]}}
                """.formatted(runId, workspace, grant, client, UUID.randomUUID(), "c".repeat(64), operationId,
                        documentId, baseVersion, documentId, baseVersion));
    }

    private JsonNode multiRun(String runId) throws Exception {
        return mapper.readTree("""
                {"id":"%s","workspace_id":"%s","status":"awaiting_approval","origin":"MCP_CLIENT",
                 "grant_id":"%s","client_id":"%s","policy_generation":2,
                 "invocation_id":"%s","spec_version":"2025-11-25","tool_name":"propose_plan",
                 "plan":{"id":"plan-1","version":1,"operation_hash":"%s","status":"awaiting_approval",
                   "operations":[
                     {"id":"op-1","tool_name":"create_folder","depends_on":[],"target_id":null,
                      "base_version":null,"arguments":{"name":"Folder","parent_folder_id":null}},
                     {"id":"op-2","tool_name":"rename_document","depends_on":["op-1"],"target_id":"doc_i5",
                      "base_version":1,"arguments":{"document_id":"doc_i5","display_name":"Same","base_version":1}},
                     {"id":"op-3","tool_name":"create_folder","depends_on":[],"target_id":null,
                      "base_version":null,"suggested_selected":false,
                      "arguments":{"name":"Other","parent_folder_id":null}}]}}
                """.formatted(runId, workspace, grant, client, UUID.randomUUID(), "c".repeat(64)));
    }

    private JsonNode createFolderRun(String runId) throws Exception {
        return mapper.readTree("""
                {"id":"%s","workspace_id":"%s","status":"awaiting_approval","origin":"MCP_CLIENT",
                 "grant_id":"%s","client_id":"%s","policy_generation":2,
                 "invocation_id":"%s","spec_version":"2025-11-25","tool_name":"propose_plan",
                 "plan":{"id":"plan-1","version":1,"operation_hash":"%s","status":"awaiting_approval",
                   "operations":[{"id":"op-1","tool_name":"create_folder","depends_on":[],
                     "target_id":null,"base_version":null,
                     "arguments":{"name":"Atomic folder","parent_folder_id":null}}]}}
                """.formatted(runId, workspace, grant, client, UUID.randomUUID(), "c".repeat(64)));
    }

    private String approvalId(String runId) {
        return jdbc.queryForObject("SELECT approval_id::text FROM mcp_agent_approvals WHERE run_id = ?",
                String.class, runId);
    }

    private fruition.core.agent.dto.AgentToolExecuteRequest execute(JsonNode run, String approvalId,
                                                                    String operationId, long baseVersion) {
        var arguments = mapper.createObjectNode();
        arguments.put("document_id", run.path("plan").path("operations").get(0).path("target_id").asText());
        arguments.put("base_version", baseVersion);
        return new fruition.core.agent.dto.AgentToolExecuteRequest(run.path("id").asText(), workspace, user,
                "plan-1", 1, "c".repeat(64), operationId, UUID.randomUUID().toString(), arguments,
                approvalId, UUID.randomUUID().toString(), grant, client, 2L);
    }

    private fruition.core.agent.dto.AgentToolExecuteRequest executionRequest(
            JsonNode run, String approvalId, String operationId, JsonNode arguments) {
        return new fruition.core.agent.dto.AgentToolExecuteRequest(run.path("id").asText(), workspace, user,
                "plan-1", 1, "c".repeat(64), operationId, UUID.randomUUID().toString(), arguments,
                approvalId, UUID.randomUUID().toString(), grant, client, 2L);
    }

    private McpAgentApprovalService.ApprovalLookup approvalLookup(JsonNode run, String approvalId,
                                                                  String operationId) {
        return new McpAgentApprovalService.ApprovalLookup(approvalId, run.path("id").asText(), workspace, user,
                1, "c".repeat(64), java.util.List.of(operationId), grant, client, 2L);
    }
}
