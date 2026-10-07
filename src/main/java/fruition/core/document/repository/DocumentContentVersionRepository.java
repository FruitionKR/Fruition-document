package fruition.core.document.repository;

import fruition.core.document.domain.DocumentContentVersion;
import fruition.core.document.domain.DocumentContentVersionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface DocumentContentVersionRepository
        extends JpaRepository<DocumentContentVersion, DocumentContentVersionId> {

    Optional<DocumentContentVersion> findTopByIdDocumentIdOrderByIdVersionDesc(String documentId);
    Optional<DocumentContentVersion> findFirstByIdDocumentIdAndRevisionOrderByIdVersionDesc(String documentId, long revision);

    @Query("SELECT v FROM DocumentContentVersion v WHERE v.id.documentId = :documentId AND v.revision IN :revisions ORDER BY v.id.version ASC")
    List<DocumentContentVersion> findByRevisions(@Param("documentId") String documentId, @Param("revisions") Collection<Long> revisions);

    @Modifying
    @Query(value = """
            INSERT INTO document_content_versions(document_id, version, revision, markdown, content_hash,
                created_by, created_at, record_type, restored_from_version)
            VALUES (:documentId, :version, :revision, :markdown, :hash, :actor, :at, :type, :restoredFrom)
            """, nativeQuery = true)
    int insertSnapshot(@Param("documentId") String documentId, @Param("version") long version,
        @Param("revision") long revision, @Param("markdown") String markdown, @Param("hash") String hash,
        @Param("actor") String actor, @Param("at") Instant at, @Param("type") String type,
        @Param("restoredFrom") Long restoredFrom);

    @Query(value = """
            SELECT d.id FROM documents d
            JOIN document_edit_states s ON s.document_id = d.id
            JOIN LATERAL (SELECT v.content_hash, v.created_at FROM document_content_versions v
                WHERE v.document_id = d.id ORDER BY v.version DESC LIMIT 1) last ON true
            WHERE d.deleted_at IS NULL AND d.document_role = 'EDITABLE'
              AND d.origin IS DISTINCT FROM 'chat_export'
              AND NOT (d.status = 'processing' AND COALESCE(d.pipeline_run_id, '') LIKE 'convert:%')
              AND NOT EXISTS (SELECT 1 FROM documents parent WHERE parent.id = d.source_document_id
                AND parent.status = 'processing' AND parent.pipeline_run_id LIKE 'convert:%')
              AND s.content_hash <> last.content_hash AND last.created_at <= :threshold
            ORDER BY last.created_at LIMIT 100
            """, nativeQuery = true)
    List<String> findDueDocumentIds(@Param("threshold") Instant threshold);

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM documents d WHERE d.id = :documentId AND d.deleted_at IS NULL
                AND d.document_role = 'EDITABLE' AND d.origin IS DISTINCT FROM 'chat_export'
                AND NOT (d.status = 'processing' AND COALESCE(d.pipeline_run_id, '') LIKE 'convert:%')
                AND NOT EXISTS (SELECT 1 FROM documents parent WHERE parent.id = d.source_document_id
                    AND parent.status = 'processing' AND parent.pipeline_run_id LIKE 'convert:%'))
            """, nativeQuery = true)
    boolean isHistoryEligible(@Param("documentId") String documentId);

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
                   v.createdBy AS createdBy, v.createdAt AS createdAt,
                   v.restoredFromVersion AS restoredFromVersion, v.revision AS revision, v.recordType AS recordType
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
        long getRevision();
        String getRecordType();
    }
}
