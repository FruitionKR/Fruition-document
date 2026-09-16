package fruition.core.chat.service;

import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.dto.ChatSessionCreateRequest;
import fruition.core.chat.dto.ChatSessionListResponse;
import fruition.core.chat.dto.ChatSessionRenameRequest;
import fruition.core.chat.dto.ChatSessionResponse;
import fruition.core.chat.exception.ChatSessionLimitExceededException;
import fruition.core.chat.exception.ChatSessionNotFoundException;
import fruition.core.chat.repository.ChatSessionRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ChatSessionService {

    private static final int MAX_SESSIONS_PER_WORKSPACE_MEMBER = 10;

    private final ChatSessionRepository chatSessionRepository;
    private final WorkspaceAccessGuard workspaceAccessGuard;

    public ChatSessionService(ChatSessionRepository chatSessionRepository, WorkspaceAccessGuard workspaceAccessGuard) {
        this.chatSessionRepository = chatSessionRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
    }

    private void verifyWorkspaceOwnership(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
    }

    /** workspace 소유권과 세션 소속을 함께 검증한다. 호출부는 컨트롤러/QueryController에서 재사용한다. */
    public ChatSession verifyOwnedSession(String workspaceId, String userId, String sessionId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        return chatSessionRepository.findByIdAndWorkspaceIdAndUserId(sessionId, workspaceId, userId)
                .orElseThrow(() -> new ChatSessionNotFoundException(sessionId));
    }

    /**
     * pipeline이 턴마다 갱신해 세션에 쌓아 둔 누적 대화 요약.
     * 최근 메시지 몇 개로는 담기지 않는 앞쪽 맥락을 이어주므로 AI 요청마다 함께 보낸다.
     * 대화가 짧아 아직 요약이 없으면 null이다.
     */
    @Transactional(readOnly = true)
    public String contextSummary(String sessionId) {
        return chatSessionRepository.findById(sessionId)
                .map(ChatSession::getContextSummary)
                .filter(summary -> !summary.isBlank())
                .orElse(null);
    }

    @Transactional
    public ChatSessionResponse create(String workspaceId, String userId, ChatSessionCreateRequest request) {
        verifyWorkspaceOwnership(workspaceId, userId);

        if (chatSessionRepository.countByWorkspaceIdAndUserId(workspaceId, userId)
                >= MAX_SESSIONS_PER_WORKSPACE_MEMBER) {
            throw new ChatSessionLimitExceededException(workspaceId);
        }

        String sessionId = "session_" + UUID.randomUUID().toString().replace("-", "");
        ChatSession session = new ChatSession(sessionId, workspaceId, userId, request.title());
        chatSessionRepository.save(session);

        return toResponse(session);
    }

    public ChatSessionListResponse list(String workspaceId, String userId) {
        verifyWorkspaceOwnership(workspaceId, userId);

        return new ChatSessionListResponse(
                chatSessionRepository
                        .findAllByWorkspaceIdAndUserIdOrderByLastMessageAtDesc(workspaceId, userId)
                        .stream()
                        .map(this::toResponse)
                        .toList()
        );
    }

    @Transactional
    public ChatSessionResponse rename(String workspaceId, String userId, String sessionId,
                                      ChatSessionRenameRequest request) {
        ChatSession session = verifyOwnedSession(workspaceId, userId, sessionId);
        session.rename(request.title());
        return toResponse(session);
    }

    @Transactional
    public void delete(String workspaceId, String userId, String sessionId) {
        ChatSession session = verifyOwnedSession(workspaceId, userId, sessionId);
        // chat_messages/chat_message_references/chat_message_related_pages는
        // DB FK ON DELETE CASCADE로 함께 삭제된다 (ChatMessage.session 참고).
        chatSessionRepository.delete(session);
    }

    /** 워크스페이스 삭제 시 소속 세션을 함께 정리한다. DB에 workspace_id FK CASCADE가 없어 애플리케이션에서 직접 처리한다. */
    @Transactional
    public void deleteAllByWorkspaceId(String workspaceId) {
        chatSessionRepository.deleteAll(
                chatSessionRepository.findAllByWorkspaceIdOrderByLastMessageAtDesc(workspaceId));
    }

    private ChatSessionResponse toResponse(ChatSession session) {
        return new ChatSessionResponse(session.getId(), session.getTitle(), session.getCreatedAt(), session.getLastMessageAt());
    }
}
