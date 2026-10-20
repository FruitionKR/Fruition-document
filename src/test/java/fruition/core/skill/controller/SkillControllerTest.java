package fruition.core.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fruition.core.CoreExceptionHandler;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.config.SecurityConfig;
import fruition.core.skill.dto.SkillAuthoringRequest;
import fruition.core.skill.dto.SkillPublishRequest;
import fruition.core.skill.dto.SkillUpdateRequest;
import fruition.core.skill.exception.PipelineSkillException;
import fruition.core.skill.exception.SkillReferenceDocumentTooLargeException;
import fruition.core.skill.repository.PipelineSkillRequester;
import fruition.core.skill.service.SkillReferenceService;
import fruition.core.skill.service.SkillService;
import fruition.shared.http.PipelineClientFactory;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import io.swagger.v3.core.converter.ModelConverters;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest({SkillController.class, SkillReferenceController.class})
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        SkillReferenceTokenFilter.class})
@TestPropertySource(properties = "app.skill.agent-token=test-agent-token")
class SkillControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean SkillService skillService;
    @MockBean SkillReferenceService referenceService;
    @MockBean fruition.core.skill.service.SkillTestRunService skillTestRunService;

    @Test
    void delete_usesAuthenticatedActorAndReturnsNoContent() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/workspaces/" + WORKSPACE_ID + "/skills/skill_1")
                        .header("Authorization", bearer())
                        .param("user_id", "forged-user"))
                .andExpect(status().isNoContent());
        org.mockito.Mockito.verify(skillService).delete(WORKSPACE_ID, USER_ID, "skill_1");
    }

    @Test
    void delete_requiresAuthentication() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/workspaces/" + WORKSPACE_ID + "/skills/skill_1"))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(skillService);
    }

    @Test
    void delete_preservesPipelineNotFound() throws Exception {
        org.mockito.Mockito.doThrow(new PipelineSkillException("삭제 권한 없음", 404, "{}"))
                .when(skillService).delete(WORKSPACE_ID, USER_ID, "skill_1");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/workspaces/" + WORKSPACE_ID + "/skills/skill_1")
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void author_forwardsPathWorkspaceAndPrincipalUser() throws Exception {
        when(skillService.author(eq(WORKSPACE_ID), eq(USER_ID), any(SkillAuthoringRequest.class), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(objectMapper.readTree("{\"status\":\"proposal_ready\",\"name\":\"meeting-notes\"}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/author")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SkillAuthoringRequest(
                                "personal", "meeting-notes", null,
                                "회의록 Skill을 만들어줘", "enhance", List.of("doc_1")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("proposal_ready"))
                .andExpect(jsonPath("$.name").value("meeting-notes"));
    }

    @Test
    void publish_acceptsMissingAllowedTools() throws Exception {
        when(skillService.publish(eq(WORKSPACE_ID), eq(USER_ID), any(SkillPublishRequest.class), anyString()))
                .thenReturn(objectMapper.readTree("{\"status\":\"published\",\"allowed_tools\":[\"create_document\"]}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/author/publish")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope_type\":\"personal\",\"name\":\"meeting-notes\",\"description\":\"회의록 작성\","
                                + "\"instructions_markdown\":\"# 작성 절차\",\"capabilities\":[\"document-create\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed_tools[0]").value("create_document"));

        var captor = org.mockito.ArgumentCaptor.forClass(SkillPublishRequest.class);
        org.mockito.Mockito.verify(skillService).publish(eq(WORKSPACE_ID), eq(USER_ID), captor.capture(), anyString());
        assertThat(captor.getValue().allowedTools()).isNull();
    }

    @Test
    void publish_rejectsBlankAllowedToolEntry() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/author/publish")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope_type\":\"personal\",\"name\":\"meeting-notes\",\"description\":\"회의록 작성\","
                                + "\"instructions_markdown\":\"# 작성 절차\",\"capabilities\":[\"document-create\"],"
                                + "\"allowed_tools\":[\"\"]}"))
                .andExpect(status().is4xxClientError());
        org.mockito.Mockito.verifyNoInteractions(skillService);
    }

    @Test
    void publish_forwardsTargetsAndResultMode() throws Exception {
        when(skillService.publish(eq(WORKSPACE_ID), eq(USER_ID), any(SkillPublishRequest.class), anyString()))
                .thenReturn(objectMapper.readTree("{\"status\":\"published\",\"targets\":[\"selection\"],\"result_mode\":\"edit\"}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/author/publish")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(",\"targets\":[\"selection\"],\"result_mode\":\"edit\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets[0]").value("selection"))
                .andExpect(jsonPath("$.result_mode").value("edit"));

        var captor = org.mockito.ArgumentCaptor.forClass(SkillPublishRequest.class);
        org.mockito.Mockito.verify(skillService).publish(eq(WORKSPACE_ID), eq(USER_ID), captor.capture(), anyString());
        assertThat(captor.getValue().targets()).containsExactly("selection");
        assertThat(captor.getValue().resultMode()).isEqualTo("edit");
    }

    @Test
    void publishAndUpdate_forwardRequiresSourceAndReturnItFromAi() throws Exception {
        when(skillService.publish(eq(WORKSPACE_ID), eq(USER_ID), any(SkillPublishRequest.class), anyString()))
                .thenReturn(objectMapper.readTree("{\"status\":\"published\",\"requires_source\":true}"));
        when(skillService.update(eq(WORKSPACE_ID), eq(USER_ID), eq("skill_1"), any(SkillUpdateRequest.class), anyString()))
                .thenReturn(objectMapper.readTree("{\"status\":\"published\",\"requires_source\":false}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/author/publish")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(",\"requires_source\":true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requires_source").value(true));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/api/workspaces/" + WORKSPACE_ID + "/skills/skill_1")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"meeting-notes\",\"description\":\"회의록 작성\","
                                + "\"instructions_markdown\":\"# 작성 절차\",\"requires_source\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requires_source").value(false));

        var publishCaptor = org.mockito.ArgumentCaptor.forClass(SkillPublishRequest.class);
        org.mockito.Mockito.verify(skillService).publish(eq(WORKSPACE_ID), eq(USER_ID), publishCaptor.capture(), anyString());
        assertThat(publishCaptor.getValue().requiresSource()).isTrue();
        var updateCaptor = org.mockito.ArgumentCaptor.forClass(SkillUpdateRequest.class);
        org.mockito.Mockito.verify(skillService).update(eq(WORKSPACE_ID), eq(USER_ID), eq("skill_1"), updateCaptor.capture(), anyString());
        assertThat(updateCaptor.getValue().requiresSource()).isFalse();
    }

    @Test
    void publish_leavesRequiresSourceNullWhenOmitted() throws Exception {
        when(skillService.publish(eq(WORKSPACE_ID), eq(USER_ID), any(SkillPublishRequest.class), anyString()))
                .thenReturn(objectMapper.readTree("{\"status\":\"published\"}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/author/publish")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("")))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(SkillPublishRequest.class);
        org.mockito.Mockito.verify(skillService).publish(eq(WORKSPACE_ID), eq(USER_ID), captor.capture(), anyString());
        assertThat(captor.getValue().requiresSource()).isNull();
    }

    @Test
    void publishAndUpdate_rejectInvalidTargetsAndResultMode() throws Exception {
        for (String extra : List.of(
                ",\"targets\":[\"whole_workspace\"]",
                ",\"targets\":[\"current_document\",\"selection\",\"selection\"]",
                ",\"result_mode\":\"replace\"")) {
            mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/author/publish")
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(publishBody(extra)))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                    "/api/workspaces/" + WORKSPACE_ID + "/skills/skill_1")
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"meeting-notes\",\"description\":\"회의록 작성\","
                                    + "\"instructions_markdown\":\"# 작성 절차\"" + extra + "}"))
                    .andExpect(status().isBadRequest());
        }
        org.mockito.Mockito.verifyNoInteractions(skillService);
    }

    private String publishBody(String extra) {
        return "{\"scope_type\":\"personal\",\"name\":\"meeting-notes\",\"description\":\"회의록 작성\","
                + "\"instructions_markdown\":\"# 작성 절차\",\"capabilities\":[\"document-edit\"]" + extra + "}";
    }

    @Test
    void testRun_forwardsPrincipalAndGeneratesRunId() throws Exception {
        when(skillTestRunService.testRun(eq(WORKSPACE_ID), eq(USER_ID),
                any(fruition.core.skill.dto.SkillTestRunRequest.class), anyString()))
                .thenReturn(objectMapper.readTree("{\"run_id\":\"skill_test_1\",\"status\":\"blocked\",\"changes\":[],\"issues\":[{}]}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/test-run")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testRunBody(",\"target\":{\"type\":\"selection\",\"start_line\":10,\"end_line\":24}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("blocked"));

        var request = org.mockito.ArgumentCaptor.forClass(fruition.core.skill.dto.SkillTestRunRequest.class);
        var runId = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(skillTestRunService).testRun(eq(WORKSPACE_ID), eq(USER_ID), request.capture(), runId.capture());
        assertThat(request.getValue().documentId()).isEqualTo("doc_1");
        assertThat(request.getValue().target().startLine()).isEqualTo(10);
        assertThat(request.getValue().target().endLine()).isEqualTo(24);
        assertThat(runId.getValue()).startsWith("skill_test_");
    }

    @Test
    void testRun_rejectsInvalidBodyBeforeService() throws Exception {
        for (String body : List.of(
                "{\"name\":\"research-summary\",\"description\":\"연구 요약\",\"instructions_markdown\":\"지침\","
                        + "\"capabilities\":[\"document-edit\"]}",
                testRunBody(",\"target\":{\"type\":\"paragraph\",\"start_line\":1,\"end_line\":2}"),
                testRunBody(",\"target\":{\"type\":\"selection\",\"start_line\":0,\"end_line\":2}"))) {
            mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/test-run")
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        org.mockito.Mockito.verifyNoInteractions(skillTestRunService);
    }

    @Test
    void testRun_missingAiEndpointUsesDedicatedUnavailableCode() throws Exception {
        when(skillTestRunService.testRun(eq(WORKSPACE_ID), eq(USER_ID),
                any(fruition.core.skill.dto.SkillTestRunRequest.class), anyString()))
                .thenThrow(new PipelineSkillException("Skill 테스트 실행을 아직 사용할 수 없습니다.", 503, null,
                        "SKILL_TEST_RUN_UNAVAILABLE"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/test-run")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testRunBody("")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SKILL_TEST_RUN_UNAVAILABLE"));
    }

    @Test
    void testRun_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/test-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testRunBody("")))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(skillTestRunService);
    }

    private String testRunBody(String extra) {
        return "{\"name\":\"research-summary\",\"description\":\"연구 요약\",\"instructions_markdown\":\"지침\","
                + "\"capabilities\":[\"document-edit\"],\"document_id\":\"doc_1\"" + extra + "}";
    }

    @Test
    void author_unauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/author")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope_type\":\"personal\",\"instruction\":\"회의록 Skill을 만들어줘\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void author_acceptsWithoutProviderAndModel() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/author")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope_type\":\"personal\",\"instruction\":\"회의록 Skill을 만들어줘\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void author_pipelineValidationFailureUsesStandardErrorEnvelope() throws Exception {
        when(skillService.author(eq(WORKSPACE_ID), eq(USER_ID), any(SkillAuthoringRequest.class), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new PipelineSkillException(
                        "Skill 요청이 거부되었습니다.", 422, "{\"detail\":[{\"type\":\"missing\"}]}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/author")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SkillAuthoringRequest(
                                "personal", "meeting-notes", null,
                                "회의록 Skill을 만들어줘", "enhance", List.of()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("SKILL_REQUEST_REJECTED"))
                .andExpect(jsonPath("$.error.message").value("Skill 요청이 거부되었습니다."))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    void author_referenceRoundTripPreservesTooLargeEnvelope() throws Exception {
        HttpServer pipeline = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        pipeline.createContext("/skills/tasks", exchange -> {
            MockHttpServletResponse referenceResponse;
            try {
                referenceResponse = mockMvc.perform(post(
                                "/internal/agent/skill-authoring/references/read")
                                .header("X-Agent-Service-Token", "test-agent-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"workspace_id\":\"" + WORKSPACE_ID
                                        + "\",\"user_id\":\"" + USER_ID
                                        + "\",\"document_id\":\"doc_1\"}"))
                        .andReturn()
                        .getResponse();
            } catch (Exception exception) {
                throw new IOException(exception);
            }
            byte[] response = referenceResponse.getContentAsByteArray();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(referenceResponse.getStatus(), response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        pipeline.start();
        try {
            when(referenceService.read(WORKSPACE_ID, USER_ID, "doc_1"))
                    .thenThrow(new SkillReferenceDocumentTooLargeException());
            var requester = new PipelineSkillRequester(
                    new PipelineClientFactory("unused-internal-token"),
                    "http://localhost:" + pipeline.getAddress().getPort() + "/skills",
                    "agent-token",
                    5, mock(fruition.core.aitask.service.AiTaskCancellationService.class), objectMapper,
                    mock(fruition.core.usage.service.UsageChargeService.class)
            );
            var service = new SkillService(mock(WorkspaceAccessGuard.class), requester,
                    workspaceAiModelClient());
            when(skillService.author(eq(WORKSPACE_ID), eq(USER_ID), any(SkillAuthoringRequest.class), org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(invocation -> service.author(
                            WORKSPACE_ID,
                            USER_ID,
                            invocation.getArgument(2), invocation.getArgument(3)
                    ));

            mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/author")
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new SkillAuthoringRequest(
                                    "personal", "meeting-notes", null,
                                    "회의록 Skill을 만들어줘", "enhance", List.of("doc_1")))))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.error.code").value("REFERENCE_DOCUMENT_TOO_LARGE"))
                    .andExpect(jsonPath("$.error.message")
                            .value("EDITABLE 참조 문서는 30,000자 이하여야 합니다."));
        } finally {
            pipeline.stop(0);
        }
    }

    @Test
    void swaggerSchemas_exposeSkillProperties() throws Exception {
        var skill = ModelConverters.getInstance().read(schemaClass("SkillResponseSchema"))
                .get("SkillResponse");
        var version = ModelConverters.getInstance().read(schemaClass("SkillVersionResponseSchema"))
                .get("SkillVersionResponse");

        assertThat(skill.getProperties()).containsKeys("id", "workspace_id", "enabled_version", "latest_version");
        assertThat(version.getProperties()).containsKeys("capabilities", "allowed_tools", "targets", "result_mode", "requires_source",
                "lint_result", "status");
        assertThat(skill.getRequired()).contains("id", "enabled_version", "latest_version");
        assertThat(version.getRequired()).containsExactlyInAnyOrder("id", "version", "name", "description",
                "instructions_markdown", "capabilities", "allowed_tools", "lint_result", "status");
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) skill.getProperties().get("workspace_id")).getNullable()).isTrue();
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) skill.getProperties().get("enabled_version")).getNullable()).isTrue();
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) skill.getProperties().get("enabled_version")).getAllOf())
                .singleElement()
                .satisfies(schema -> assertThat(schema.get$ref()).isEqualTo("#/components/schemas/SkillVersionResponse"));
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) skill.getProperties().get("latest_version")).getNullable()).isTrue();
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) skill.getProperties().get("latest_version")).getAllOf())
                .singleElement()
                .satisfies(schema -> assertThat(schema.get$ref()).isEqualTo("#/components/schemas/SkillVersionResponse"));
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) version.getProperties().get("capabilities")).getItems().getType()).isEqualTo("string");
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) version.getProperties().get("allowed_tools")).getItems().getType()).isEqualTo("string");
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) version.getProperties().get("lint_result")).getType()).isEqualTo("object");
    }

    private Class<?> schemaClass(String name) throws ClassNotFoundException {
        return Class.forName(SkillController.class.getName() + "$" + name);
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }

    private WorkspaceAiModelClient workspaceAiModelClient() {
        var client = mock(WorkspaceAiModelClient.class);
        when(client.get(WORKSPACE_ID))
                .thenReturn(new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"));
        return client;
    }
}
