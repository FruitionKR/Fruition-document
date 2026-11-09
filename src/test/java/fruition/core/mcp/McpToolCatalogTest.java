package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class McpToolCatalogTest {
    @Test void catalogReadToolsAreStaticReadOnlyStrictAndAdvertiseBoundedWikiPageRead() {
        var pages = mock(McpWikiPageClient.class);
        var audit = mock(McpAuditLogger.class);
        var catalog = new McpToolCatalog(mock(McpWikiSearchClient.class), pages, mock(McpDocumentReadService.class),
                mock(McpDocumentProposalService.class),
                mock(McpPlanProposalService.class), mock(McpWikiProposalService.class), mock(McpSkillTools.class), mock(McpMeetingTools.class), mock(McpPdfTools.class), audit, new ObjectMapper(), McpTokenVerifierTest.props());
        var tools = catalog.tools();
        assertThat(tools).extracting(s -> s.tool().name()).containsExactly("search_wiki", "find_documents", "get_document", "get_wiki_page",
                        "propose_document_edit", "propose_plan", "start_ingest", "resume_ingest", "get_window",
                        "submit_window", "finalize_ingest", "submit_lint_changes");
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

        var page = new McpWikiPageClient.Response("mcp.wiki-page.v1", "page-a", "SOURCE", "Title", "body",
                "sha256:" + "a".repeat(64), "UNTRUSTED_WORKSPACE_CONTENT");
        when(pages.get(id, "page-a")).thenReturn(page);
        var pageTool = tools.get(3);
        assertThat(pageTool.tool().inputSchema().get("required")).isEqualTo(java.util.List.of("page_id"));
        @SuppressWarnings("unchecked")
        var inputProperties = (Map<String,Object>) pageTool.tool().inputSchema().get("properties");
        assertThat(inputProperties.get("page_id")).isEqualTo(Map.of("type", "string", "maxLength", 255,
                "pattern", "^[A-Za-z0-9][A-Za-z0-9_-]{0,254}$"));
        @SuppressWarnings("unchecked")
        var outputProperties = (Map<String,Object>) pageTool.tool().outputSchema().get("properties");
        assertThat(outputProperties.keySet()).containsExactly("schema_version", "page_id", "page_type", "title",
                "body", "content_hash", "data_boundary");
        assertThat(((Map<?,?>) outputProperties.get("content_hash")).get("description"))
                .isEqualTo("SHA-256 of the exact UTF-8 body returned by this tool");
        assertThat(outputProperties).doesNotContainKeys("version", "evidence", "next_cursor", "truncated");
        var pageResult = pageTool.callHandler().apply(
                McpTransportContext.create(Map.of(McpSecurityFilter.IDENTITY, id)),
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("get_wiki_page",
                        Map.of("page_id", "page-a"), null));
        assertThat(pageResult.isError()).isFalse();
        assertThat(pageResult.structuredContent()).isEqualTo(page);
        verify(audit).log(id, "get_wiki_page", java.util.List.of("page-a"), "SUCCESS", null);

        when(pages.get(id, "page-big")).thenReturn(new McpWikiPageClient.Response("mcp.wiki-page.v1", "page-big",
                "SOURCE", "Title", "x".repeat(60_000), "sha256:" + "b".repeat(64),
                "UNTRUSTED_WORKSPACE_CONTENT"));
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

    @Test void rejectsInvalidPageIdsBeforeAuditTargetsButRetainsValidatedDependencyTargets() {
        var pages = mock(McpWikiPageClient.class);
        var audit = mock(McpAuditLogger.class);
        var catalog = new McpToolCatalog(mock(McpWikiSearchClient.class), pages, mock(McpDocumentReadService.class),
                mock(McpDocumentProposalService.class),
                mock(McpPlanProposalService.class), mock(McpWikiProposalService.class), mock(McpSkillTools.class), mock(McpMeetingTools.class), mock(McpPdfTools.class), audit, new ObjectMapper(), McpTokenVerifierTest.props());
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
        var catalog = new McpToolCatalog(mock(McpWikiSearchClient.class), mock(McpWikiPageClient.class),
                mock(McpDocumentReadService.class), proposals, mock(McpPlanProposalService.class),
                mock(McpWikiProposalService.class), mock(McpSkillTools.class), mock(McpMeetingTools.class), mock(McpPdfTools.class), audit, new ObjectMapper(), McpTokenVerifierTest.props());
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
        var catalog = new McpToolCatalog(mock(McpWikiSearchClient.class), mock(McpWikiPageClient.class),
                mock(McpDocumentReadService.class), mock(McpDocumentProposalService.class), planProposals,
                mock(McpWikiProposalService.class), mock(McpSkillTools.class), mock(McpMeetingTools.class), mock(McpPdfTools.class), mock(McpAuditLogger.class), new ObjectMapper(),
                McpTokenVerifierTest.props());
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
        assertThat(variants.toString()).contains("source_document_id", "source_content_hash");
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
        var catalog = new McpToolCatalog(mock(McpWikiSearchClient.class), mock(McpWikiPageClient.class),
                mock(McpDocumentReadService.class), mock(McpDocumentProposalService.class),
                mock(McpPlanProposalService.class), wikiProposals, mock(McpSkillTools.class), mock(McpMeetingTools.class), mock(McpPdfTools.class), mock(McpAuditLogger.class), new ObjectMapper(),
                McpTokenVerifierTest.props());

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

    @Test void skillToolsAreWrappedWithReadAnnotationsAuditTargetAndErrorMapping() {
        var skills = mock(fruition.core.skill.service.SkillService.class);
        var audit = mock(McpAuditLogger.class);
        var catalog = new McpToolCatalog(mock(McpWikiSearchClient.class), mock(McpWikiPageClient.class),
                mock(McpDocumentReadService.class), mock(McpDocumentProposalService.class),
                mock(McpPlanProposalService.class), mock(McpWikiProposalService.class), new McpSkillTools(skills, mock(McpDocumentProposalService.class), new ObjectMapper()), mock(McpMeetingTools.class), mock(McpPdfTools.class),
                audit, new ObjectMapper(), McpTokenVerifierTest.props());
        var tools = catalog.tools();
        assertThat(tools).extracting(spec -> spec.tool().name()).hasSize(15).endsWith("list_skills", "get_skill", "propose_skill_definition");
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
}
