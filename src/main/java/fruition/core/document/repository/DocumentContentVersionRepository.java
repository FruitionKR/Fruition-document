package fruition.core.document.repository;

import fruition.core.document.domain.DocumentContentVersion;
import fruition.core.document.domain.DocumentContentVersionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface DocumentContentVersionRepository
        extends JpaRepository<DocumentContentVersion, DocumentContentVersionId> {

    /** 콘텐츠 스냅샷을 남긴다. 같은 (document_id, version)이 이미 있으면 무시한다(초기본 중복 기록 방지). */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO document_content_versions(document_id, version, markdown, content_hash, created_by, created_at)
            VALUES (:documentId, :version, :markdown, :contentHash, :createdBy, :createdAt)
            ON CONFLICT (document_id, version) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("documentId") String documentId,
            @Param("version") long version,
            @Param("markdown") String markdown,
            @Param("contentHash") String contentHash,
            @Param("createdBy") String createdBy,
            @Param("createdAt") Instant createdAt
    );

    /** 이 버전을 만든 AI 작업을 연결한다. 수동 편집이면 호출하지 않는다. */
    @Modifying
    @Query("""
            UPDATE DocumentContentVersion v SET v.operationId = :operationId
            WHERE v.id.documentId = :documentId AND v.id.version = :version
              AND v.operationId IS NULL
            """)
    int linkOperation(
            @Param("documentId") String documentId,
            @Param("version") long version,
            @Param("operationId") String operationId
    );

    /** 이력 목록. markdown 본문을 제외한 메타데이터만 최신 버전 순으로 반환한다. */
    @Query("""
            SELECT v.id.version AS version, v.contentHash AS contentHash,
                   v.createdBy AS createdBy, v.createdAt AS createdAt
            FROM DocumentContentVersion v
            WHERE v.id.documentId = :documentId
            ORDER BY v.id.version DESC
            """)
    List<Summary> findSummaries(@Param("documentId") String documentId);

    interface Summary {
        long getVersion();
        String getContentHash();
        String getCreatedBy();
        Instant getCreatedAt();
    }
}
