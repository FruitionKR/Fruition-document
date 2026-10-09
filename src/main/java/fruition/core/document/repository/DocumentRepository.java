package fruition.core.document.repository;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.domain.DocumentRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, String> {
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspace AND d.sourceDocumentId = :parent "
            + "AND d.origin = 'convert_part' AND d.deletedAt IS NULL ORDER BY d.sortOrder, d.id")
    List<Document> findConvertedParts(@Param("workspace") String workspace, @Param("parent") String parent);


    /** chat export 중복 판별: 일반 문서는 같은 content를 허용한다. */
    Optional<Document> findByWorkspaceIdAndOriginAndContentHashAndSelectionModeAndDeletedAtIsNull(
            String workspaceId, String origin, String contentHash, String selectionMode);

    /** DB unique index로 chat export를 한 번만 예약한다. 0이면 다른 트랜잭션이 먼저 예약했다. */
    @Modifying
    @Query(value = """
            INSERT INTO documents(
                id, workspace_id, user_id, filename, display_name, normalized_filename,
                mime_type, byte_size, status, source_uri, content_hash, current_content_hash,
                current_version, document_role, sort_order, uploaded_at, updated_at,
                origin, selection_mode
            ) VALUES (
                :id, :workspaceId, :userId, :filename, :displayName, :normalizedFilename,
                :mimeType, :byteSize, :status, :sourceUri, :contentHash, :currentContentHash,
                :currentVersion, :documentRole, :sortOrder, :uploadedAt, :updatedAt,
                'chat_export', :selectionMode
            )
            ON CONFLICT (workspace_id, content_hash, selection_mode)
                WHERE origin = 'chat_export' AND deleted_at IS NULL
            DO NOTHING
            """, nativeQuery = true)
    int reserveChatExport(
            @Param("id") String id,
            @Param("workspaceId") String workspaceId,
            @Param("userId") String userId,
            @Param("filename") String filename,
            @Param("displayName") String displayName,
            @Param("normalizedFilename") String normalizedFilename,
            @Param("mimeType") String mimeType,
            @Param("byteSize") long byteSize,
            @Param("status") String status,
            @Param("sourceUri") String sourceUri,
            @Param("contentHash") String contentHash,
            @Param("currentContentHash") String currentContentHash,
            @Param("currentVersion") long currentVersion,
            @Param("documentRole") String documentRole,
            @Param("sortOrder") long sortOrder,
            @Param("uploadedAt") java.time.Instant uploadedAt,
            @Param("updatedAt") java.time.Instant updatedAt,
            @Param("selectionMode") String selectionMode
    );

    /** 완료 후처리(reconcile) 대상: 아직 후처리 안 된(reconciled_at IS NULL) origin·status 문서. */
    List<Document> findAllByOriginAndStatusAndReconciledAtIsNull(String origin, DocumentStatus status);

    List<Document> findAllByStatusAndPipelineRunIdIsNotNull(DocumentStatus status);

    /** 호환 문서 목록: 채팅 편입 문서를 포함한 활성 문서를 공용 순서로 조회한다. 스킬 참고 문서는 뺀다. */
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL "
            + "AND (d.origin IS NULL OR d.origin <> 'skill_reference') "
            + "ORDER BY d.sortOrder ASC, d.id ASC")
    List<Document> findVisibleByWorkspaceId(@Param("workspaceId") String workspaceId);

    /**
     * 문서 트리 응답이 바뀌었는지 가리는 지문. 트리를 조립하지 않고 304를 판단할 때 쓴다.
     *
     * <p>트리에 실리는 문서·폴더 행의 {@code xmin}을 모은다. 행이 갱신되면 {@code xmin}이 바뀌므로
     * 엔티티·벌크 갱신·네이티브 SQL 어느 경로로 바뀌어도 지문이 달라진다. 시각 비교와 달리 늦게
     * 커밋된 갱신도 놓치지 않는다. 편집 상태는 행이 있는지만 편집 가능 여부에 쓰이므로 ID만 넣는다.
     * 문서·폴더 권한 설정은 항목의 can_edit를 바꾸므로 함께 넣는다.
     *
     * <p>{@code stalled}는 DB 변경 없이 시간이 지나 바뀌므로 {@code stalledBefore}로 따로 넣는다.
     * 조건은 {@link fruition.core.document.service.DocumentItemAssembler}의 판정과 같아야 한다.
     */
    @Query(value = """
            SELECT md5(
                coalesce((SELECT string_agg(d.id || ':' || d.xmin::text
                                     || CASE WHEN d.status NOT IN ('completed', 'failed')
                                                  AND d.pipeline_run_id IS NOT NULL
                                                  AND d.processing_updated_at < :stalledBefore
                                             THEN ':stalled' ELSE '' END,
                                     ',' ORDER BY d.id)
                          FROM documents d
                          WHERE d.workspace_id = :workspaceId
                            AND d.deleted_at IS NULL
                            AND (d.origin IS NULL OR d.origin <> 'skill_reference')), '')
                || '|' || coalesce((SELECT string_agg(f.id::text || ':' || f.xmin::text, ',' ORDER BY f.id)
                                    FROM folders f
                                    WHERE f.workspace_id = :workspaceId
                                      AND f.deleted_at IS NULL), '')
                || '|' || coalesce((SELECT string_agg(e.document_id, ',' ORDER BY e.document_id)
                                    FROM document_edit_states e
                                    JOIN documents d ON d.id = e.document_id
                                    WHERE d.workspace_id = :workspaceId
                                      AND d.deleted_at IS NULL), '')
                || '|' || coalesce((SELECT string_agg(p.id::text || ':' || p.xmin::text, ',' ORDER BY p.id)
                                    FROM document_permissions p
                                    WHERE p.workspace_id = :workspaceId), ''))
            """, nativeQuery = true)
    String findTreeFingerprint(@Param("workspaceId") String workspaceId,
                               @Param("stalledBefore") Instant stalledBefore);

    /** 파일명 검색은 본문을 조회하지 않는다. */
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL "
            + "AND (d.origin IS NULL OR d.origin <> 'skill_reference') "
            + "AND (LOWER(d.displayName) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR LOWER(d.filename) LIKE LOWER(CONCAT('%', :query, '%'))) "
            + "ORDER BY d.sortOrder ASC, d.id ASC")
    List<Document> searchVisibleByWorkspaceId(
            @Param("workspaceId") String workspaceId,
            @Param("query") String query
    );

    /** 스킬 피커용 참고 문서 목록. 최근 업로드가 앞에 온다. */
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL AND d.origin = 'skill_reference' "
            + "ORDER BY d.uploadedAt DESC, d.id DESC")
    List<Document> findSkillReferences(@Param("workspaceId") String workspaceId);

    /** 스킬 참고 문서끼리의 이름 공간. 정규화는 uq_documents_skill_reference_name 인덱스와 같다. */
    @Query(value = "SELECT normalize(lower(normalize(btrim(filename), NFC)), NFC) FROM documents "
            + "WHERE workspace_id = :workspaceId AND origin = 'skill_reference' AND deleted_at IS NULL",
            nativeQuery = true)
    List<String> findActiveSkillReferenceNames(@Param("workspaceId") String workspaceId);

    Optional<Document> findByIdAndWorkspaceIdAndDeletedAtIsNull(String id, String workspaceId);

    /**
     * 노트 저장 projection: 현재 편집본 해시를 PG에 반영한다.
     * 목록 API가 편집 상태 조회 없이 content_hash(마지막 ingest 스냅샷)와 비교해 needs_reingest를 판단할 수 있게 한다.
     */
    @Transactional
    @Modifying
    @Query("UPDATE Document d SET d.currentContentHash = :contentHash, d.updatedAt = :updatedAt, "
            + "d.updatedBy = :updatedBy WHERE d.id = :documentId")
    int updateCurrentContentHash(
            @Param("documentId") String documentId,
            @Param("contentHash") String contentHash,
            @Param("updatedAt") Instant updatedAt,
            @Param("updatedBy") String updatedBy
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.id = :documentId "
            + "AND d.workspaceId = :workspaceId")
    Optional<Document> findByIdAndWorkspaceIdForUpdate(
            @Param("documentId") String documentId,
            @Param("workspaceId") String workspaceId
    );

    // workspaces는 access_db 소유라 여기서 조인할 수 없다.
    // workspace 유효성은 WorkspaceAccessGuard(projection/내부 API)가 담당한다.
    @Query(value = "SELECT d.* FROM documents d WHERE d.id = :documentId "
            + "AND d.deleted_at IS NULL", nativeQuery = true)
    Optional<Document> findByIdInActiveWorkspace(
            @Param("documentId") String documentId
    );

    Optional<Document> findByIdAndWorkspaceIdAndDeletedAtIsNotNull(String id, String workspaceId);

    List<Document> findAllByWorkspaceIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
            String workspaceId
    );

    @Query("SELECT COALESCE(MAX(d.sortOrder), -1) FROM Document d "
            + "WHERE d.workspaceId = :workspaceId "
            + "AND d.documentRole = :documentRole "
            + "AND d.folderId IS NULL "
            + "AND d.deletedAt IS NULL")
    long findMaxRootSortOrder(
            @Param("workspaceId") String workspaceId,
            @Param("documentRole") DocumentRole documentRole
    );

    @Query("SELECT COALESCE(MAX(d.sortOrder), -1) FROM Document d "
            + "WHERE d.workspaceId = :workspaceId "
            + "AND ((:folderId IS NULL AND d.folderId IS NULL) OR d.folderId = :folderId) "
            + "AND d.deletedAt IS NULL")
    long findMaxSortOrderInFolder(
            @Param("workspaceId") String workspaceId,
            @Param("folderId") java.util.UUID folderId
    );

    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND ((:folderId IS NULL AND d.folderId IS NULL) OR d.folderId = :folderId) "
            + "AND d.deletedAt IS NULL "
            + "AND (d.origin IS NULL OR d.origin <> 'skill_reference') "
            + "ORDER BY d.sortOrder ASC, d.id ASC")
    List<Document> findChildDocuments(
            @Param("workspaceId") String workspaceId,
            @Param("folderId") java.util.UUID folderId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND ((:folderId IS NULL AND d.folderId IS NULL) OR d.folderId = :folderId) "
            + "AND d.deletedAt IS NULL "
            + "ORDER BY d.sortOrder ASC, d.id ASC")
    List<Document> findChildDocumentsForUpdate(
            @Param("workspaceId") String workspaceId,
            @Param("folderId") java.util.UUID folderId
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Document d SET d.sortOrder = :sortOrder, d.updatedAt = :updatedAt "
            + "WHERE d.id = :id AND d.workspaceId = :workspaceId AND d.deletedAt IS NULL")
    void updateSortOrder(
            @Param("id") String id,
            @Param("workspaceId") String workspaceId,
            @Param("sortOrder") long sortOrder,
            @Param("updatedAt") Instant updatedAt
    );

    boolean existsByWorkspaceIdAndFolderIdAndDeletedAtIsNull(String workspaceId, java.util.UUID folderId);

    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId AND d.deletedAt IS NULL "
            + "AND (d.origin IS NULL OR d.origin <> 'skill_reference') "
            + "AND (LOWER(d.displayName) LIKE :pattern OR d.normalizedFilename LIKE :pattern) "
            + "ORDER BY d.displayName ASC, d.id ASC")
    List<Document> searchByName(
            @Param("workspaceId") String workspaceId,
            @Param("pattern") String pattern
    );

    /** 루트 폴더와 그 하위 폴더에 속한 문서 전체를 같은 삭제 작업 ID로 소프트 삭제한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "WITH RECURSIVE subtree AS ("
            + "SELECT id FROM folders WHERE id = :rootId "
            + "UNION ALL "
            + "SELECT f.id FROM folders f JOIN subtree s ON f.parent_folder_id = s.id) "
            + "UPDATE documents SET deleted_at = :deletedAt, deleted_by = :deletedBy, "
            + "delete_operation_id = :operationId, current_version = current_version + 1, updated_at = :deletedAt, "
            + "status = CASE WHEN document_role = 'EDITABLE' THEN 'uploaded' ELSE status END "
            + "WHERE folder_id IN (SELECT id FROM subtree) AND deleted_at IS NULL",
            nativeQuery = true)
    void softDeleteDocumentsInSubtree(
            @Param("rootId") java.util.UUID rootId,
            @Param("deletedBy") String deletedBy,
            @Param("deletedAt") Instant deletedAt,
            @Param("operationId") java.util.UUID operationId
    );

    /**
     * 문서가 휴지통에 있는지 읽고 삭제와 겹치지 않게 행을 공유 잠금한다. 문서가 없으면 비어 있다.
     * 삭제는 같은 행을 FOR UPDATE로 잡으므로 둘 중 먼저 온 쪽이 끝난 뒤 나머지가 진행한다.
     */
    @Query(value = "SELECT deleted_at IS NOT NULL FROM documents WHERE id = :documentId FOR SHARE",
            nativeQuery = true)
    java.util.Optional<Boolean> findDeletedForShare(@Param("documentId") String documentId);

    /** 같은 삭제 작업으로 휴지통에 들어간 문서 ID. 위키 정리 명령을 문서마다 보낼 때 쓴다. */
    @Query(value = "SELECT id FROM documents WHERE delete_operation_id = :operationId", nativeQuery = true)
    java.util.List<String> findIdsByDeleteOperationId(@Param("operationId") java.util.UUID operationId);

    /** 복구 대상 폴더의 하위 트리에 속하고 같은 삭제 작업으로 삭제된 문서만 되살린다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "WITH RECURSIVE subtree AS ("
            + "SELECT id FROM folders WHERE id = :rootId "
            + "UNION ALL "
            + "SELECT f.id FROM folders f JOIN subtree s ON f.parent_folder_id = s.id) "
            + "UPDATE documents SET deleted_at = NULL, deleted_by = NULL, delete_operation_id = NULL, "
            + "current_version = current_version + 1, updated_at = :restoredAt "
            + "WHERE folder_id IN (SELECT id FROM subtree) "
            + "AND deleted_at IS NOT NULL AND delete_operation_id = :operationId",
            nativeQuery = true)
    void restoreDocumentsInSubtree(
            @Param("rootId") java.util.UUID rootId,
            @Param("operationId") java.util.UUID operationId,
            @Param("restoredAt") Instant restoredAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Document d SET d.currentVersion = d.currentVersion + 1, "
            + "d.folderId = :folderId, d.sortOrder = :sortOrder, d.updatedAt = :updatedAt "
            + "WHERE d.id = :documentId AND d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL AND d.currentVersion = :baseVersion")
    int moveIfVersionMatches(
            @Param("documentId") String documentId,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("folderId") java.util.UUID folderId,
            @Param("sortOrder") long sortOrder,
            @Param("updatedAt") Instant updatedAt
    );

    /** 동일 부모의 파일·폴더 이름만 비교한다. 최종 경합은 공유 DB 고유 제약이 막는다. */
    @Query(value = "SELECT normalized_name FROM document_tree_names "
            + "WHERE workspace_id = :workspaceId "
            + "AND parent_folder_id IS NOT DISTINCT FROM :folderId", nativeQuery = true)
    List<String> findActiveSiblingNames(@Param("workspaceId") String workspaceId,
                                        @Param("folderId") java.util.UUID folderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND d.documentRole = fruition.core.document.domain.DocumentRole.EDITABLE "
            + "AND ((:folderId IS NULL AND d.folderId IS NULL) "
            + "OR d.folderId = :folderId) "
            + "AND d.deletedAt IS NULL "
            + "ORDER BY d.sortOrder ASC, d.id ASC")
    List<Document> findSiblingPagesForUpdate(
            @Param("workspaceId") String workspaceId,
            @Param("folderId") java.util.UUID folderId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND d.documentRole = :documentRole "
            + "AND d.folderId IS NULL "
            + "AND d.deletedAt IS NULL "
            + "ORDER BY d.sortOrder ASC, d.id ASC")
    List<Document> findRootItemsForUpdate(
            @Param("workspaceId") String workspaceId,
            @Param("documentRole") DocumentRole documentRole
    );

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Document d SET d.currentVersion = d.currentVersion + 1, "
            + "d.currentContentHash = :contentHash, d.byteSize = :byteSize, d.updatedAt = :updatedAt "
            + "WHERE d.id = :documentId AND d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL AND d.currentVersion = :baseVersion")
    int updateContentIfVersionMatches(
            @Param("documentId") String documentId,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("contentHash") String contentHash,
            @Param("byteSize") long byteSize,
            @Param("updatedAt") Instant updatedAt
    );

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Document d SET d.currentVersion = d.currentVersion + 1, "
            + "d.filename = :filename, d.displayName = :displayName, "
            + "d.normalizedFilename = :normalizedFilename, d.updatedAt = :updatedAt "
            + "WHERE d.id = :documentId AND d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL AND d.currentVersion = :baseVersion")
    int renameIfVersionMatches(
            @Param("documentId") String documentId,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("filename") String filename,
            @Param("displayName") String displayName,
            @Param("normalizedFilename") String normalizedFilename,
            @Param("updatedAt") Instant updatedAt
    );

    /** 삭제하면 위키에서 빠지므로 편집 문서는 미편입 상태로 되돌린다. 복구하면 다시 편입해야 한다. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Document d SET d.currentVersion = d.currentVersion + 1, "
            + "d.deletedAt = :deletedAt, d.deletedBy = :deletedBy, "
            + "d.deleteOperationId = :deleteOperationId, d.updatedAt = :deletedAt, "
            + "d.status = CASE WHEN d.documentRole = fruition.core.document.domain.DocumentRole.EDITABLE "
            + "THEN fruition.core.document.domain.DocumentStatus.uploaded ELSE d.status END "
            + "WHERE d.id = :documentId AND d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL AND d.currentVersion = :baseVersion")
    int softDeleteIfVersionMatches(
            @Param("documentId") String documentId,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("deletedBy") String deletedBy,
            @Param("deletedAt") Instant deletedAt,
            @Param("deleteOperationId") java.util.UUID deleteOperationId
    );

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Document d SET d.currentVersion = d.currentVersion + 1, "
            + "d.deletedAt = NULL, d.deletedBy = NULL, d.deleteOperationId = NULL, "
            + "d.folderId = NULL, "
            + "d.sortOrder = :sortOrder, d.updatedAt = :restoredAt "
            + "WHERE d.id = :documentId AND d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NOT NULL AND d.currentVersion = :baseVersion")
    int restoreIfVersionMatches(
            @Param("documentId") String documentId,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("sortOrder") long sortOrder,
            @Param("restoredAt") Instant restoredAt
    );
}
