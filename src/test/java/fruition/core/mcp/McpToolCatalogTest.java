package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.usage.service.CreditService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class McpToolCatalogTest {

    @Test void creditBalanceIsReadOnlySelfScopedAndFailsClosed() {
        var credits = mock(CreditService.class);
        var catalog = new McpToolCatalog(mock(McpWikiSearchClient.class), mock(McpWikiPageService.class),
                mock(McpDocumentReadService.class), mock(McpDocumentProposalService.class),
                mock(McpPlanProposalService.class), mock(McpWikiProposalService.class), mock(McpSkillTools.class),
                mock(McpMeetingTools.class), mock(McpHierarchyTools.class), mock(McpPdfTools.class), mock(McpDocumentUploadTools.class),
                mock(McpAuditLogger.class), new ObjectMapper(), McpTokenVerifierTest.props(), "", credits);
        var tool = catalog.tools().getLast();
        assertThat(tool.tool().name()).isEqualTo("get_credit_balance");
        assertThat(tool.tool().annotations().readOnlyHint()).isTrue();
        assertThat(tool.tool().inputSchema().get("additionalProperties")).isEqualTo(false);
        assertThat(tool.tool().description()).contains("personal", "1000", "available_krw_milli", "limit_exempt");
        var identity = new McpIdentity("user", "workspace", "client", "grant", 1, Set.of("read"), "MEMBER", Set.of());
        var context = McpTransportContext.create(Map.of(McpSecurityFilter.IDENTITY, identity));
        for (String field : java.util.List.of("user_id", "workspace_id", "cursor")) {
            var rejected = tool.callHandler().apply(context,
                    new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("get_credit_balance", Map.of(field, "other"), null));
            assertThat(rejected.isError()).isTrue();
            assertThat(((io.modelcontextprotocol.spec.McpSchema.TextContent) rejected.content().getFirst()).text())
                    .contains("INVALID_INPUT");
        }
        verifyNoMoreInteractions(credits);
        when(credits.credits("user")).thenThrow(new IllegalStateException("database unavailable"));
        var failed = tool.callHandler().apply(context,
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("get_credit_balance", Map.of(), null));
        assertThat(failed.isError()).isTrue();
        assertThat(((io.modelcontextprotocol.spec.McpSchema.TextContent) failed.content().getFirst()).text())
                .isEqualTo("{\"error\":\"DEPENDENCY_UNAVAILABLE\"}");
        verify(credits).credits("user");
        reset(credits);
        when(credits.credits("user")).thenReturn(new CreditService.Credits("user", "KRW", -160_041, 0, -160_041, true,
                java.util.List.of()));
        var exempt = tool.callHandler().apply(context,
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("get_credit_balance", Map.of(), null));
        assertThat(exempt.isError()).isFalse();
        assertThat(new ObjectMapper().valueToTree(exempt.structuredContent()).path("limit_exempt").asBoolean()).isTrue();
    }
    @Test void catalogReadToolsAreStaticReadOnlyStrictAndAdvertiseBoundedWikiPageRead() {
        var pages = mock(McpWikiPageService.class);
        var audit = mock(McpAuditLogger.class);
        var catalog = new McpToolCatalog(mock(McpWikiSearchClient.class), pages, mock(McpDocumentReadService.class),
                mock(McpDocumentProposalService.class),
                mock(McpPlanProposalService.class), mock(McpWikiProposalService.class), mock(McpSkillTools.class), mock(McpMeetingTools.class), mock(McpHierarchyTools.class), mock(McpPdfTools.class), mock(McpDocumentUploadTools.class), audit, new ObjectMapper(), McpTokenVerifierTest.props(), "https://app.example/", mock(CreditService.class));
        var tools = catalog.tools();
        assertThat(tools).extracting(s -> s.tool().name()).containsExactly("search_wiki", "find_documents", "get_document", "get_wiki_page",
                        "propose_document_edit", "propose_plan", "start_ingest", "resume_ingest", "get_window",
                        "submit_window", "finalize_ingest", "submit_lint_changes", "get_credit_balance");
        assertThat(tools.subList(0, 4)).allSatisfy(s -> {
            assertThat(s.tool().inputSchema().get("additionalProperties")).isEqualTo(false);
            assertThat(s.tool().outputSchema().get("additionalProperties")).isEqualTo(false);
            assertThat(s.tool().annotations().readOnlyHint()).isTrue();
            assertThat(s.tool().annotations().destructiveHint()).isFalse();
        });
        var id = new McpIdentity("user", "workspace", "https://client.example/client.json", "grant", 1,
                Set.of("read"), "MEMBER", Set.of());
        var result = tools.get(1).callHandler().apply(McpTransportContext.create(Map.of(McpSecurityFilter.IDENTITY, id)),
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("find_documents",
                        Map.of("query", "x", "cursor", "unexpected", "page_size", 1, "extra", true), null));
        assertThat(result.isError()).isTrue();

        var page = new McpWikiPageService.Response("mcp.wiki-page.v1", "page-a", "SOURCE", "Title", null, "body",
                "sha256:" + "a".repeat(64), null, java.util.List.of(), java.util.List.of(), java.util.List.of(), false, null,
                "UNTRUSTED_WORKSPACE_CONTENT");
        when(pages.get(id, "page-a")).thenReturn(page);
        var pageTool = tools.get(3);
        assertThat(pageTool.tool().inputSchema().get("required")).isEqualTo(java.util.List.of("page_id"));
        @SuppressWarnings("unchecked")
        var inputProperties = (Map<String,Object>) pageTool.tool().inputSchema().get("properties");
        assertThat(inputProperties.get("page_id")).isEqualTo(Map.of("type", "string", "maxLength", 255,
                "pattern", "^[A-Za-z0-9][A-Za-z0-9_-]{0,254}$"));
        @SuppressWarnings("unchecked")
        var outputProperties = (Map<String,Object>) pageTool.tool().outputSchema().get("properties");
        assertThat(outputProperties.keySet()).containsExactlyInAnyOrder("schema_version", "page_id", "page_type", "title",
                "slug", "body", "content_hash", "lint_target", "statements", "links", "lint_target_page_ids",
                "lint_target_truncated", "lint_context_error", "data_boundary", "workspace_id");
        assertThat(((Map<?,?>) outputProperties.get("content_hash")).get("description"))
                .isEqualTo("SHA-256 of the exact UTF-8 body returned by this tool");
        assertThat(outputProperties).doesNotContainKeys("version", "evidence", "next_cursor", "truncated");
        var pageResult = pageTool.callHandler().apply(
                McpTransportContext.create(Map.of(McpSecurityFilter.IDENTITY, id)),
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("get_wiki_page",
                        Map.of("page_id", "page-a"), null));
        assertThat(pageResult.isError()).isFalse();
        var linkedPage = new ObjectMapper().valueToTree(pageResult.structuredContent());
        assertThat(linkedPage.has("source_url")).isFalse();
        assertThat(linkedPage.path("workspace_id").asText()).isEqualTo("workspace");
        assertThat(linkedPage.path("body").asText()).isEqualTo(page.body());
        assertThat(linkedPage.path("content_hash").asText()).isEqualTo(page.contentHash());
        verify(audit).log(id, "get_wiki_page", java.util.List.of("page-a"), "SUCCESS", null);

        when(pages.get(id, "page-big")).thenReturn(new McpWikiPageService.Response("mcp.wiki-page.v1", "page-big",
                "SOURCE", "Title", null, "x".repeat(60_000), "sha256:" + "b".repeat(64), null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), false, null, "UNTRUSTED_WORKSPACE_CONTENT"));
        var oversizedEnvelope = pageTool.callHandler().apply(
                McpTransportContext.create(Map.of(McpSecurityFilter.IDENTITY, id)),
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("get_wiki_page",
                        Map.of("page_id", "page-big"), null));
        assertThat(oversizedEnvelope.isError()).isTrue();
        verify(audit).log(id, "get_wiki_page", java.util.List.of("page-big"), "ERROR",
                "RESPONSE_LIMIT_EXCEEDED");

        var invalidPage = pageTool.callHandler().apply(
                McpTransportContext.create(Map.of(McpSecurityFilter.IDENTITY, id)),
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("get_wiki_page",
                        Map.of("page_id", "page-a", "workspace_id", "override"), null));
        assertThat(invalidPage.isError()).isTrue();
    }

    @Test void readToolsLinkSourcesInBothContentFormatsAndPreserveEvidenceAndCursors() throws Exception {
        var mapper = new ObjectMapper();
        var wiki = mock(McpWikiSearchClient.class);
        var documents = mock(McpDocumentReadService.class);
        var pages = mock(McpWikiPageService.class);
        var id = new McpIdentity("user", "workspace", "https://client.example/client.json", "grant", 1,
                Set.of("read"), "MEMBER", Set.of());
        var context = McpTransportContext.create(Map.of(McpSecurityFilter.IDENTITY, id));
        var evidence = new McpWikiSearchClient.Evidence("doc_1&doc=other", "B0046");
        // A query delimiter in an upstream ID must remain part of that ID, never become another parameter.
        when(wiki.search(id, "attention", "cursor", 1)).thenReturn(new McpWikiSearchClient.Response(
                "mcp.wiki-search.v1", java.util.List.of(new McpWikiSearchClient.Result("page&doc=other", "CONCEPT",
                "Attention", 1, "sha256:" + "a".repeat(64), "Summary", java.util.List.of(evidence))),
                "next", true, "UNTRUSTED_WORKSPACE_CONTENT"));
        when(documents.find(id, "attention", "cursor", 1)).thenReturn(new McpDocumentReadService.DocumentSearchResponse(
                "mcp.document-search.v1", java.util.List.of(new McpDocumentReadService.DocumentResult("doc_1",
                "Paper", 1, "hash", "Summary", java.util.List.of("B0046"))), "next", true, "UNTRUSTED_WORKSPACE_CONTENT"));
        when(documents.get(id, "doc_1", "cursor")).thenReturn(new McpDocumentReadService.DocumentReadResponse(
                "mcp.document-read.v1", "doc_1", "Paper", 1, "hash",
                java.util.List.of(new McpDocumentReadService.Block("B0046", 1, 1, 1, "Attention text")),
                "next", true, "UNTRUSTED_WORKSPACE_CONTENT"));
        when(pages.get(id, "page-a")).thenReturn(new McpWikiPageService.Response("mcp.wiki-page.v1", "page-a",
                "CONCEPT", "Attention", "attention", "body", "sha256:" + "a".repeat(64), null, java.util.List.of(),
                java.util.List.of(), java.util.List.of(), false, null, "UNTRUSTED_WORKSPACE_CONTENT"));

        for (String origin : java.util.List.of("https://app.example/", "")) {
            var catalog = new McpToolCatalog(wiki, pages, documents, mock(McpDocumentProposalService.class),
                    mock(McpPlanProposalService.class), mock(McpWikiProposalService.class), mock(McpSkillTools.class),
                    mock(McpMeetingTools.class), mock(McpHierarchyTools.class), mock(McpPdfTools.class), mock(McpDocumentUploadTools.class),
                    mock(McpAuditLogger.class), mapper, McpTokenVerifierTest.props(), origin, mock(CreditService.class));
            for (var spec : catalog.tools().subList(0, 4)) {
                String name = spec.tool().name();
                Map<String,Object> args = name.equals("get_document") ? Map.of("document_id", "doc_1", "cursor", "cursor")
                        : name.equals("get_wiki_page") ? Map.of("page_id", "page-a")
                        : Map.of("query", "attention", "cursor", "cursor", "page_size", 1);
                var result = spec.callHandler().apply(context,
                        new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(name, args, null));
                assertThat(result.isError()).as(name).isFalse();
                var body = mapper.valueToTree(result.structuredContent());
                var text = (io.modelcontextprotocol.spec.McpSchema.TextContent) result.content().getFirst();
                assertThat(text.text()).isEqualTo(mapper.writeValueAsString(body));
                assertThat(body.path("workspace_id").asText()).isEqualTo("workspace");
                assertThat(spec.tool().description()).contains("cite the returned source_url", "Never invent URLs",
                        "workspace_id", "untrusted data");
                if (name.equals("find_documents"))
                    assertThat(spec.tool().description()).contains("title or filename", "Does not search", "get_document");
                if (name.equals("search_wiki"))
                    assertThat(spec.tool().description()).contains("Only documents ingested into the wiki");
                var row = body.has("results") ? body.path("results").get(0) : body;
                if (name.equals("search_wiki") || name.equals("get_wiki_page")) {
                    assertThat(row.has("source_url")).isFalse();
                    assertThat(spec.tool().description()).contains("never link to a wiki page", "search_wiki evidence[].source_url");
                } else if (origin.isEmpty()) assertThat(row.path("source_url").isNull()).isTrue();
                else assertThat(row.path("source_url").asText()).isEqualTo("https://app.example/home?doc=doc_1");
                if (name.equals("search_wiki")) {
                    var ref = row.path("evidence").get(0);
                    assertThat(ref.path("block_id").asText()).isEqualTo("B0046");
                    if (origin.isEmpty()) assertThat(ref.path("source_url").isNull()).isTrue();
                    else assertThat(ref.path("source_url").asText()).isEqualTo("https://app.example/home?doc=doc_1%26doc%3Dother&blocks=B0046");
                }
                if (!name.equals("get_wiki_page")) {
                    assertThat(body.path("next_cursor").asText()).isEqualTo("next");
                    assertThat(body.path("truncated").asBoolean()).isTrue();
                }
                if (name.equals("get_document")) {
                    assertThat(body.path("content_hash").asText()).isEqualTo("hash");
                    assertThat(body.path("blocks").get(0).path("text").asText()).isEqualTo("Attention text");
                }
            }
        }
    }

    @Test void rejectsInvalidPageIdsBeforeAuditTargetsButRetainsValidatedDependencyTargets() {
        var pages = mock(McpWikiPageService.class);
        var audit = mock(McpAuditLogger.class);
        var catalog = new McpToolCatalog(mock(McpWikiSearchClient.class), pages, mock(McpDocumentReadService.class),
                mock(McpDocumentProposalService.class),
                mock(McpPlanProposalService.class), mock(McpWikiProposalService.class), mock(McpSkillTools.class), mock(McpMeetingTools.class), mock(McpHierarchyTools.class), mock(McpPdfTools.class), mock(McpDocumentUploadTools.class), audit, new ObjectMapper(), McpTokenVerifierTest.props(), "https://app.example/", mock(CreditService.class));
        var pageTool = catalog.tools().get(3);
        var id = new McpIdentity("user", "workspace", "https://client.example/client.json", "grant", 1,
                Set.of("read"), "MEMBER", Set.of());
        var context = McpTransportContext.create(Map.of(McpSecurityFilter.IDENTITY, id));

        var malformed = pageTool.callHandler().apply(context,
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("get_wiki_page",
                        Map.of("page_id", "../internal"), null));
        var oversized = pageTool.callHandler().apply(context,
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("get_wiki_page",
                        Map.of("page_id", "a".repeat(256)), null));
        when(pages.get(id, "page-valid")).thenThrow(new McpToolException("DEPENDENCY_UNAVAILABLE"));
        var dependencyFailure = pageTool.callHandler().apply(context,
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("get_wiki_page",
                        Map.of("page_id", "page-valid"), null));

        assertThat(malformed.isError()).isTrue();
        assertThat(oversized.isError()).isTrue();
        assertThat(dependencyFailure.isError()).isTrue();
        verify(audit, times(2)).log(id, "get_wiki_page", java.util.List.of(), "ERROR", "INVALID_INPUT");
        verify(audit).log(id, "get_wiki_page", java.util.List.of("page-valid"), "ERROR",
                "DEPENDENCY_UNAVAILABLE");
        verify(pages).get(id, "page-valid");
        verifyNoMoreInteractions(pages);
    }

    @Test void proposalToolIsStrictInvocableAndRegistered() {
        var proposals = mock(McpDocumentProposalService.class);
        var audit = mock(McpAuditLogger.class);
        var catalog = new McpToolCatalog(mock(McpWikiSearchClient.class), mock(McpWikiPageService.class),
                mock(McpDocumentReadService.class), proposals, mock(McpPlanProposalService.class),
                mock(McpWikiProposalService.class), mock(McpSkillTools.class), mock(McpMeetingTools.class), mock(McpHierarchyTools.class), mock(McpPdfTools.class), mock(McpDocumentUploadTools.class), audit, new ObjectMapper(), McpTokenVerifierTest.props(), "https://app.example/", mock(CreditService.class));
        var identity = new McpIdentity("user", "workspace", "https://client.example/client.json",
                "00000000-0000-4000-8000-000000000001", 2, Set.of("write_propose"), "OWNER", Set.of());
        Map<String,Object> arguments = proposalArguments();
        when(proposals.propose(identity, arguments))
                .thenReturn(new McpDocumentProposalService.Result("00000000-0000-4000-8000-000000000003",
                        "awaiting_approval",
                        "https://app.example/mcp/workspaces/workspace/runs/00000000-0000-4000-8000-000000000003"));

        var specification = catalog.tools().get(4);
        assertThat(specification.tool().name()).isEqualTo("propose_document_edit");
        assertThat(specification.tool().inputSchema().get("additionalProperties")).isEqualTo(false);
        assertThat(specification.tool().outputSchema().get("required"))
                .isEqualTo(java.util.List.of("run_id", "status", "review_url"));
        assertThat(specification.tool().annotations().readOnlyHint()).isFalse();
        assertThat(specification.tool().annotations().destructiveHint()).isFalse();

        var result = specification.callHandler().apply(
                McpTransportContext.create(Map.of(McpSecurityFilter.IDENTITY, identity)),
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("propose_document_edit", arguments, null));

        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isEqualTo(new McpDocumentProposalService.Result(
                "00000000-0000-4000-8000-000000000003", "awaiting_approval",
                "https://app.example/mcp/workspaces/workspace/runs/00000000-0000-4000-8000-000000000003"));
        verify(proposals).propose(identity, arguments);
        verify(audit).log(identity, "propose_document_edit", java.util.List.of("doc_1"), "SUCCESS", null);

        Map<String,Object> unknown = new java.util.HashMap<>(arguments);
        unknown.put("workspace_id", "forged");
        var rejected = specification.callHandler().apply(
                McpTransportContext.create(Map.of(McpSecurityFilter.IDENTITY, identity)),
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("propose_document_edit", unknown, null));
        assertThat(rejected.isError()).isTrue();
        verifyNoMoreInteractions(proposals);
    }

    @Test void planToolIsStrictInvocableAndRegistered() {
        var planProposals = mock(McpPlanProposalService.class);
        var catalog = new McpToolCatalog(mock(McpWikiSearchClient.class), mock(McpWikiPageService.class),
                mock(McpDocumentReadService.class), mock(McpDocumentProposalService.class), planProposals,
                mock(McpWikiProposalService.class), mock(McpSkillTools.class), mock(McpMeetingTools.class), mock(McpHierarchyTools.class), mock(McpPdfTools.class), mock(McpDocumentUploadTools.class), mock(McpAuditLogger.class), new ObjectMapper(),
                McpTokenVerifierTest.props(), "https://app.example/", mock(CreditService.class));
        var identity = new McpIdentity("user", "workspace", "https://client.example/client.json",
                "00000000-0000-4000-8000-000000000001", 2, Set.of("write_propose"), "OWNER", Set.of());
        Map<String,Object> operation = new java.util.LinkedHashMap<>();
        operation.put("id", "op-1"); operation.put("sequence", 1); operation.put("tool_name", "create_folder");
        operation.put("target_type", "folder"); operation.put("target_id", null); operation.put("base_version", null);
        operation.put("source_parent_id", null); operation.put("destination_parent_id", null);
        Map<String,Object> operationArguments = new java.util.LinkedHashMap<>();
        operationArguments.put("name", "Folder"); operationArguments.put("parent_folder_id", null);
        operation.put("arguments", operationArguments);
        operation.put("reason", "Create folder"); operation.put("depends_on", java.util.List.of());
        Map<String,Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("idempotency_key", "key-1"); arguments.put("request_summary", "Create folder");
        arguments.put("target_folder_id", null); arguments.put("operations", java.util.List.of(operation));
        arguments.put("artifacts", java.util.List.of());
        var expected = new McpDocumentProposalService.Result("run-1", "awaiting_approval", "https://review/run-1");
        when(planProposals.propose(identity, arguments)).thenReturn(expected);

        var specification = catalog.tools().get(5);
        var variants = new ObjectMapper().valueToTree(specification.tool().inputSchema())
                .path("properties").path("operations").path("items").path("properties").path("arguments").path("oneOf");
        // PDF 변환본 저장은 finalize_pdf_conversion만 제안한다. 클라이언트용 propose_plan 스키마에는 없다.
        assertThat(variants.toString()).doesNotContain("source_document_id", "content_result_key");
        assertThat(specification.tool().name()).isEqualTo("propose_plan");
        assertThat(specification.tool().inputSchema().get("additionalProperties")).isEqualTo(false);
        assertThat(specification.tool().annotations().readOnlyHint()).isFalse();
        var result = specification.callHandler().apply(
                McpTransportContext.create(Map.of(McpSecurityFilter.IDENTITY, identity)),
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("propose_plan", arguments, null));
        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isEqualTo(expected);
        verify(planProposals).propose(identity, arguments);
    }

    @Test void sixWikiToolsHaveExactSchemasInPublicCatalog() {
        var wikiProposals = mock(McpWikiProposalService.class);
        var catalog = new McpToolCatalog(mock(McpWikiSearchClient.class), mock(McpWikiPageService.class),
                mock(McpDocumentReadService.class), mock(McpDocumentProposalService.class),
                mock(McpPlanProposalService.class), wikiProposals, mock(McpSkillTools.class), mock(McpMeetingTools.class), mock(McpHierarchyTools.class), mock(McpPdfTools.class), mock(McpDocumentUploadTools.class), mock(McpAuditLogger.class), new ObjectMapper(),
                McpTokenVerifierTest.props(), "https://app.example/", mock(CreditService.class));

        var staged = catalog.tools().subList(6, 12);
        assertThat(staged).extracting(spec -> spec.tool().name()).containsExactly(
                "start_ingest", "resume_ingest", "get_window", "submit_window", "finalize_ingest",
                "submit_lint_changes");
        assertThat(staged).allSatisfy(spec -> {
            assertThat(spec.tool().inputSchema().get("additionalProperties")).isEqualTo(false);
            assertThat(spec.tool().outputSchema().get("additionalProperties")).isEqualTo(false);
            assertThat(spec.tool().annotations().readOnlyHint()).isFalse();
            assertThat(spec.tool().annotations().destructiveHint()).isFalse();
        });
        var required = java.util.List.of(Set.of("idempotency_key", "document_id"), Set.of("run_id"),
                Set.of("window_ordinal", "run_id"),
                Set.of("idempotency_key", "run_id", "window_id", "snapshot_id", "window_hash", "contributions"),
                Set.of("expected_submitted_window_count", "idempotency_key", "run_id", "snapshot_id"),
                Set.of("source_documents", "operations", "target_snapshot", "idempotency_key"));
        for (int i = 0; i < staged.size(); i++) {
            @SuppressWarnings("unchecked")
            var actual = Set.copyOf((java.util.List<String>) staged.get(i).tool().inputSchema().get("required"));
            assertThat(actual).isEqualTo(required.get(i));
        }
        for (int i : new int[] {0, 4, 5})
            assertThat(staged.get(i).tool().description()).contains("Fruition fee", "no server LLM", "get_credit_balance");
        assertThat(staged.get(5).tool().description()).contains("block_id values from get_document");
        assertThat(staged.get(4).tool().description()).contains("unless get_credit_balance reports limit_exempt");
        assertThat(staged.get(5).tool().description()).contains("LEDGER_RECONCILIATION_PENDING is transient", "LEDGER_CONTRADICTION");
        var schema = new ObjectMapper().valueToTree(staged.get(3).tool().inputSchema());
        var contribution = schema.path("properties").path("contributions").path("items");
        assertThat(contribution.path("properties").has("merge_target_page_id")).isTrue();
        assertThat(contribution.path("required").toString()).doesNotContain("merge_target_page_id");
        assertThat(staged.get(3).tool().description()).contains("search_wiki", "get_wiki_page", "by meaning", "merge_target_page_id");
        var output = new ObjectMapper().valueToTree(staged.get(2).tool().outputSchema());
        assertThat(output.path("properties").path("concept_resolution").path("properties").path("mode").path("const").asText())
                .isEqualTo("CLIENT_LLM_SEMANTIC");
        assertThat(staged.get(4).tool().outputSchema().get("required")).isEqualTo(java.util.List.of(
                "schema_version", "run_id", "draft_id", "draft_version", "draft_hash", "public_state_hash",
                "state", "review_url"));
    }

    @Test void everyReviewUrlToolTellsClientToOpenItAndWikiSubmitsDocumentEvidenceRules() {
        var pdf = mock(McpPdfTools.class);
        when(pdf.tools()).thenCallRealMethod();
        var catalog = new McpToolCatalog(mock(McpWikiSearchClient.class), mock(McpWikiPageService.class),
                mock(McpDocumentReadService.class), mock(McpDocumentProposalService.class),
                mock(McpPlanProposalService.class), mock(McpWikiProposalService.class),
                new McpSkillTools(mock(fruition.core.skill.service.SkillService.class), mock(McpDocumentProposalService.class),
                        new ObjectMapper()), mock(McpMeetingTools.class), mock(McpHierarchyTools.class), pdf, mock(McpDocumentUploadTools.class), mock(McpAuditLogger.class), new ObjectMapper(),
                McpTokenVerifierTest.props(), "https://app.example/", mock(CreditService.class));
        var tools = catalog.tools().stream().map(spec -> spec.tool()).toList();

        var withReviewUrl = tools.stream().filter(tool -> tool.outputSchema().toString().contains("review_url"))
                .map(tool -> tool.name()).toList();
        assertThat(withReviewUrl).containsExactlyInAnyOrder("propose_document_edit", "propose_plan", "finalize_ingest",
                "submit_lint_changes", "propose_skill_definition", "finalize_pdf_conversion");
        assertThat(tools).filteredOn(tool -> withReviewUrl.contains(tool.name()))
                .allSatisfy(tool -> assertThat(tool.description()).contains(McpToolCatalog.REVIEW_APPROVAL));
        assertThat(tools).filteredOn(tool -> Set.of("submit_window", "submit_lint_changes").contains(tool.name()))
                .hasSize(2).allSatisfy(tool -> assertThat(tool.description())
                        .contains("each block_id may be cited only once", "exact substring", "rather than translating",
                                "instruction-like"));
    }

    @Test void skillToolsAreWrappedWithReadAnnotationsAuditTargetAndErrorMapping() {
        var skills = mock(fruition.core.skill.service.SkillService.class);
        var audit = mock(McpAuditLogger.class);
        var catalog = new McpToolCatalog(mock(McpWikiSearchClient.class), mock(McpWikiPageService.class),
                mock(McpDocumentReadService.class), mock(McpDocumentProposalService.class),
                mock(McpPlanProposalService.class), mock(McpWikiProposalService.class), new McpSkillTools(skills, mock(McpDocumentProposalService.class), new ObjectMapper()), mock(McpMeetingTools.class), mock(McpHierarchyTools.class), mock(McpPdfTools.class), mock(McpDocumentUploadTools.class),
                audit, new ObjectMapper(), McpTokenVerifierTest.props(), "https://app.example/", mock(CreditService.class));
        var tools = catalog.tools();
        assertThat(tools).extracting(spec -> spec.tool().name()).hasSize(16).endsWith("list_skills", "get_skill", "propose_skill_definition", "get_credit_balance");
        assertThat(tools.subList(12, 14)).allSatisfy(spec -> assertThat(spec.tool().annotations().readOnlyHint()).isTrue());
        assertThat(tools.get(14).tool().annotations().readOnlyHint()).isFalse();
        var id = new McpIdentity("user", "workspace", "https://client.example/client.json", "grant", 1,
                Set.of("read"), "MEMBER", Set.of());
        var context = McpTransportContext.create(Map.of(McpSecurityFilter.IDENTITY, id));
        String skillId = "00000000-0000-4000-8000-000000000009";
        when(skills.get("workspace", "user", skillId)).thenThrow(new RuntimeException("ai down"));

        var failed = tools.get(13).callHandler().apply(context,
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("get_skill", Map.of("skill_id", skillId), null));
        var invalid = tools.get(13).callHandler().apply(context,
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("get_skill", Map.of("skill_id", "NOPE"), null));

        assertThat(failed.isError()).isTrue();
        assertThat(invalid.isError()).isTrue();
        verify(audit).log(id, "get_skill", java.util.List.of(skillId), "ERROR", "DEPENDENCY_UNAVAILABLE");
        verify(audit).log(id, "get_skill", java.util.List.of(), "ERROR", "INVALID_INPUT");
    }

    private static Map<String,Object> proposalArguments() {
        return Map.of(
                "idempotency_key", "request-1",
                "document_id", "doc_1",
                "base_version", 1,
                "target", Map.of("type", "selection", "start_line", 1, "end_line", 1),
                "instruction", "Fix title",
                "replacement_markdown", "x");
    }

    @Test void uploadToolsWrapWebDirectUploadWithAuditAndStableErrors() throws Exception {
        var uploads = mock(fruition.core.document.service.DocumentDirectUploadService.class);
        var audit = mock(McpAuditLogger.class);
        var catalog = new McpToolCatalog(mock(McpWikiSearchClient.class), mock(McpWikiPageService.class),
                mock(McpDocumentReadService.class), mock(McpDocumentProposalService.class),
                mock(McpPlanProposalService.class), mock(McpWikiProposalService.class), mock(McpSkillTools.class),
                mock(McpMeetingTools.class), mock(McpHierarchyTools.class), mock(McpPdfTools.class),
                new McpDocumentUploadTools(uploads, mock(fruition.core.document.service.McpDocumentDataGateway.class), new ObjectMapper()),
                audit, new ObjectMapper(), McpTokenVerifierTest.props(), "https://app.example/", mock(CreditService.class));
        var tools = catalog.tools();
        assertThat(tools).extracting(spec -> spec.tool().name())
                .endsWith("start_document_upload", "get_upload_part_urls", "complete_document_upload", "get_credit_balance");
        assertThat(tools.subList(tools.size() - 4, tools.size() - 1)).allSatisfy(spec -> {
            assertThat(spec.tool().inputSchema().get("additionalProperties")).isEqualTo(false);
            assertThat(spec.tool().annotations().readOnlyHint()).isFalse();
            assertThat(spec.tool().annotations().destructiveHint()).isFalse();
            assertThat(spec.tool().annotations().openWorldHint()).isFalse();
            assertThat(spec.tool().description()).contains("upload the file in the Fruition web app");
        });
        assertThat(tools.subList(tools.size() - 4, tools.size() - 1))
                .extracting(spec -> spec.tool().annotations().idempotentHint()).containsExactly(false, true, true);
        assertThat(tools.get(tools.size() - 4).tool().description()).contains("confirm with the user before uploading");
        assertThat(tools.get(tools.size() - 2).tool().description()).contains("confirm with the user before uploading");
        assertThat(tools.get(tools.size() - 4).tool().description()).contains("PUT the raw bytes of each part");

        String folder = "00000000-0000-4000-8000-000000000001";
        when(uploads.start(org.mockito.ArgumentMatchers.eq("workspace"), org.mockito.ArgumentMatchers.eq("user"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new fruition.core.document.exception.UnsupportedDocumentFileException("x"));
        var id = new McpIdentity("user", "workspace", "https://client.example/client.json", "grant", 1,
                Set.of("write_propose"), "MEMBER", Set.of());
        var args = new java.util.HashMap<String, Object>();
        args.put("filename", "a.docx"); args.put("size", 5); args.put("folder_id", folder);
        var result = tools.get(tools.size() - 4).callHandler().apply(
                McpTransportContext.create(Map.of(McpSecurityFilter.IDENTITY, id)),
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("start_document_upload", args, null));
        assertThat(result.isError()).isTrue();
        assertThat(result.content().toString()).contains("UNSUPPORTED_FILE_TYPE");
        verify(audit).log(id, "start_document_upload", java.util.List.of(folder), "ERROR", "UNSUPPORTED_FILE_TYPE");
    }
}
