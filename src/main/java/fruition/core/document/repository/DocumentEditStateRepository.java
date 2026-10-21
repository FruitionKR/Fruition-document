package fruition.core.document.repository;

import fruition.core.document.domain.DocumentEditState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface DocumentEditStateRepository extends JpaRepository<DocumentEditState, String> {

    /** 주어진 문서 중 편집 상태가 있는 문서 ID. 본문을 읽지 않고 존재 여부만 본다. */
    @Query("SELECT s.documentId FROM DocumentEditState s WHERE s.documentId IN :documentIds")
    List<String> findExistingDocumentIds(@Param("documentIds") Collection<String> documentIds);

    @Modifying
    @Query(value = """
            INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at)
            VALUES (:documentId, :markdown, :contentHash, :revision, :createdAt, :updatedAt)
            ON CONFLICT (document_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("documentId") String documentId,
            @Param("markdown") String markdown,
            @Param("contentHash") String contentHash,
            @Param("revision") long revision,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt
    );
}
