package fruition.core.document.repository;

import fruition.core.document.domain.DocumentContentVersion;
import fruition.core.document.domain.DocumentContentVersionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
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

    /** 버전 복원으로 만든 버전에 복원 대상 버전을 기록한다. */
    @Modifying
    @Query("""
            UPDATE DocumentContentVersion v SET v.restoredFromVersion = :restoredFromVersion
            WHERE v.id.documentId = :documentId AND v.id.version = :version
              AND v.restoredFromVersion IS NULL
            """)
    int markRestoredFrom(
            @Param("documentId") String documentId,
            @Param("version") long version,
            @Param("restoredFromVersion") long restoredFromVersion
    );

    /** 이 버전을 저장한 경로를 기록한다. 재전송된 저장이 처음 기록을 바꾸지 않게 비어 있을 때만 쓴다. */
    @Modifying
    @Query("""
            UPDATE DocumentContentVersion v SET v.source = :source
            WHERE v.id.documentId = :documentId AND v.id.version = :version
              AND v.source IS NULL
            """)
    int markSource(
            @Param("documentId") String documentId,
            @Param("version") long version,
            @Param("source") String source
    );

    /** 문서마다 가장 최근 AI 저장 버전(agent·meeting_notes) 한 건. 목록에서 문서마다 조회하지 않도록 한 번에 읽는다. */
    @Query("""
            SELECT v.id.documentId AS documentId, v.id.version AS version, v.source AS source,
                   v.operationId AS operationId, v.createdAt AS createdAt
            FROM DocumentContentVersion v
            WHERE v.id.documentId IN :documentIds AND v.source IN ('agent', 'meeting_notes')
              AND v.id.version = (
                  SELECT MAX(l.id.version) FROM DocumentContentVersion l
                  WHERE l.id.documentId = v.id.documentId AND l.source IN ('agent', 'meeting_notes'))
            """)
    List<AiChange> findLatestAiChanges(@Param("documentIds") Collection<String> documentIds);

    /** 이력 목록. markdown 본문을 제외한 메타데이터만 최신 버전 순으로 반환한다. */
    @Query("""
            SELECT v.id.version AS version, v.contentHash AS contentHash,
                   v.createdBy AS createdBy, v.createdAt AS createdAt,
                   v.restoredFromVersion AS restoredFromVersion,
                   v.source AS source, v.operationId AS operationId
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
        Long getRestoredFromVersion();
        String getSource();
        String getOperationId();
    }

    interface AiChange {
        String getDocumentId();
        long getVersion();
        String getSource();
        String getOperationId();
        Instant getCreatedAt();
    }
}
