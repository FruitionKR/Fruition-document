package fruition.core.chat.repository;

import fruition.core.chat.domain.ChatMessageReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageReferenceRepository extends JpaRepository<ChatMessageReference, Long> {
    List<ChatMessageReference> findAllByChatMessage_IdIn(List<String> chatMessageIds);
}
