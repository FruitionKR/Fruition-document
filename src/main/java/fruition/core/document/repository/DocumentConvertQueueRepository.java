package fruition.core.document.repository;

import fruition.core.document.domain.DocumentConvertQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DocumentConvertQueueRepository extends JpaRepository<DocumentConvertQueue, Long> {

    /** 같은 문서의 batch 이벤트를 직렬화한다. 결과 기록·병합·다음 발행이 이 잠금 안에서 일어난다. */
    @Query(value = "SELECT * FROM document_convert_queue WHERE document_id = :documentId FOR UPDATE", nativeQuery = true)
    Optional<DocumentConvertQueue> findByDocumentIdForUpdate(@Param("documentId") String documentId);

    Optional<DocumentConvertQueue> findByDocumentId(String documentId);
}
