package fruition.core.agent.controller;

import fruition.core.agent.dto.AgentToolReadRequest;
import fruition.core.agent.security.AgentServiceTokenFilter;
import fruition.core.agent.service.AgentToolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
