package fruition.core.document.repository;

import fruition.core.document.domain.DocumentEditState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface DocumentEditStateRepository extends JpaRepository<DocumentEditState, String> {

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
