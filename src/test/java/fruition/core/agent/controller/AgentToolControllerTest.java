package fruition.core.agent.controller;

import fruition.core.agent.dto.AgentToolReadRequest;
import fruition.core.agent.security.AgentServiceTokenFilter;
import fruition.core.agent.service.AgentToolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentToolControllerTest {

    private AgentToolService toolService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        toolService = mock(AgentToolService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AgentToolController(toolService))
                .addFilters(new AgentServiceTokenFilter("test-agent-token"))
                .build();
    }

    @Test
    void read_forwardsAuthenticatedRequest() throws Exception {
        when(toolService.read(eq("list_root_items"), any(AgentToolReadRequest.class)))
                .thenReturn(Map.of("items", "ok"));

        mockMvc.perform(post("/internal/agent/tools/read/list_root_items")
                        .header("X-Agent-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"run_id":"run-1","workspace_id":"workspace-1",
                                 "user_id":"user-1","arguments":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").value("ok"));
    }

    @Test
    void readDocumentContentForwardsAuthenticatedRequest() throws Exception {
        when(toolService.read(eq("get_document_content"), any(AgentToolReadRequest.class)))
                .thenReturn(Map.of("id", "document-1", "markdown", "# current"));

        mockMvc.perform(post("/internal/agent/tools/read/get_document_content")
                        .header("X-Agent-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"run_id":"run-1","workspace_id":"workspace-1",
                                 "user_id":"user-1","arguments":{"document_id":"document-1"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("document-1"))
                .andExpect(jsonPath("$.markdown").value("# current"));

        verify(toolService).read(eq("get_document_content"), argThat(request ->
                "run-1".equals(request.runId())
                        && "workspace-1".equals(request.workspaceId())
                        && "user-1".equals(request.userId())
                        && "document-1".equals(request.arguments().get("document_id").asText())));
    }

    @Test
    void execute_rejectsInvalidTokenBeforeMalformedBodyBinding() throws Exception {
        mockMvc.perform(post("/internal/agent/tools/execute/move_document")
                        .header("X-Agent-Service-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(toolService);
    }

    @Test
    void read_rejectsMissingTokenBeforeMalformedBodyBinding() throws Exception {
        mockMvc.perform(post("/internal/agent/tools/read/list_root_items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(toolService);
    }

    // 필터가 디코딩 전 URI를 비교하면 한 글자만 인코딩해도 토큰 검사를 건너뛰고 핸들러에 닿았다(#8).
    @ParameterizedTest
    @ValueSource(strings = {
            "/internal/agent/%74ools/read/list_root_items",
            "/internal/agent/tools/%72ead/list_root_items",
            "/internal/%61gent/tools/read/list_root_items"})
    void read_rejectsPercentEncodedPathWithoutToken(String path) throws Exception {
        mockMvc.perform(post(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"run_id":"run-1","workspace_id":"workspace-1",
                                 "user_id":"user-1","arguments":{}}
                                """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(toolService);
    }

    /** 대조군: 인코딩한 경로도 같은 핸들러로 가므로 위 401은 필터가 막은 결과다. */
    @Test
    void read_forwardsPercentEncodedPathWithToken() throws Exception {
        when(toolService.read(eq("list_root_items"), any(AgentToolReadRequest.class)))
                .thenReturn(Map.of("items", "ok"));

        mockMvc.perform(post(URI.create("/internal/agent/%74ools/read/list_root_items"))
                        .header("X-Agent-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"run_id":"run-1","workspace_id":"workspace-1",
                                 "user_id":"user-1","arguments":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").value("ok"));
    }

    @Test
    void execute_rejectsMalformedBodyWithValidToken() throws Exception {
        mockMvc.perform(post("/internal/agent/tools/execute/move_document")
                        .header("X-Agent-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(toolService);
    }
}
