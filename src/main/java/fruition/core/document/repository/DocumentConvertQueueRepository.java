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

    @Query(value = "SELECT current_setting('lock_timeout')", nativeQuery = true)
    String currentLockTimeout();

    /** 이 트랜잭션 안에서만 잠금 대기 한도를 바꾼다(SET LOCAL과 같다). */
    @Query(value = "SELECT set_config('lock_timeout', :value, true)", nativeQuery = true)
    String setLocalLockTimeout(@Param("value") String value);
}
