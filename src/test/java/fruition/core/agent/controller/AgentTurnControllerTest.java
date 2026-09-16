package fruition.core.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.agent.dto.AgentTurnRequest;
import fruition.core.agent.dto.AgentTurnResponse;
import fruition.core.agent.dto.AgentRunApproveRequest;
import fruition.core.agent.dto.AgentRunReviseRequest;
import fruition.core.agent.exception.AgentRunNotFoundException;
import fruition.core.agent.exception.InvalidAgentTurnRequestException;
import fruition.core.agent.exception.PipelineAgentException;
import fruition.core.agent.service.AgentTurnService;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import fruition.core.config.SecurityConfig;
import fruition.core.CoreExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@WebMvcTest(AgentTurnController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class AgentTurnControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean AgentTurnService agentTurnService;
    @MockBean fruition.core.query.service.QueryEventBroker runEventBroker;

    @Test
    void turn_authenticatedRequestReturnsPipelineResult() throws Exception {
        AgentTurnRequest request = request();
        AgentTurnResponse response = new AgentTurnResponse(
                "doc_1",
                4L,
                "agent_request_1",
                "op_apply_1",
                "queued",
                null,
                null
        );
        when(agentTurnService.turn(eq(WORKSPACE_ID), eq(USER_ID), any(AgentTurnRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/agent/turn")
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.documentId").value("doc_1"))
                .andExpect(jsonPath("$.baseVersion").value(4))
                .andExpect(jsonPath("$.status").value("queued"));

        ArgumentCaptor<AgentTurnRequest> captured = ArgumentCaptor.forClass(AgentTurnRequest.class);
        verify(agentTurnService).turn(eq(WORKSPACE_ID), eq(USER_ID), captured.capture());
        org.assertj.core.api.Assertions.assertThat(captured.getValue().skillMode()).isEqualTo("auto");
        org.assertj.core.api.Assertions.assertThat(captured.getValue().skillId()).isNull();
    }

    @Test
    void turn_explicitSkillFieldsReachService() throws Exception {
        when(agentTurnService.turn(eq(WORKSPACE_ID), eq(USER_ID), any(AgentTurnRequest.class)))
                .thenReturn(new AgentTurnResponse("doc_1", 4L, "agent_request_1", "op_apply_1", "queued", null, null));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/agent/turn")
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "session_id":"session_1","documentId":"doc_1","baseVersion":4,"message":"문서를 점검해줘",
                                  "skill_mode":"explicit","skill_id":"skill-1",
                                  "editorSnapshot":{"markdown":"# 제목\\n본문","target":{"type":"whole_document","startLine":1,"endLine":2}}
                                }
                                """))
                .andExpect(status().isAccepted());

        ArgumentCaptor<AgentTurnRequest> captured = ArgumentCaptor.forClass(AgentTurnRequest.class);
        verify(agentTurnService).turn(eq(WORKSPACE_ID), eq(USER_ID), captured.capture());
        org.assertj.core.api.Assertions.assertThat(captured.getValue().skillMode()).isEqualTo("explicit");
        org.assertj.core.api.Assertions.assertThat(captured.getValue().skillId()).isEqualTo("skill-1");
    }

    @Test
    void turn_rejectsInvalidSkillSelectionCombination() throws Exception {
        String token = "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
        for (String skillFields : new String[]{
                "\"skill_mode\":\"explicit\"",
                "\"skill_mode\":\"off\",\"skill_id\":\"skill-1\"",
                "\"skill_mode\":\"unknown\"",
                "\"skill_mode\":\"auto\",\"skill_id\":\"skill-1\"",
                "\"skill_mode\":\"auto\",\"skill_id\":\"   \"",
                "\"skill_mode\":\"auto\",\"skill_id\":\"" + "x".repeat(129) + "\""
        }) {
            mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/agent/turn")
                            .header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{" +
                                    "\"documentId\":\"doc_1\",\"baseVersion\":4,\"message\":\"문서를 점검해줘\"," +
                                    skillFields + ",\"editorSnapshot\":{\"markdown\":\"# 제목\\n본문\"}}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void turn_unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/agent/turn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTurn_unknownRunReturns404() throws Exception {
        String runId = "agent_0123456789abcdef0123456789abcdef";
        when(agentTurnService.get(WORKSPACE_ID, USER_ID, runId))
                .thenThrow(new AgentRunNotFoundException(runId));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/agent/turn/" + runId)
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("AGENT_RUN_NOT_FOUND"));
    }

    @Test
    void getTurn_malformedRunIdReturns400() throws Exception {
        when(agentTurnService.get(WORKSPACE_ID, USER_ID, "agent_bad"))
                .thenThrow(new InvalidAgentTurnRequestException("Agent run ID 형식이 올바르지 않습니다."));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/agent/turn/agent_bad")
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void approve_providerConflictReturnsSafeErrorEnvelope() throws Exception {
        String runId = "run-autonomous";
        when(agentTurnService.approve(eq(WORKSPACE_ID), eq(USER_ID), eq(runId), any(AgentRunApproveRequest.class)))
                .thenThrow(new PipelineAgentException("AgentRun 요청이 거부되었습니다.", 409,
                        "{\"detail\":\"stale plan\"}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/agent/runs/" + runId + "/approve")
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan_version\":1,\"operation_hash\":\"" + "a".repeat(64) + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AGENT_REQUEST_REJECTED"))
                .andExpect(jsonPath("$.error.message").value("AgentRun 요청이 거부되었습니다."))
                .andExpect(content().string(not(containsString("stale plan"))));
    }

    @Test
    void getRun_providerFailureReturnsUnavailableErrorEnvelope() throws Exception {
        String runId = "run-autonomous";
        when(agentTurnService.getRun(WORKSPACE_ID, USER_ID, runId))
                .thenThrow(new PipelineAgentException("AgentRun 요청 시간이 초과되었습니다.", 503,
                        "{\"detail\":\"provider secret\"}"));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/agent/runs/" + runId)
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("AGENT_PIPELINE_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("provider secret"))));
    }

    @Test
    void autonomousRunRoutesUseAuthenticatedWorkspaceScope() throws Exception {
        String runId = "run-autonomous";
        JsonNode response = objectMapper.readTree("{\"id\":\"run-autonomous\"}");
        when(agentTurnService.getRun(WORKSPACE_ID, USER_ID, runId)).thenReturn(response);
        when(agentTurnService.approve(eq(WORKSPACE_ID), eq(USER_ID), eq(runId), any(AgentRunApproveRequest.class)))
                .thenReturn(response);
        when(agentTurnService.reject(WORKSPACE_ID, USER_ID, runId)).thenReturn(response);
        when(agentTurnService.cancel(WORKSPACE_ID, USER_ID, runId)).thenReturn(response);
        when(agentTurnService.revise(eq(WORKSPACE_ID), eq(USER_ID), eq(runId), any(AgentRunReviseRequest.class)))
                .thenReturn(response);
        String token = "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/agent/runs/" + runId)
                        .header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/agent/runs/" + runId + "/approve")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan_version\":1,\"operation_hash\":\"" + "a".repeat(64) + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/agent/runs/" + runId + "/reject")
                        .header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/agent/runs/" + runId + "/cancel")
                        .header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/agent/runs/" + runId + "/revise")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"계획을 다시 세워줘\"}"))
                .andExpect(status().isOk());

        verify(agentTurnService).getRun(WORKSPACE_ID, USER_ID, runId);
        verify(agentTurnService).reject(WORKSPACE_ID, USER_ID, runId);
        verify(agentTurnService).cancel(WORKSPACE_ID, USER_ID, runId);
    }

    @Test
    void autonomousActionBodiesRejectClientActorScope() throws Exception {
        String token = "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
        for (String body : new String[]{
                "{\"workspace_id\":\"ws-evil\",\"plan_version\":1,\"operation_hash\":\"" + "a".repeat(64) + "\"}",
                "{\"user_id\":\"user-evil\",\"plan_version\":1,\"operation_hash\":\"" + "a".repeat(64) + "\"}",
                "{\"plan_version\":1,\"operation_hash\":\"" + "a".repeat(64) + "\",\"unexpected\":true}",
                "{\"plan_version\":\"one\",\"operation_hash\":\"" + "a".repeat(64) + "\"}",
                "{\"plan_version\":1,\"operation_hash\":123}",
                "{\"instruction\":\"계획을 다시 세워줘\",\"workspace_id\":\"ws-evil\"}",
                "{\"instruction\":\"계획을 다시 세워줘\",\"user_id\":\"user-evil\"}",
                "{\"instruction\":\"계획을 다시 세워줘\",\"unexpected\":true}",
                "{\"instruction\":{\"text\":\"계획\"}}"
        }) {
            String action = body.contains("plan_version") ? "approve" : "revise";
            mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/agent/runs/run-1/" + action)
                            .header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.error.message").value("요청 형식이 올바르지 않습니다."));
        }
    }

    private AgentTurnRequest request() {
        return new AgentTurnRequest("session_1", 
                "doc_1",
                4L,
                "문서를 점검해줘",
                null,
                null,
                null,
                new AgentTurnRequest.EditorSnapshot(
                        "# 제목\n본문",
                        new AgentTurnRequest.Target("whole_document", 1, 2))
        );
    }

    /**
     * 진행 이벤트는 Redis 버퍼에 이미 쌓여 있어 pipeline 없이도 흘려보낼 수 있다. 구독 입구가
     * 결과 조회(get)를 부르면 pipeline이 잠깐 멈춘 동안 소유권과 무관하게 구독까지 막힌다.
     */
    @Test
    void subscribeTurnEvents_checksAccessWithoutCallingPipeline() throws Exception {
        when(runEventBroker.subscribe("agent_request_1"))
                .thenReturn(new org.springframework.web.servlet.mvc.method.annotation.SseEmitter());

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/agent/turn/agent_request_1/events")
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com")))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(agentTurnService).verifyRunAccess(WORKSPACE_ID, USER_ID, "agent_request_1");
        org.mockito.Mockito.verify(agentTurnService, org.mockito.Mockito.never())
                .get(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }
}
