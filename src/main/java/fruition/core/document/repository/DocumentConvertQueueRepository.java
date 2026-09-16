package fruition.core.document.repository;

import fruition.core.document.domain.DocumentConvertQueue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentConvertQueueRepository extends JpaRepository<DocumentConvertQueue, Long> {

    Optional<DocumentConvertQueue> findFirstByStatusOrderByCreatedAtAsc(String status);

    List<DocumentConvertQueue> findAllByStatus(String status);
}
