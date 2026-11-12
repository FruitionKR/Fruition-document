package fruition.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.aihistory.service.McpWikiOperationRegistrar;
import fruition.core.purge.DataPurgeService;
import fruition.core.usage.service.CreditService;
import fruition.core.wiki.service.McpWikiStateGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "test.isolated-database=i9_mcp_wiki_approval")
@Import(TestcontainersConfiguration.class)
class McpWikiApprovalServiceIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper mapper;
    @Autowired CreditService credits;
    @Autowired DataPurgeService purge;
    @Autowired OperationLogRepository operations;

    private McpAccessClient access;
    private McpWikiSourceService sources;
    private McpWikiDraftClient ai;
    private McpWikiApprovalService service;
    private final String user = "user_i9_" + UUID.randomUUID();
    private final String workspace = "ws_i9";
    private final String grant = UUID.randomUUID().toString();
    private final String client = "https://client.example/i9.json";
    private final String draft = UUID.randomUUID().toString();
    private final String task = UUID.randomUUID().toString();
    private McpIdentity identity;
    private JsonNode preview;

    @BeforeEach
    void setUp() throws Exception {
        access = mock(McpAccessClient.class);
        sources = mock(McpWikiSourceService.class);
        ai = mock(McpWikiDraftClient.class);
        identity = new McpIdentity(user, workspace, client, grant, 2, Set.of("wiki_write"), "MEMBER",
                Set.of("wiki.lint"), "Trusted MCP Client");
        preview = mapper.readTree("""
                {"schema_version":"mcp.wiki-preview.v1","draft_id":"%s","draft_kind":"LINT",
                 "draft_version":1,"draft_hash":"sha256:%s",
                 "source_snapshots":[{"snapshot_id":"00000000-0000-4000-8000-000000000011",
                   "document_id":"doc_i9","source_version":4,"source_content_hash":"sha256:%s"}],
                 "target_pages":[],"public_state_hash":"sha256:%s","changes":[],"complete":true}
                """.formatted(draft, "a".repeat(64), "b".repeat(64), "c".repeat(64)));
        when(sources.read(any(McpIdentity.class), anyString())).thenReturn(new McpWikiSourceService.Response(
                "mcp.wiki-source.v1", "doc_i9", "Source", workspace, 4, "sha256:" + "b".repeat(64),
                "text/markdown", List.of(new McpWikiSourceService.Block("B0001", 1, "body",
                "sha256:" + "d".repeat(64), 1, 1, "paragraph"))));
        when(access.authorizeWikiWebApproval(any(), anyString())).thenReturn(identity);
        when(access.authorizeWikiOperationBoundary(any(), anyLong(), anyString())).thenReturn(identity);
        service = new McpWikiApprovalService(jdbc, new TransactionTemplate(manager), mapper, access, sources, ai,
                mock(McpWikiStateGateway.class), mock(WorkspaceAccessGuard.class), credits,
                new McpWikiOperationRegistrar(operations), "ai.maintenance.command",
                "KRW", "", "", "", "", "", "3", "apply", "fixed 3 KRW per apply", "i9-test-v1");
        service.registerDraft(identity, task, preview);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM ai_command_outbox WHERE run_id IN (SELECT 'mcp-index:'||event_id FROM mcp_wiki_publication_inbox WHERE draft_id=?::uuid)", draft);
        jdbc.update("DELETE FROM mcp_wiki_publication_inbox WHERE draft_id=?::uuid", draft);
        jdbc.update("DELETE FROM mcp_wiki_drafts WHERE draft_id=?::uuid", draft);
        jdbc.update("DELETE FROM ai_operation_logs WHERE user_id=?", user);
        jdbc.update("DELETE FROM credit_entries WHERE user_id=?", user);
        jdbc.update("DELETE FROM credit_accounts WHERE user_id=?", user);
    }

    @Test
    void lintDraftWithPageBodyChangeRegisters() throws Exception {
        String bodyDraft = UUID.randomUUID().toString();
        JsonNode bodyPreview = mapper.readTree("""
                {"schema_version":"mcp.wiki-preview.v1","draft_id":"%s","draft_kind":"LINT",
                 "draft_version":1,"draft_hash":"sha256:%s",
                 "source_snapshots":[{"snapshot_id":"00000000-0000-4000-8000-000000000012",
                   "document_id":"doc_i9","source_version":4,"source_content_hash":"sha256:%s"}],
                 "target_pages":[{"page_id":"wiki_page_1","version":1,"content_hash":"sha256:%s"}],
                 "public_state_hash":"sha256:%s",
                 "changes":[{"change_id":"sha256:%s","kind":"PAGE_BODY","action":"UPDATE","page_id":"wiki_page_1",
                   "subject_id":"wiki_page_1","before_text":"# A","after_text":"# A\\n- added","evidence":[]}],
                 "complete":true}
                """.formatted(bodyDraft, "a".repeat(64), "b".repeat(64), "e".repeat(64), "c".repeat(64),
                "f".repeat(64)));
        try {
            service.registerDraft(identity, UUID.randomUUID().toString(), bodyPreview);
            assertThat(jdbc.queryForObject("SELECT draft_kind FROM mcp_wiki_drafts WHERE draft_id=?::uuid",
                    String.class, bodyDraft)).isEqualTo("LINT");
        } finally {
            jdbc.update("DELETE FROM mcp_wiki_drafts WHERE draft_id=?::uuid", bodyDraft);
        }
    }

    @Test
    void fullReservationLookupAndDuplicatePublicationSettleExactlyOnce() throws Exception {
        credits.post(user, "grant", 10_000, "i9-grant:" + user, "test");
        when(ai.getPreview(draft, 1, workspace, user)).thenReturn(preview);
        when(ai.post(anyString(), any())).thenReturn(mapper.readTree("""
                {"schema_version":"mcp.wiki-result.v1","approval_id":"ignored","operation_id":"ignored",
                 "draft_id":"%s","state":"APPLYING","changed":false,"event_id":null,
                 "published_reference_hash":null,"committed_at":null,"error_code":null}
                """.formatted(draft)));

        McpWikiApprovalService.ApprovalRequest approval = approval();
        assertThatThrownBy(() -> service.approve(workspace, user, approval))
                .isInstanceOf(McpToolException.class);
        String approvalId = jdbc.queryForObject("SELECT approval_id::text FROM mcp_wiki_drafts WHERE draft_id=?::uuid",
                String.class, draft);
        String operationId = jdbc.queryForObject("SELECT operation_id FROM mcp_wiki_drafts WHERE draft_id=?::uuid",
                String.class, draft);
        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(3_000);
        assertThat(jdbc.queryForMap("SELECT operation_type,status,target_document_id FROM ai_operation_logs WHERE operation_id=?",
                operationId)).containsEntry("operation_type", "lint").containsEntry("status", "processing")
                .containsEntry("target_document_id", null);
        assertThat(service.lookup(lookup(approvalId, operationId)))
                .extracting(McpWikiApprovalService.ApprovalStatus::status,
                        McpWikiApprovalService.ApprovalStatus::reason)
                .containsExactly("VALID", null);
        when(ai.getResult(approvalId, operationId, workspace, user)).thenReturn(mapper.readTree("""
                {"schema_version":"mcp.wiki-result.v1","approval_id":"%s","operation_id":"%s",
                 "draft_id":null,"state":"NOT_FOUND","changed":null,"event_id":null,
                 "published_reference_hash":null,"committed_at":null,"error_code":null}
                """.formatted(approvalId, operationId)));
        service.approve(workspace, user, approval);
        verify(ai, times(1)).post(anyString(), any());

        var event = event(approvalId, operationId, "PUBLISHED", true);
        service.acceptPublication(event);
        service.acceptPublication(event);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_wiki_publication_inbox WHERE approval_id=?::uuid",
                Integer.class, approvalId)).isOne();
        assertThat(jdbc.queryForList("SELECT topic,message_key,payload FROM ai_command_outbox WHERE run_id=?",
                "mcp-index:" + event.eventId())).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("topic", "ai.maintenance.command").containsEntry("message_key", workspace);
            assertThat(mapper.readTree((String) row.get("payload")).path("kind").asText()).isEqualTo("embedding_recovery");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE id=?", Integer.class,
                "mcp-index:" + event.eventId())).isZero();
        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(7_000);
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();
        assertThat(jdbc.queryForObject("SELECT charged_minor FROM mcp_wiki_drafts WHERE draft_id=?::uuid",
                Long.class, draft)).isEqualTo(3);
    }

    @Test
    void noChangeIsFreeAndChangedCanonicalRetryConflicts() {
        credits.post(user, "grant", 10_000, "i9-nochange:" + user, "test");
        reserveDirectly();
        String approvalId = approvalId();
        String operationId = operationId();
        var event = event(approvalId, operationId, "NO_CHANGE", false);

        service.acceptPublication(event);
        service.acceptPublication(event);

        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(10_000);
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_command_outbox WHERE run_id=?", Integer.class,
                "mcp-index:" + event.eventId())).isZero();
        assertThatThrownBy(() -> service.acceptPublication(new McpWikiApprovalService.PublicationEvent(
                event.eventId(), event.eventType(), event.approvalId(), event.operationId(), event.draftId(),
                event.workspaceId(), event.userId(), event.grantId(), event.clientId(), event.kind(),
                "PUBLISHED", true, event.publishedReferenceHash(), event.committedAt())))
                .hasMessageContaining("MCP_WIKI_PUBLICATION_CONFLICT");
    }

    @Test
    void indexWakeCommitsAndRollsBackWithPublicationInbox() {
        credits.post(user, "grant", 10_000, "i9-wake:" + user, "test");
        reserveDirectly();
        var event = event(approvalId(), operationId(), "PUBLISHED", true);
        String wake = "SELECT count(*) FROM ai_command_outbox WHERE run_id=?";

        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            service.acceptPublication(event);
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject(wake, Integer.class, "mcp-index:" + event.eventId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_wiki_publication_inbox WHERE event_id=?::uuid",
                Integer.class, event.eventId())).isZero();

        service.acceptPublication(event);
        service.acceptPublication(event);
        assertThat(jdbc.queryForObject(wake, Integer.class, "mcp-index:" + event.eventId())).isOne();
    }

    @Test
    void insufficientCreditLeavesDraftUnapprovedAndSourceDriftInvalidatesLookup() {
        when(ai.getPreview(draft, 1, workspace, user)).thenReturn(preview);
        assertThatThrownBy(() -> service.approve(workspace, user, approval()))
                .isInstanceOf(CreditService.InsufficientCreditException.class);
        assertThat(jdbc.queryForObject("SELECT approval_id IS NULL FROM mcp_wiki_drafts WHERE draft_id=?::uuid",
                Boolean.class, draft)).isTrue();

        credits.post(user, "grant", 10_000, "i9-drift:" + user, "test");
        reserveDirectly();
        when(sources.read(any(McpIdentity.class), anyString())).thenReturn(new McpWikiSourceService.Response(
                "mcp.wiki-source.v1", "doc_i9", "Source", workspace, 5, "sha256:" + "e".repeat(64),
                "text/markdown", List.of(new McpWikiSourceService.Block("B0001", 1, "changed",
                "sha256:" + "f".repeat(64), 1, 1, "paragraph"))));
        assertThat(service.lookup(lookup(approvalId(), operationId())))
                .extracting(McpWikiApprovalService.ApprovalStatus::status,
                        McpWikiApprovalService.ApprovalStatus::reason)
                .containsExactly("INVALID", "SOURCE_CHANGED");
        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(3_000);
    }

    @Test
    void purgeScrubsPreviewFencesLookupAndStillSettlesConfirmedLatePublication() {
        credits.post(user, "grant", 10_000, "i9-purge:" + user, "test");
        reserveDirectly();
        String approvalId = approvalId(), operationId = operationId();

        purge.purgeUser(user);

        assertThat(jdbc.queryForObject("SELECT preview IS NULL FROM mcp_wiki_drafts WHERE draft_id=?::uuid",
                Boolean.class, draft)).isTrue();
        assertThat(service.lookup(lookup(approvalId, operationId)))
                .extracting(McpWikiApprovalService.ApprovalStatus::status,
                        McpWikiApprovalService.ApprovalStatus::reason)
                .containsExactly("FENCED", "PURGED");
        service.acceptPublication(event(approvalId, operationId, "PUBLISHED", true));
        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(7_000);
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();
        assertThat(jdbc.queryForObject("SELECT settlement_status FROM mcp_wiki_drafts WHERE draft_id=?::uuid",
                String.class, draft)).isEqualTo("SETTLED");
    }

    @Test
    void targetVersionMatchesAiUpdatedAtMicroseconds() {
        assertThat(McpWikiApprovalService.pageVersion(Instant.parse("2026-08-13T04:25:24.371948Z")))
                .isEqualTo(1_786_595_124_371_948L);
    }

    @Test
    void concurrentExactApprovalCreatesOneOperationReservationAndForward() throws Exception {
        credits.post(user, "grant", 10_000, "i9-concurrent:" + user, "test");
        when(ai.getPreview(draft, 1, workspace, user)).thenReturn(preview);
        when(ai.post(anyString(), any())).thenAnswer(call -> {
            @SuppressWarnings("unchecked") var body = (java.util.Map<String, Object>) call.getArgument(1);
            var result = mapper.createObjectNode();
            result.put("schema_version", "mcp.wiki-result.v1");
            result.put("approval_id", String.valueOf(body.get("approval_id")));
            result.put("operation_id", String.valueOf(body.get("operation_id")));
            result.put("draft_id", draft);
            result.put("state", "APPLYING");
            result.putNull("changed"); result.putNull("event_id"); result.putNull("published_reference_hash");
            result.putNull("committed_at"); result.putNull("error_code");
            return result;
        });
        when(ai.getResult(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            var result = mapper.createObjectNode();
            result.put("schema_version", "mcp.wiki-result.v1");
            result.put("approval_id", call.getArgument(0, String.class));
            result.put("operation_id", call.getArgument(1, String.class));
            result.putNull("draft_id"); result.put("state", "NOT_FOUND"); result.putNull("changed");
            result.putNull("event_id"); result.putNull("published_reference_hash"); result.putNull("committed_at");
            result.putNull("error_code");
            return result;
        });
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var calls = java.util.stream.IntStream.range(0, 2).mapToObj(ignored -> executor.submit(() -> {
                ready.countDown();
                start.await();
                return service.approve(workspace, user, approval());
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var call : calls) call.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_operation_logs WHERE user_id=?", Integer.class, user)).isOne();
        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(3_000);
        verify(ai, times(1)).post(anyString(), any());
    }

    private void reserveDirectly() {
        String approvalId = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        jdbc.update("UPDATE mcp_wiki_drafts SET approval_id=?::uuid,operation_id=?,canonical_approval_hash=?,"
                        + "client_name='Trusted MCP Client',approval_status='EXECUTING',publication_status='APPLYING',"
                        + "settlement_status='RESERVED',reserved_credit_milli=3000 WHERE draft_id=?::uuid",
                approvalId, operationId, "sha256:" + "9".repeat(64), draft);
        credits.reserveMcpApproval(approvalId, user, 3_000);
    }

    private String approvalId() {
        return jdbc.queryForObject("SELECT approval_id::text FROM mcp_wiki_drafts WHERE draft_id=?::uuid",
                String.class, draft);
    }

    private String operationId() {
        return jdbc.queryForObject("SELECT operation_id FROM mcp_wiki_drafts WHERE draft_id=?::uuid", String.class, draft);
    }

    private McpWikiApprovalService.ApprovalRequest approval() {
        return new McpWikiApprovalService.ApprovalRequest(draft, 1, "sha256:" + "a".repeat(64),
                "sha256:" + "c".repeat(64));
    }

    private McpWikiApprovalService.ApprovalLookup lookup(String approvalId, String operationId) {
        return new McpWikiApprovalService.ApprovalLookup(approvalId, operationId, draft, 1,
                "sha256:" + "a".repeat(64), "sha256:" + "c".repeat(64), workspace, user, grant, client, 2,
                List.of(new McpWikiApprovalService.Source("00000000-0000-4000-8000-000000000011", "doc_i9", 4,
                        "sha256:" + "b".repeat(64))));
    }

    private McpWikiApprovalService.PublicationEvent event(String approvalId, String operationId,
                                                           String outcome, boolean changed) {
        return new McpWikiApprovalService.PublicationEvent(UUID.randomUUID().toString(),
                "mcp.wiki.publication.v1", approvalId, operationId, draft, workspace, user, grant, client,
                "LINT", outcome, changed, "sha256:" + "8".repeat(64), Instant.now().toString());
    }
}
