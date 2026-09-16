package fruition.core.query.controller;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.query.domain.QueryRun;
import fruition.core.query.dto.QueryResponse;
import fruition.core.query.service.QueryEventBroker;
import fruition.core.query.service.QueryRunStore;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import fruition.core.config.SecurityConfig;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.CoreExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QueryRunController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class QueryRunControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_abc123";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean QueryRunStore queryRunStore;
    @MockBean QueryEventBroker queryEventBroker;
    @MockBean WorkspaceAccessGuard workspaceAccessGuard;

    private String bearerToken() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }

    private QueryRun pendingRun() {
        return QueryRun.pending("query_abc123", WORKSPACE_ID, "session_abc123", "질문",
                Instant.parse("2026-06-20T10:00:00Z"));
    }

    @Test
    void getRun_unknownRequestId_returns404() throws Exception {
        when(queryRunStore.find("query_unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/query/runs/query_unknown")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUERY_RUN_NOT_FOUND"));
    }

    @Test
    void getRun_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/query/runs/query_abc123"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getRun_notWorkspaceMember_returns404() throws Exception {
        when(queryRunStore.find("query_abc123")).thenReturn(Optional.of(pendingRun()));
        doThrow(new WorkspaceNotFoundException(WORKSPACE_ID))
                .when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);

        mockMvc.perform(get("/api/query/runs/query_abc123")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getRun_completedRun_returnsResult() throws Exception {
        QueryResponse result = new QueryResponse(null, null, null, null, null, null, true, true, 2, null);
        QueryRun run = pendingRun()
                .running()
                .completed(result, Instant.parse("2026-06-20T10:00:05Z"));
        when(queryRunStore.find("query_abc123")).thenReturn(Optional.of(run));

        mockMvc.perform(get("/api/query/runs/query_abc123")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value("query_abc123"))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.result.web_search_requested").value(true))
                .andExpect(jsonPath("$.result.web_search_executed").value(true))
                .andExpect(jsonPath("$.result.result_count").value(2));

        verify(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
    }

    @Test
    void subscribe_unknownRequestId_returns404() throws Exception {
        when(queryRunStore.find("query_unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/query/runs/query_unknown/events")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void subscribe_existingRun_startsAsyncSseResponse() throws Exception {
        when(queryRunStore.find("query_abc123")).thenReturn(Optional.of(pendingRun()));
        when(queryEventBroker.subscribe("query_abc123")).thenReturn(new SseEmitter(0L));

        mockMvc.perform(get("/api/query/runs/query_abc123/events")
                        .header("Authorization", bearerToken()))
                .andExpect(request().asyncStarted());

        verify(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
    }

    /**
     * SSE는 완료 시 async 디스패치로 돌아온다. Spring Security 6은 기본적으로 모든 dispatch type을
     * 인가 필터에 태우는데, 그 시점에는 SecurityContext가 이미 비워져 있어 Access Denied가 난다.
     * 응답은 이미 커밋된 뒤라 오류를 내보내지도 못하고, 프론트는 완료 이벤트를 마무리하지 못한다.
     */
    @Test
    void subscribe_asyncDispatchAfterCompletion_isNotDeniedBySecurity() throws Exception {
        SseEmitter emitter = new SseEmitter(0L);
        when(queryRunStore.find("query_abc123")).thenReturn(Optional.of(pendingRun()));
        when(queryEventBroker.subscribe("query_abc123")).thenReturn(emitter);

        var result = mockMvc.perform(get("/api/query/runs/query_abc123/events")
                        .header("Authorization", bearerToken()))
                .andExpect(request().asyncStarted())
                .andReturn();
        emitter.complete();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk());
    }

}
