package fruition.core.chat.service;

import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.dto.ChatSessionCreateRequest;
import fruition.core.chat.dto.ChatSessionRenameRequest;
import fruition.core.chat.dto.ChatSessionResponse;
import fruition.core.chat.exception.ChatSessionLimitExceededException;
import fruition.core.chat.exception.ChatSessionNotFoundException;
import fruition.core.chat.repository.ChatSessionRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSessionServiceTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";
    private static final String SESSION_ID = "session_aaa11111";

    @Mock ChatSessionRepository chatSessionRepository;
    @Mock WorkspaceAccessGuard workspaceAccessGuard;

    ChatSessionService chatSessionService;

    @BeforeEach
    void setUp() {
        chatSessionService = new ChatSessionService(chatSessionRepository, workspaceAccessGuard);
    }

    private void stubOwnedWorkspace() {
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
    }

    @Test
    void create_withoutTitle_fillsDefaultTitle() {
        stubOwnedWorkspace();
        when(chatSessionRepository.countByWorkspaceIdAndUserId(WORKSPACE_ID, USER_ID)).thenReturn(0L);
        when(chatSessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ChatSessionResponse blank = chatSessionService.create(
                WORKSPACE_ID, USER_ID, new ChatSessionCreateRequest("   "));
        ChatSessionResponse omitted = chatSessionService.create(
                WORKSPACE_ID, USER_ID, new ChatSessionCreateRequest(null));

        // 제목이 비면 세션 ID가 파생 이름(위키화 문서명 등)으로 새어 나간다.
        assertThat(blank.title()).isEqualTo("새 채팅");
        assertThat(omitted.title()).isEqualTo("새 채팅");
        assertThat(omitted.title()).doesNotContain(omitted.id());
    }

    @Test
    void create_underLimit_createsSession() {
        stubOwnedWorkspace();
        when(chatSessionRepository.countByWorkspaceIdAndUserId(WORKSPACE_ID, USER_ID)).thenReturn(3L);
        when(chatSessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ChatSessionResponse response = chatSessionService.create(WORKSPACE_ID, USER_ID, new ChatSessionCreateRequest("제목"));

        assertThat(response.id()).startsWith("session_");
        assertThat(response.title()).isEqualTo("제목");
    }

    @Test
    void create_atLimit_throwsChatSessionLimitExceeded() {
        stubOwnedWorkspace();
        when(chatSessionRepository.countByWorkspaceIdAndUserId(WORKSPACE_ID, USER_ID)).thenReturn(10L);

        assertThatThrownBy(() -> chatSessionService.create(WORKSPACE_ID, USER_ID, new ChatSessionCreateRequest(null)))
                .isInstanceOf(ChatSessionLimitExceededException.class);
    }

    @Test
    void create_notOwnedWorkspace_throwsWorkspaceNotFound() {
        doThrow(new WorkspaceNotFoundException(WORKSPACE_ID))
                .when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);

        assertThatThrownBy(() -> chatSessionService.create(WORKSPACE_ID, USER_ID, new ChatSessionCreateRequest(null)))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void list_returnsSessionsOrderedByLastMessageAt() {
        stubOwnedWorkspace();
        when(chatSessionRepository.findAllByWorkspaceIdAndUserIdOrderByLastMessageAtDesc(WORKSPACE_ID, USER_ID))
                .thenReturn(List.of(new ChatSession("session_aaa11111", WORKSPACE_ID, USER_ID, "세션 A")));

        var response = chatSessionService.list(WORKSPACE_ID, USER_ID);

        assertThat(response.sessions()).hasSize(1);
        assertThat(response.sessions().get(0).title()).isEqualTo("세션 A");
    }

    @Test
    void rename_ownedSession_changesTitleAndKeepsLastMessageAt() {
        stubOwnedWorkspace();
        ChatSession session = new ChatSession(SESSION_ID, WORKSPACE_ID, USER_ID, "이전 제목");
        var lastMessageAt = session.getLastMessageAt();
        when(chatSessionRepository.findByIdAndWorkspaceIdAndUserId(SESSION_ID, WORKSPACE_ID, USER_ID))
                .thenReturn(Optional.of(session));

        ChatSessionResponse response = chatSessionService.rename(
                WORKSPACE_ID, USER_ID, SESSION_ID, new ChatSessionRenameRequest("새 제목"));

        assertThat(response.title()).isEqualTo("새 제목");
        // 제목만 바꿨는데 세션이 목록 맨 위로 올라오면 안 된다.
        assertThat(response.lastMessageAt()).isEqualTo(lastMessageAt);
    }

    @Test
    void rename_notOwnedSession_throwsChatSessionNotFound() {
        stubOwnedWorkspace();
        when(chatSessionRepository.findByIdAndWorkspaceIdAndUserId(SESSION_ID, WORKSPACE_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatSessionService.rename(
                WORKSPACE_ID, USER_ID, SESSION_ID, new ChatSessionRenameRequest("새 제목")))
                .isInstanceOf(ChatSessionNotFoundException.class);
    }

    @Test
    void verifyOwnedSession_ownedSession_returnsSession() {
        stubOwnedWorkspace();
        ChatSession session = new ChatSession("session_aaa11111", WORKSPACE_ID, USER_ID, null);
        when(chatSessionRepository.findByIdAndWorkspaceIdAndUserId(
                "session_aaa11111", WORKSPACE_ID, USER_ID))
                .thenReturn(Optional.of(session));

        ChatSession result = chatSessionService.verifyOwnedSession(WORKSPACE_ID, USER_ID, "session_aaa11111");

        assertThat(result).isSameAs(session);
    }

    @Test
    void verifyOwnedSession_unknownSession_throwsChatSessionNotFound() {
        stubOwnedWorkspace();
        when(chatSessionRepository.findByIdAndWorkspaceIdAndUserId(
                "session_unknown", WORKSPACE_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatSessionService.verifyOwnedSession(WORKSPACE_ID, USER_ID, "session_unknown"))
                .isInstanceOf(ChatSessionNotFoundException.class);
    }

    @Test
    void delete_ownedSession_removesSession() {
        stubOwnedWorkspace();
        ChatSession session = new ChatSession(SESSION_ID, WORKSPACE_ID, USER_ID, null);
        when(chatSessionRepository.findByIdAndWorkspaceIdAndUserId(
                SESSION_ID, WORKSPACE_ID, USER_ID)).thenReturn(Optional.of(session));

        chatSessionService.delete(WORKSPACE_ID, USER_ID, SESSION_ID);

        // chat_messages/references/related_pages 삭제는 DB FK ON DELETE CASCADE가 처리한다 (여기서는 검증 불가 — 통합 테스트 참고).
        verify(chatSessionRepository).delete(session);
    }

    @Test
    void deleteAllByWorkspaceId_removesEverySessionInWorkspace() {
        ChatSession session1 = new ChatSession("session_aaa11111", WORKSPACE_ID, USER_ID, null);
        ChatSession session2 = new ChatSession("session_bbb22222", WORKSPACE_ID, USER_ID, null);
        List<ChatSession> sessions = List.of(session1, session2);
        when(chatSessionRepository.findAllByWorkspaceIdOrderByLastMessageAtDesc(WORKSPACE_ID)).thenReturn(sessions);

        chatSessionService.deleteAllByWorkspaceId(WORKSPACE_ID);

        verify(chatSessionRepository).deleteAll(sessions);
    }
}
