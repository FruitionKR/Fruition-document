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

    List<Document> findAllByWorkspaceId(String workspaceId);

    List<Document> findAllByStatusAndPipelineRunIdIsNotNull(DocumentStatus status);

    /** 호환 문서 목록: 채팅 편입 문서를 포함한 활성 문서를 공용 순서로 조회한다. */
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL "
            + "ORDER BY d.sortOrder ASC, d.id ASC")
    List<Document> findVisibleByWorkspaceId(@Param("workspaceId") String workspaceId);

    /** 파일명 검색은 본문을 조회하지 않는다. */
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL "
            + "AND (LOWER(d.displayName) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR LOWER(d.filename) LIKE LOWER(CONCAT('%', :query, '%'))) "
            + "ORDER BY d.sortOrder ASC, d.id ASC")
    List<Document> searchVisibleByWorkspaceId(
            @Param("workspaceId") String workspaceId,
            @Param("query") String query
    );

    Optional<Document> findByIdAndWorkspaceIdAndDeletedAtIsNull(String id, String workspaceId);

    /**
     * 노트 저장 projection: 현재 편집본 해시를 PG에 반영한다.
     * 목록 API가 편집 상태 조회 없이 content_hash(마지막 ingest 스냅샷)와 비교해 needs_reingest를 판단할 수 있게 한다.
     */
    @Transactional
    @Modifying
    @Query("UPDATE Document d SET d.currentContentHash = :contentHash, d.updatedAt = :updatedAt "
            + "WHERE d.id = :documentId")
    int updateCurrentContentHash(
            @Param("documentId") String documentId,
            @Param("contentHash") String contentHash,
            @Param("updatedAt") Instant updatedAt
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
            + "delete_operation_id = :operationId, current_version = current_version + 1, updated_at = :deletedAt "
            + "WHERE folder_id IN (SELECT id FROM subtree) AND deleted_at IS NULL",
            nativeQuery = true)
    void softDeleteDocumentsInSubtree(
            @Param("rootId") java.util.UUID rootId,
            @Param("deletedBy") String deletedBy,
            @Param("deletedAt") Instant deletedAt,
            @Param("operationId") java.util.UUID operationId
    );

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

    /** 폴더 위치·문서 종류와 무관하게 활성 파일명을 읽는다. 최종 경합은 DB 고유 제약이 막는다. */
    @Query(value = "SELECT lower(normalize(btrim(filename), NFC)) FROM documents "
            + "WHERE workspace_id = :workspaceId AND deleted_at IS NULL", nativeQuery = true)
    List<String> findActiveNormalizedFilenames(@Param("workspaceId") String workspaceId);

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

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Document d SET d.currentVersion = d.currentVersion + 1, "
            + "d.deletedAt = :deletedAt, d.deletedBy = :deletedBy, "
            + "d.deleteOperationId = :deleteOperationId, d.updatedAt = :deletedAt "
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
