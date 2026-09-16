package fruition.core.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fruition.core.CoreExceptionHandler;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.config.SecurityConfig;
import fruition.core.skill.dto.SkillAuthoringRequest;
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
                    5, mock(fruition.core.aitask.service.AiTaskCancellationService.class), objectMapper
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
        assertThat(version.getProperties()).containsKeys("capabilities", "allowed_tools", "lint_result", "status");
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
                .thenReturn(new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-5-nano"));
        return client;
    }
}
