package fruition.core.chat.repository;

import fruition.core.chat.domain.ChatMessageRelatedPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRelatedPageRepository extends JpaRepository<ChatMessageRelatedPage, Long> {
    List<ChatMessageRelatedPage> findAllByChatMessage_IdIn(List<String> chatMessageIds);
}
