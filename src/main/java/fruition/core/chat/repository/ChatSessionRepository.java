package fruition.core.chat.repository;

import fruition.core.chat.domain.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

    List<ChatSession> findAllByWorkspaceIdOrderByLastMessageAtDesc(String workspaceId);

    List<ChatSession> findAllByWorkspaceIdAndUserIdOrderByLastMessageAtDesc(String workspaceId, String userId);

    Optional<ChatSession> findByIdAndWorkspaceIdAndUserId(String id, String workspaceId, String userId);

    long countByWorkspaceIdAndUserId(String workspaceId, String userId);
}
