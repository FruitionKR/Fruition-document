package fruition.core.document.repository;

import fruition.core.document.domain.DocumentConvertQueue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentConvertQueueRepository extends JpaRepository<DocumentConvertQueue, Long> {

    @org.springframework.data.jpa.repository.Query(value = "SELECT * FROM document_convert_queue "
            + "WHERE status = :status AND next_attempt_at <= now() ORDER BY created_at "
            + "LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<DocumentConvertQueue> findFirstByStatusOrderByCreatedAtAsc(
            @org.springframework.data.repository.query.Param("status") String status);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "UPDATE document_convert_queue SET status = 'pending' "
            + "WHERE status = 'processing' AND updated_at < now() - interval '10 minutes'", nativeQuery = true)
    int recoverStale();

    List<DocumentConvertQueue> findAllByStatus(String status);
}
