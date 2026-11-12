package fruition.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.usage.service.CreditService;
import fruition.shared.http.PipelineClientFactory;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "test.isolated-database=i9_mcp_wiki_proposals")
@Import(TestcontainersConfiguration.class)
class McpWikiProposalServiceIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper mapper;

    private MockWebServer aiServer;
    private McpAccessClient access;
    private McpWikiSourceService sources;
    private McpWikiApprovalService approvals;
    private CreditService credits;
    private McpWikiProposalService service;
    private final McpIdentity identity = new McpIdentity("user_i9_proposal", "workspace_i9_proposal",
            "https://client.example/i9-proposal.json", "00000000-0000-4000-8000-000000000041", 2,
            Set.of("wiki_write"), "MEMBER", Set.of("wiki.ingest", "wiki.lint"), "I9 Client");

    @BeforeEach
    void setUp() throws Exception {
        aiServer = new MockWebServer();
        aiServer.start();
        access = mock(McpAccessClient.class);
        sources = mock(McpWikiSourceService.class);
        approvals = mock(McpWikiApprovalService.class);
        credits = mock(CreditService.class);
        when(access.authorizeWikiProposal(any(), anyString())).thenReturn(identity);
        McpWikiDraftClient client = new McpWikiDraftClient(new PipelineClientFactory("internal-test"), mapper,
                McpTokenVerifierTest.props(), aiServer.url("/agent/runs").toString(), "i9-agent-token");
        service = new McpWikiProposalService(access, sources, client, approvals, credits, jdbc,
                new TransactionTemplate(manager), mapper, "https://review.example", "8", "100000");
    }

    @AfterEach
    void clean() throws Exception {
        jdbc.update("DELETE FROM mcp_wiki_lint_intents WHERE workspace_id=?", identity.workspaceId());
        jdbc.update("DELETE FROM mcp_wiki_ingest_handles WHERE workspace_id=?", identity.workspaceId());
        aiServer.shutdown();
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "null", "auth-page"})
    void ingestFlowMapsExternalEvidenceToOneBoundSnapshotAndProducesOnlyValidatedReviewUrl(String mergeTarget) throws Exception {
        String run = UUID.randomUUID().toString(), draft = UUID.randomUUID().toString();
        String snapshot = UUID.randomUUID().toString(), window = UUID.randomUUID().toString();
        aiServer.enqueue(json("""
                {"schema_version":"mcp.wiki-draft.v1","run_id":"%s","draft_id":"%s",
                 "state":"ACTIVE_WINDOWING","source_snapshot":{"snapshot_id":"%s","document_id":"doc_i9",
                 "source_version":4,"source_content_hash":"sha256:%s","block_count":1},"next_window_ordinal":1}
                """.formatted(run, draft, snapshot, "a".repeat(64))));
        aiServer.enqueue(json("""
                {"schema_version":"mcp.wiki-draft.v1","run_id":"%s","draft_id":"%s","state":"ACTIVE_WINDOWING",
                 "snapshot_id":"%s","next_window_ordinal":1,"submitted_window_count":0}
                """.formatted(run, draft, snapshot)));
        aiServer.enqueue(json("""
                {"schema_version":"mcp.wiki-draft.v1","run_id":"%s","draft_id":"%s","snapshot_id":"%s",
                 "window_id":"%s","window_ordinal":1,"window_hash":"sha256:%s",
                 "blocks":[{"block_id":"B0001","ordinal":1,"text":"evidence","content_hash":"sha256:%s"}],
                 "has_more":false,"data_boundary":"UNTRUSTED_WORKSPACE_CONTENT",
                 "concept_resolution":{"mode":"CLIENT_LLM_SEMANTIC","instructions":"Compare meaning before submitting."}}
                """.formatted(run, draft, snapshot, window, "b".repeat(64), "c".repeat(64))));
        aiServer.enqueue(json("""
                {"schema_version":"mcp.wiki-draft.v1","run_id":"%s","draft_id":"%s","window_id":"%s",
                 "accepted_submission_hash":"sha256:%s","state":"READY_TO_FINALIZE","next_window_ordinal":2}
                """.formatted(run, draft, window, "d".repeat(64))));
        aiServer.enqueue(json("""
                {"schema_version":"mcp.wiki-draft.v1","run_id":"%s","draft_id":"%s","draft_version":1,
                 "draft_hash":"sha256:%s","public_state_hash":"sha256:%s","state":"AWAITING_WEB_APPROVAL"}
                """.formatted(run, draft, "c".repeat(64), "d".repeat(64))));
        aiServer.enqueue(json(preview(draft, "INGEST", snapshot, "doc_i9", "a".repeat(64))));
        when(credits.paidAssistAllowed(identity.userId())).thenReturn(true);

        service.invoke(identity, "start_ingest", Map.of("idempotency_key", "start-1", "document_id", "doc_i9"));
        service.invoke(identity, "resume_ingest", Map.of("run_id", run));
        JsonNode windowResult = (JsonNode) service.invoke(identity, "get_window", Map.of("run_id", run, "window_ordinal", 1));
        assertThat(windowResult.path("concept_resolution").path("mode").asText()).isEqualTo("CLIENT_LLM_SEMANTIC");
        Map<String,Object> evidence = Map.of("document_id", "doc_i9", "block_id", "B0001", "quote", "evidence");
        Map<String,Object> contribution = linked("concept_name", "Concept", "statements", List.of(Map.of(
                "statement_id", "s1", "text", "evidence", "evidence", List.of(evidence))), "links", List.of());
        if (!"legacy".equals(mergeTarget)) contribution.put("merge_target_page_id", "null".equals(mergeTarget) ? null : mergeTarget);
        service.invoke(identity, "submit_window", linked("idempotency_key", "submit-1", "run_id", run,
                "window_id", window, "snapshot_id", snapshot, "window_hash", "sha256:" + "b".repeat(64),
                "contributions", List.of(contribution)));
        @SuppressWarnings("unchecked")
        Map<String,Object> finalized = (Map<String,Object>) service.invoke(identity, "finalize_ingest", Map.of(
                "idempotency_key", "final-1", "run_id", run, "snapshot_id", snapshot,
                "expected_submitted_window_count", 1));

        assertThat(finalized.get("review_url"))
                .isEqualTo("https://review.example/mcp/workspaces/workspace_i9_proposal/wiki-drafts/" + draft);
        var requests = new java.util.ArrayList<okhttp3.mockwebserver.RecordedRequest>();
        for (int i = 0; i < 6; i++) requests.add(aiServer.takeRequest());
        assertThat(requests.get(0).getHeader("X-Agent-Service-Token")).isEqualTo("i9-agent-token");
        JsonNode submitted = mapper.readTree(requests.get(3).getBody().readUtf8());
        JsonNode normalized = submitted.path("contributions").get(0);
        if ("auth-page".equals(mergeTarget)) {
            assertThat(normalized.path("merge_target_page_id").asText()).isEqualTo("auth-page");
        } else {
            assertThat(normalized.has("merge_target_page_id")).isFalse();
        }
        String stored = jdbc.queryForObject("SELECT contributions::text FROM mcp_wiki_ingest_windows WHERE run_id=?::uuid AND window_id=?::uuid", String.class, run, window);
        assertThat(mapper.readTree(stored).get(0)).isEqualTo(normalized);
        JsonNode internalEvidence = submitted.path("contributions").get(0).path("statements").get(0)
                .path("evidence").get(0);
        assertThat(internalEvidence.path("snapshot_id").asText()).isEqualTo(snapshot);
        assertThat(internalEvidence.has("document_id")).isFalse();
        for (int i = 0; i < 4; i++) {
            assertThat(mapper.readTree(requests.get(i).getBody().readUtf8()).has("jev_allowed")).isFalse();
        }
        JsonNode finalizeBody = mapper.readTree(requests.get(4).getBody().readUtf8());
        assertThat(requests.get(4).getPath()).endsWith("/finalize");
        assertThat(finalizeBody.path("jev_allowed").isBoolean()).isTrue();
        assertThat(finalizeBody.path("jev_allowed").booleanValue()).isTrue();
        assertThat(requests.get(5).getMethod()).isEqualTo("GET");
        assertThat(requests.get(5).getHeader("X-MCP-Workspace-Id")).isEqualTo(identity.workspaceId());
        verify(approvals).registerDraft(identity, run, mapper.readTree(preview(draft, "INGEST", snapshot,
                "doc_i9", "a".repeat(64))));
    }

    @Test
    void lintRetryRetainsServerSnapshotMappingAndNeverSendsExternalDocumentEvidenceToAi() throws Exception {
        String draft = UUID.randomUUID().toString();
        when(sources.read(any(McpProposalActor.class), anyString())).thenReturn(new McpWikiSourceService.Response(
                "mcp.wiki-source.v1", "doc_i9", "Source", identity.workspaceId(), 4, "sha256:" + "a".repeat(64),
                "text/markdown", List.of(new McpWikiSourceService.Block("B0001", 1, "quote",
                "sha256:" + "b".repeat(64), 1, 1, "paragraph"))));
        for (int i = 0; i < 2; i++) {
            aiServer.enqueue(json("""
                    {"schema_version":"mcp.wiki-draft.v1","draft_id":"%s","draft_version":1,
                     "draft_hash":"sha256:%s","public_state_hash":"sha256:%s","state":"AWAITING_WEB_APPROVAL"}
                    """.formatted(draft, "c".repeat(64), "d".repeat(64))));
            aiServer.enqueue(json(preview(draft, "LINT", "00000000-0000-4000-8000-000000000042",
                    "doc_i9", "a".repeat(64))));
        }
        Map<String,Object> args = linked("idempotency_key", "lint-1",
                "target_snapshot", List.of(Map.of("page_id", "page-1", "version", 3,
                        "content_hash", "sha256:" + "e".repeat(64))),
                "source_documents", List.of(Map.of("document_id", "doc_i9", "current_version", 4,
                        "content_hash", "sha256:" + "a".repeat(64))),
                "operations", List.of(linked("operation_id", "op-1", "kind", "UPSERT_STATEMENT",
                        "target_page_id", "page-1", "arguments", Map.of("statement_id", "s1", "text", "quote",
                                "evidence", List.of(Map.of("document_id", "doc_i9", "block_id", "B0001",
                                        "quote", "quote"))), "depends_on", List.of())));

        when(credits.paidAssistAllowed(identity.userId())).thenReturn(false, true);
        service.invoke(identity, "submit_lint_changes", args);
        service.invoke(identity, "submit_lint_changes", args);

        JsonNode first = mapper.readTree(aiServer.takeRequest().getBody().readUtf8());
        aiServer.takeRequest();
        JsonNode second = mapper.readTree(aiServer.takeRequest().getBody().readUtf8());
        aiServer.takeRequest();
        String firstSnapshot = first.path("source_snapshots").get(0).path("snapshot_id").asText();
        assertThat(second.path("source_snapshots").get(0).path("snapshot_id").asText()).isEqualTo(firstSnapshot);
        assertThat(second.path("request_hash").asText()).isEqualTo(first.path("request_hash").asText());
        assertThat(first.path("jev_allowed").isBoolean()).isTrue();
        assertThat(first.path("jev_allowed").booleanValue()).isFalse();
        assertThat(second.path("jev_allowed").booleanValue()).isTrue();
        JsonNode internalEvidence = first.path("operations").get(0).path("arguments").path("evidence").get(0);
        assertThat(internalEvidence.path("snapshot_id").asText()).isEqualTo(firstSnapshot);
        assertThat(internalEvidence.has("document_id")).isFalse();
        verify(sources).read(any(McpProposalActor.class), anyString());
        verify(access, times(2)).authorizeWikiProposal(any(), anyString());

        Map<String,Object> changed = new LinkedHashMap<>(args);
        changed.put("operations", List.of());
        assertThatThrownBy(() -> service.invoke(identity, "submit_lint_changes", changed))
                .isInstanceOf(McpToolException.class).hasMessage("INVALID_INPUT");
    }

    private MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String preview(String draft, String kind, String snapshot, String document, String sourceHash) {
        return """
                {"schema_version":"mcp.wiki-preview.v1","draft_id":"%s","draft_kind":"%s","draft_version":1,
                 "draft_hash":"sha256:%s","source_snapshots":[{"snapshot_id":"%s","document_id":"%s",
                 "source_version":4,"source_content_hash":"sha256:%s"}],"target_pages":[],
                 "public_state_hash":"sha256:%s","changes":[],"complete":true}
                """.formatted(draft, kind, "c".repeat(64), snapshot, document, sourceHash, "d".repeat(64));
    }

    private static Map<String,Object> linked(Object... values) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int index=0; index<values.length; index+=2) result.put((String) values[index], values[index+1]);
        return result;
    }
}
