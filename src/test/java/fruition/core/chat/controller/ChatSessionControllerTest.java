package fruition.core.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.dto.ChatSessionCreateRequest;
import fruition.core.chat.dto.ChatSessionListResponse;
import fruition.core.chat.dto.ChatSessionRenameRequest;
import fruition.core.chat.dto.ChatSessionResponse;
import fruition.core.chat.exception.ChatSessionLimitExceededException;
import fruition.core.chat.exception.ChatSessionNotFoundException;
import fruition.core.chat.repository.ChatMessageReferenceRepository;
import fruition.core.chat.repository.ChatMessageRelatedPageRepository;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.repository.ChatPartialWikiRepository;
import fruition.core.chat.service.ChatSessionService;
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

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatSessionController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class ChatSessionControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";
    private static final String SESSION_ID = "session_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean ChatSessionService chatSessionService;
    @MockBean ChatMessageRepository chatMessageRepository;
    @MockBean ChatMessageReferenceRepository referenceRepository;
    @MockBean ChatMessageRelatedPageRepository relatedPageRepository;
    @MockBean ChatPartialWikiRepository chatPartialWikiRepository;

    private String bearerToken() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }

    @Test
    void create_authenticated_returns201() throws Exception {
        when(chatSessionService.create(eq(WORKSPACE_ID), eq(USER_ID), any())).thenReturn(
                new ChatSessionResponse(SESSION_ID, "제목", Instant.now(), Instant.now()));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/chat/sessions")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatSessionCreateRequest("제목"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(SESSION_ID));
    }

    @Test
    void create_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatSessionCreateRequest("제목"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_limitExceeded_returns409() throws Exception {
        when(chatSessionService.create(eq(WORKSPACE_ID), eq(USER_ID), any()))
                .thenThrow(new ChatSessionLimitExceededException(WORKSPACE_ID));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/chat/sessions")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatSessionCreateRequest(null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHAT_SESSION_LIMIT_EXCEEDED"));
    }

    @Test
    void list_authenticated_returnsSessions() throws Exception {
        when(chatSessionService.list(WORKSPACE_ID, USER_ID)).thenReturn(
                new ChatSessionListResponse(List.of(new ChatSessionResponse(SESSION_ID, "제목", Instant.now(), Instant.now()))));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/chat/sessions")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].id").value(SESSION_ID));
    }

    @Test
    void rename_ownedSession_returns200() throws Exception {
        when(chatSessionService.rename(eq(WORKSPACE_ID), eq(USER_ID), eq(SESSION_ID), any())).thenReturn(
                new ChatSessionResponse(SESSION_ID, "새 제목", Instant.now(), Instant.now()));

        mockMvc.perform(patch("/api/workspaces/" + WORKSPACE_ID + "/chat/sessions/" + SESSION_ID)
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatSessionRenameRequest("새 제목"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("새 제목"));
    }

    @Test
    void rename_blankTitle_returns400() throws Exception {
        mockMvc.perform(patch("/api/workspaces/" + WORKSPACE_ID + "/chat/sessions/" + SESSION_ID)
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatSessionRenameRequest("   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rename_tooLongTitle_returns400() throws Exception {
        mockMvc.perform(patch("/api/workspaces/" + WORKSPACE_ID + "/chat/sessions/" + SESSION_ID)
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatSessionRenameRequest("가".repeat(256)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rename_unknownSession_returns404() throws Exception {
        when(chatSessionService.rename(eq(WORKSPACE_ID), eq(USER_ID), eq(SESSION_ID), any()))
                .thenThrow(new ChatSessionNotFoundException(SESSION_ID));

        mockMvc.perform(patch("/api/workspaces/" + WORKSPACE_ID + "/chat/sessions/" + SESSION_ID)
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatSessionRenameRequest("새 제목"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHAT_SESSION_NOT_FOUND"));
    }

    @Test
    void rename_unauthenticated_returns401() throws Exception {
        mockMvc.perform(patch("/api/workspaces/" + WORKSPACE_ID + "/chat/sessions/" + SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatSessionRenameRequest("새 제목"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void delete_ownedSession_returns204() throws Exception {
        mockMvc.perform(delete("/api/workspaces/" + WORKSPACE_ID + "/chat/sessions/" + SESSION_ID)
                        .header("Authorization", bearerToken()))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_unknownSession_returns404() throws Exception {
        org.mockito.Mockito.doThrow(new ChatSessionNotFoundException(SESSION_ID))
                .when(chatSessionService).delete(WORKSPACE_ID, USER_ID, SESSION_ID);

        mockMvc.perform(delete("/api/workspaces/" + WORKSPACE_ID + "/chat/sessions/" + SESSION_ID)
                        .header("Authorization", bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHAT_SESSION_NOT_FOUND"));
    }

    @Test
    void getMessages_ownedSession_returnsMessagesInOrder() throws Exception {
        ChatSession session = new ChatSession(SESSION_ID, WORKSPACE_ID, USER_ID, null);
        when(chatSessionService.verifyOwnedSession(WORKSPACE_ID, USER_ID, SESSION_ID)).thenReturn(session);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION_ID)).thenReturn(List.of(
                new ChatMessage("chat_user_1", session, "pair_1", "user", "질문", "completed", Instant.now(), null),
                new ChatMessage("chat_assistant_1", session, "pair_1", "assistant", "답변", "completed", Instant.now(), null)
        ));
        when(referenceRepository.findAllByChatMessage_IdIn(any())).thenReturn(List.of());
        when(relatedPageRepository.findAllByChatMessage_IdIn(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/chat/sessions/" + SESSION_ID + "/messages")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].id").value("chat_user_1"))
                .andExpect(jsonPath("$.messages[1].id").value("chat_assistant_1"));
    }

    @Test
    void getMessages_notOwnedSession_returns404() throws Exception {
        when(chatSessionService.verifyOwnedSession(WORKSPACE_ID, USER_ID, SESSION_ID))
                .thenThrow(new ChatSessionNotFoundException(SESSION_ID));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/chat/sessions/" + SESSION_ID + "/messages")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHAT_SESSION_NOT_FOUND"));
    }
}
