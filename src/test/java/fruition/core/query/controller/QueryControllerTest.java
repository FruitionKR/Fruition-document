package fruition.core.query.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.exception.ChatSessionNotFoundException;
import fruition.core.chat.service.ChatSessionService;
import fruition.core.query.domain.QueryRun;
import fruition.core.query.dto.QueryRequest;
import fruition.core.query.dto.QueryResponse;
import fruition.core.query.service.QueryRunService;
import fruition.core.query.service.QueryService;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import fruition.shared.ai.AiModelCatalog;
import fruition.core.config.SecurityConfig;
import fruition.core.CoreExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QueryController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class QueryControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";
    private static final String SESSION_ID = "session_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean QueryService queryService;
    @MockBean QueryRunService queryRunService;
    @MockBean ChatSessionService chatSessionService;
    @MockBean AiModelCatalog aiModelCatalog;

    private String bearerToken() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }

    private String basePath() {
        return "/api/workspaces/" + WORKSPACE_ID + "/chat/sessions/" + SESSION_ID;
    }

    @Test
    void query_ownedSession_returns200() throws Exception {
        when(aiModelCatalog.resolve(null, null))
                .thenReturn(new AiModelCatalog.AiModel("openai", "gpt-5-nano", "GPT-5 nano"));
        when(chatSessionService.verifyOwnedSession(WORKSPACE_ID, USER_ID, SESSION_ID))
                .thenReturn(new ChatSession(SESSION_ID, WORKSPACE_ID, USER_ID, null));
        QueryResponse response = new QueryResponse(
                new QueryResponse.MessageSummary("chat_user_1", "user", "질문", "completed", Instant.now()),
                new QueryResponse.MessageSummary("chat_assistant_1", "assistant", "답변", "completed", Instant.now()),
                null, null, null, null, false, false, 0, null);
        QueryRun run = QueryRun.pending("query_test", WORKSPACE_ID, SESSION_ID, "질문", Instant.now());
        when(queryRunService.start(WORKSPACE_ID, USER_ID, SESSION_ID, "질문", "openai", "gpt-5-nano", false, null)).thenReturn(run);
        when(queryRunService.awaitResult(run, USER_ID)).thenReturn(response);

        mockMvc.perform(post(basePath() + "/query")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QueryRequest("질문", null, null, false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistant_message.content").value("답변"));
    }

    @Test
    void query_webSearchAllowed_passesRequestSetting() throws Exception {
        when(aiModelCatalog.resolve(null, null))
                .thenReturn(new AiModelCatalog.AiModel("openai", "gpt-5-nano", "GPT-5 nano"));
        when(chatSessionService.verifyOwnedSession(WORKSPACE_ID, USER_ID, SESSION_ID))
                .thenReturn(new ChatSession(SESSION_ID, WORKSPACE_ID, USER_ID, null));
        QueryResponse response = new QueryResponse(
                new QueryResponse.MessageSummary("chat_user_1", "user", "질문", "completed", Instant.now()),
                new QueryResponse.MessageSummary("chat_assistant_1", "assistant", "답변", "completed", Instant.now()),
                null, null, null, null, true, true, 1, null);
        QueryRun run = QueryRun.pending("query_test", WORKSPACE_ID, SESSION_ID, "질문", Instant.now());
        when(queryRunService.start(WORKSPACE_ID, USER_ID, SESSION_ID, "질문", "openai", "gpt-5-nano", true, null)).thenReturn(run);
        when(queryRunService.awaitResult(run, USER_ID)).thenReturn(response);

        mockMvc.perform(post(basePath() + "/query")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QueryRequest("질문", null, null, true))))
                .andExpect(status().isOk());

        verify(queryRunService).start(WORKSPACE_ID, USER_ID, SESSION_ID, "질문", "openai", "gpt-5-nano", true, null);
    }

    @Test
    void query_missingWebSearchSetting_returns400() throws Exception {
        for (String path : new String[]{"/query", "/query/runs"}) {
            mockMvc.perform(post(basePath() + path)
                            .header("Authorization", bearerToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"질문\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.error.details[0].field").value("allowWebSearch"));
        }
    }

    @Test
    void query_invalidWebSearchValues_returns400InvalidRequest() throws Exception {
        for (String path : new String[]{"/query", "/query/runs"}) {
            for (String value : new String[]{"null", "\"true\"", "1", "1.5", "{}", "[]"}) {
                mockMvc.perform(post(basePath() + path)
                                .header("Authorization", bearerToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"질문\",\"allow_web_search\":" + value + "}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
            }
        }

        verifyNoInteractions(queryService, queryRunService, chatSessionService, aiModelCatalog);
    }

    @Test
    void query_notOwnedSession_returns404() throws Exception {
        when(chatSessionService.verifyOwnedSession(WORKSPACE_ID, USER_ID, SESSION_ID))
                .thenThrow(new ChatSessionNotFoundException(SESSION_ID));

        mockMvc.perform(post(basePath() + "/query")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QueryRequest("질문", null, null, false))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHAT_SESSION_NOT_FOUND"));
    }

    @Test
    void query_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post(basePath() + "/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QueryRequest("질문", null, null, false))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createRun_ownedSession_returns202() throws Exception {
        when(aiModelCatalog.resolve(null, null))
                .thenReturn(new AiModelCatalog.AiModel("openai", "gpt-5-nano", "GPT-5 nano"));
        when(chatSessionService.verifyOwnedSession(WORKSPACE_ID, USER_ID, SESSION_ID))
                .thenReturn(new ChatSession(SESSION_ID, WORKSPACE_ID, USER_ID, null));
        QueryRun run = QueryRun.pending(
                "query_abc123", WORKSPACE_ID, SESSION_ID, "openai", "gpt-5-nano", false, "질문", Instant.now());
        when(queryRunService.start(WORKSPACE_ID, USER_ID, SESSION_ID, "질문",
                "openai", "gpt-5-nano", false)).thenReturn(run);

        mockMvc.perform(post(basePath() + "/query/runs")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QueryRequest("질문", null, null, false))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.request_id").value("query_abc123"))
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void createRun_webSearchAllowed_passesRequestSetting() throws Exception {
        when(aiModelCatalog.resolve(null, null))
                .thenReturn(new AiModelCatalog.AiModel("openai", "gpt-5-nano", "GPT-5 nano"));
        when(chatSessionService.verifyOwnedSession(WORKSPACE_ID, USER_ID, SESSION_ID))
                .thenReturn(new ChatSession(SESSION_ID, WORKSPACE_ID, USER_ID, null));
        QueryRun run = QueryRun.pending(
                "query_abc123", WORKSPACE_ID, SESSION_ID, "openai", "gpt-5-nano", true, "질문", Instant.now());
        when(queryRunService.start(WORKSPACE_ID, USER_ID, SESSION_ID, "질문",
                "openai", "gpt-5-nano", true)).thenReturn(run);

        mockMvc.perform(post(basePath() + "/query/runs")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QueryRequest("질문", null, null, true))))
                .andExpect(status().isAccepted());

        verify(queryRunService).start(
                WORKSPACE_ID, USER_ID, SESSION_ID, "질문", "openai", "gpt-5-nano", true);
    }
}
