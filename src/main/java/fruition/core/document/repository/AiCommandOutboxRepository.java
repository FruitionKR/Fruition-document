package fruition.core.document.repository;

import fruition.core.document.domain.AiCommandOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AiCommandOutboxRepository extends JpaRepository<AiCommandOutbox, String> {

    @Query(value = "SELECT * FROM ai_command_outbox ORDER BY created_at, id LIMIT 100 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<AiCommandOutbox> lockPendingBatch();
}
