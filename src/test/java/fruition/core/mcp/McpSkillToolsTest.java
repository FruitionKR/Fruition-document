package fruition.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.skill.exception.PipelineSkillException;
import fruition.core.skill.service.SkillService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class McpSkillToolsTest {
    private static final String SKILL = "11111111-2222-4333-8444-555555555555";
    private static final McpIdentity ID = new McpIdentity("user-1", "ws-1", "https://client.example", "grant",
            1, Set.of("read"), "MEMBER", Set.of());
    private final ObjectMapper mapper = new ObjectMapper();
    private final SkillService service = mock(SkillService.class);
    private final McpDocumentProposalService proposals = mock(McpDocumentProposalService.class);
    private final McpSkillTools tools = new McpSkillTools(service, proposals, mapper);

    @Test void advertisesTwoStrictReadOnlyToolsThatPointToExistingProposals() {
        var list = tools.tools();
        assertThat(list).extracting(t -> t.name()).containsExactly("list_skills", "get_skill", "propose_skill_definition");
        list.subList(0, 2).forEach(tool -> {
            assertThat(tool.annotations().readOnlyHint()).isTrue();
            assertThat(tool.inputSchema().get("additionalProperties")).isEqualTo(false);
            assertThat(tool.description()).contains("propose_document_edit", "propose_plan");
        });
        assertThat(list.get(2).annotations().readOnlyHint()).isFalse();
        assertThat(list.get(2).annotations().destructiveHint()).isFalse();
        assertThat((List<Object>) list.get(1).inputSchema().get("required")).containsExactly("skill_id");
    }

    @Test void listsOnlyEnabledSameWorkspaceSkillsWithoutInstructions() throws Exception {
        when(service.list("ws-1", "user-1")).thenReturn(mapper.readTree("[" + skill(SKILL, "ws-1", true) + ","
                + skill("22222222-2222-4333-8444-555555555555", "ws-1", false) + ","
                + skill("33333333-2222-4333-8444-555555555555", "ws-other", true) + "]"));

        var result = (Map<String, Object>) tools.invoke(ID, "list_skills", Map.of());

        var skills = (List<?>) result.get("skills");
        assertThat(skills).hasSize(1);
        var only = (Map<String, Object>) skills.get(0);
        assertThat(only.get("skill_id")).isEqualTo(SKILL);
        assertThat(only.get("version")).isEqualTo(3L);
        assertThat(only).doesNotContainKeys("instructions_markdown", "allowed_tools");
        assertThat(result.get("data_boundary")).isEqualTo("UNTRUSTED_WORKSPACE_CONTENT");
    }

    @Test void getsEnabledVersionWithSanitizedInstructions() throws Exception {
        when(service.get("ws-1", "user-1", SKILL)).thenReturn(mapper.readTree(skill(SKILL, "ws-1", true)));

        var result = (Map<String, Object>) tools.invoke(ID, "get_skill", Map.of("skill_id", SKILL));

        assertThat(result.get("schema_version")).isEqualTo("mcp.skill.v1");
        assertThat(result.get("allowed_tools")).isEqualTo(List.of("apply_document_edit"));
        assertThat((String) result.get("instructions_markdown")).contains("{문서}").doesNotContain("<script>");
        assertThat(result.keySet()).containsExactlyElementsOf(
                ((Map<String, Object>) tools.tools().get(1).outputSchema().get("properties")).keySet());
    }

    @Test void rejectsMalformedInputBeforeCallingSkillService() {
        for (Map<String, Object> args : List.<Map<String, Object>>of(Map.of(), Map.of("skill_id", "../x"),
                Map.of("skill_id", SKILL, "extra", 1))) {
            assertThatThrownBy(() -> tools.invoke(ID, "get_skill", args)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> tools.invoke(ID, "list_skills", Map.of("x", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }

    @Test void disabledOrMissingSkillIsNotFound() throws Exception {
        when(service.get("ws-1", "user-1", SKILL)).thenReturn(mapper.readTree(skill(SKILL, "ws-1", false)));
        assertThatThrownBy(() -> tools.invoke(ID, "get_skill", Map.of("skill_id", SKILL)))
                .isInstanceOfSatisfying(McpToolException.class, e -> assertThat(e.code()).isEqualTo("RESOURCE_NOT_FOUND"));

        when(service.get(any(), any(), any())).thenThrow(new PipelineSkillException("x", 404, null));
        assertThatThrownBy(() -> tools.invoke(ID, "get_skill", Map.of("skill_id", SKILL)))
                .isInstanceOfSatisfying(McpToolException.class, e -> assertThat(e.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }

    /** 실제 DB처럼 비활성 Skill도 enabled_version을 그대로 갖는다(set_enabled는 status만 바꾼다). */
    @Test void proposesCanonicalDefinitionThroughExistingAgentProposalPath() {
        var expected = new McpDocumentProposalService.Result("run", "awaiting_approval", "https://review/x");
        var captured = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        when(proposals.submit(org.mockito.ArgumentMatchers.eq(ID), any())).thenAnswer(call -> {
            captured.set(call.getArgument(1));
            return expected;
        });

        assertThat(tools.invoke(ID, "propose_skill_definition", proposal(null, null, "personal"))).isEqualTo(expected);

        var request = captured.get();
        var actor = (Map<String, Object>) request.get("actor");
        assertThat(actor.get("tool_name")).isEqualTo("propose_skill_definition");
        assertThat(actor.get("user_id")).isEqualTo("user-1");
        assertThat((String) request.get("request_hash")).matches("sha256:[0-9a-f]{64}");
        var definition = (Map<String, Object>) ((Map<String, Object>) request.get("proposal")).get("definition");
        assertThat(definition).containsKeys("skill_id", "expected_version", "targets", "result_mode", "requires_source");
        assertThat(definition.get("skill_id")).isNull();
    }

    @Test void teamOrExistingTeamSkillRequiresSkillAuthor() throws Exception {
        assertThatThrownBy(() -> tools.invoke(ID, "propose_skill_definition", proposal(null, null, "team")))
                .isInstanceOfSatisfying(McpToolException.class, e -> assertThat(e.code()).isEqualTo("PERMISSION_REVOKED"));
        when(service.get("ws-1", "user-1", SKILL)).thenReturn(mapper.readTree(skill(SKILL, "ws-1", true)));
        assertThatThrownBy(() -> tools.invoke(ID, "propose_skill_definition", proposal(SKILL, 3, null)))
                .isInstanceOfSatisfying(McpToolException.class, e -> assertThat(e.code()).isEqualTo("PERMISSION_REVOKED"));
        verifyNoInteractions(proposals);
    }

    @Test void rejectsMalformedProposalBeforeSubmitting() {
        var missingVersion = proposal(SKILL, null, null);
        var extra = new java.util.HashMap<>(proposal(null, null, "personal"));
        extra.put("confirmed", true);
        var badCapability = proposal(null, null, "personal");
        ((Map<String, Object>) badCapability.get("definition")).put("capabilities", List.of("delete"));
        for (Map<String, Object> args : List.of(missingVersion, extra, badCapability)) {
            assertThatThrownBy(() -> tools.invoke(ID, "propose_skill_definition", args))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(proposals);
    }

    private static Map<String, Object> proposal(String skillId, Integer version, String scope) {
        Map<String, Object> definition = new java.util.LinkedHashMap<>();
        definition.put("skill_id", skillId);
        definition.put("expected_version", version);
        definition.put("scope_type", scope);
        definition.put("name", "meeting-notes");
        definition.put("description", "회의록 정리");
        definition.put("instructions_markdown", "{문서}의 결정 사항을 정리한다.");
        definition.put("capabilities", List.of("document-edit"));
        definition.put("targets", null);
        definition.put("result_mode", null);
        definition.put("requires_source", null);
        return Map.of("idempotency_key", "k-1", "request_summary", "회의록 Skill", "definition", definition);
    }

    private static String skill(String id, String workspace, boolean enabled) {
        String version = "{\"id\":\"v\",\"version\":3,\"name\":\"meeting-notes\",\"description\":\"회의록 정리\","
                + "\"instructions_markdown\":\"{문서}를 정리한다.<script>x</script>\",\"capabilities\":[\"document-edit\"],"
                + "\"allowed_tools\":[\"apply_document_edit\"],\"targets\":[\"current_document\"],"
                + "\"result_mode\":\"edit\",\"requires_source\":true,\"lint_result\":{},\"status\":\"published\"}";
        return "{\"id\":\"" + id + "\",\"workspace_id\":\"" + workspace + "\",\"scope_type\":\"team\","
                + "\"owner_user_id\":null,\"slug\":\"meeting-notes\",\"status\":\"" + (enabled ? "enabled" : "disabled")
                + "\",\"enabled_version\":" + version + ",\"latest_version\":" + version + "}";
    }
}
