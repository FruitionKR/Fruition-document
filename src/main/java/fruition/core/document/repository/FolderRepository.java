package fruition.core.document.repository;

import fruition.core.document.domain.Folder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FolderRepository extends JpaRepository<Folder, UUID> {

    Optional<Folder> findByIdAndWorkspaceIdAndDeletedAtIsNull(UUID id, String workspaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Folder f WHERE f.id = :id AND f.workspaceId = :workspaceId AND f.deletedAt IS NULL")
    Optional<Folder> findActiveForUpdate(@Param("id") UUID id, @Param("workspaceId") String workspaceId);


    Optional<Folder> findByIdAndWorkspaceIdAndDeletedAtIsNotNull(UUID id, String workspaceId);

    List<Folder> findAllByWorkspaceIdAndDeletedAtIsNull(String workspaceId);

    boolean existsByWorkspaceIdAndParentFolderIdAndDeletedAtIsNull(String workspaceId, UUID parentFolderId);

    @Query("SELECT COALESCE(MAX(f.sortOrder), -1) FROM Folder f "
            + "WHERE f.workspaceId = :workspaceId "
            + "AND ((:parentFolderId IS NULL AND f.parentFolderId IS NULL) "
            + "OR f.parentFolderId = :parentFolderId) "
            + "AND f.deletedAt IS NULL")
    long findMaxSortOrder(
            @Param("workspaceId") String workspaceId,
            @Param("parentFolderId") UUID parentFolderId
    );

    @Query("SELECT f FROM Folder f WHERE f.workspaceId = :workspaceId "
            + "AND ((:parentFolderId IS NULL AND f.parentFolderId IS NULL) "
            + "OR f.parentFolderId = :parentFolderId) "
            + "AND f.deletedAt IS NULL "
            + "ORDER BY f.sortOrder ASC, f.id ASC")
    List<Folder> findChildren(
            @Param("workspaceId") String workspaceId,
            @Param("parentFolderId") UUID parentFolderId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Folder f WHERE f.workspaceId = :workspaceId "
            + "AND ((:parentFolderId IS NULL AND f.parentFolderId IS NULL) "
            + "OR f.parentFolderId = :parentFolderId) "
            + "AND f.deletedAt IS NULL "
            + "ORDER BY f.sortOrder ASC, f.id ASC")
    List<Folder> findChildrenForUpdate(
            @Param("workspaceId") String workspaceId,
            @Param("parentFolderId") UUID parentFolderId
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Folder f SET f.sortOrder = :sortOrder, f.updatedAt = :updatedAt "
            + "WHERE f.id = :id AND f.workspaceId = :workspaceId AND f.deletedAt IS NULL")
    void updateSortOrder(
            @Param("id") UUID id,
            @Param("workspaceId") String workspaceId,
            @Param("sortOrder") long sortOrder,
            @Param("updatedAt") Instant updatedAt
    );

    /** 최상위부터 대상 폴더까지의 조상 경로를 순서대로 반환한다(root 먼저, 대상 폴더 마지막). */
    @Query(value = "WITH RECURSIVE path AS ("
            + "SELECT id, parent_folder_id, 0 AS depth FROM folders WHERE id = :folderId AND deleted_at IS NULL "
            + "UNION ALL "
            + "SELECT f.id, f.parent_folder_id, p.depth + 1 FROM folders f "
            + "JOIN path p ON f.id = p.parent_folder_id WHERE f.deleted_at IS NULL) "
            + "SELECT fo.* FROM folders fo JOIN path ON fo.id = path.id ORDER BY path.depth DESC",
            nativeQuery = true)
    List<Folder> findAncestorPath(@Param("folderId") UUID folderId);

    @Query("SELECT f FROM Folder f WHERE f.workspaceId = :workspaceId AND f.deletedAt IS NULL "
            + "AND LOWER(f.name) LIKE :pattern ORDER BY f.name ASC, f.id ASC")
    List<Folder> searchByName(
            @Param("workspaceId") String workspaceId,
            @Param("pattern") String pattern
    );

    /** 대상 부모 폴더의 조상 경로(자기 자신 포함)에 이동 폴더가 있으면 순환이다. */
    @Query(value = "WITH RECURSIVE ancestors AS ("
            + "SELECT id, parent_folder_id FROM folders WHERE id = :targetParentId "
            + "UNION ALL "
            + "SELECT f.id, f.parent_folder_id FROM folders f "
            + "JOIN ancestors a ON f.id = a.parent_folder_id) "
            + "SELECT count(*) FROM ancestors WHERE id = :movingFolderId",
            nativeQuery = true)
    long countAncestorMatches(
            @Param("targetParentId") UUID targetParentId,
            @Param("movingFolderId") UUID movingFolderId
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Folder f SET f.currentVersion = f.currentVersion + 1, "
            + "f.name = :name, f.updatedAt = :updatedAt "
            + "WHERE f.id = :id AND f.workspaceId = :workspaceId "
            + "AND f.deletedAt IS NULL AND f.currentVersion = :baseVersion")
    int renameIfVersionMatches(
            @Param("id") UUID id,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("name") String name,
            @Param("updatedAt") Instant updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Folder f SET f.currentVersion = f.currentVersion + 1, "
            + "f.parentFolderId = :parentFolderId, f.sortOrder = :sortOrder, f.updatedAt = :updatedAt "
            + "WHERE f.id = :id AND f.workspaceId = :workspaceId "
            + "AND f.deletedAt IS NULL AND f.currentVersion = :baseVersion")
    int moveIfVersionMatches(
            @Param("id") UUID id,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("parentFolderId") UUID parentFolderId,
            @Param("sortOrder") long sortOrder,
            @Param("updatedAt") Instant updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Folder f SET f.currentVersion = f.currentVersion + 1, "
            + "f.deletedAt = :deletedAt, f.deletedBy = :deletedBy, f.deleteOperationId = :operationId, "
            + "f.updatedAt = :deletedAt "
            + "WHERE f.id = :id AND f.workspaceId = :workspaceId "
            + "AND f.deletedAt IS NULL AND f.currentVersion = :baseVersion")
    int softDeleteRootIfVersionMatches(
            @Param("id") UUID id,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("deletedBy") String deletedBy,
            @Param("deletedAt") Instant deletedAt,
            @Param("operationId") UUID operationId
    );

    /** 루트 폴더의 하위 폴더 전체를 같은 삭제 작업 ID로 소프트 삭제한다(루트 자신은 제외). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "WITH RECURSIVE subtree AS ("
            + "SELECT id FROM folders WHERE parent_folder_id = :rootId AND deleted_at IS NULL "
            + "UNION ALL "
            + "SELECT f.id FROM folders f JOIN subtree s ON f.parent_folder_id = s.id WHERE f.deleted_at IS NULL) "
            + "UPDATE folders SET deleted_at = :deletedAt, deleted_by = :deletedBy, "
            + "delete_operation_id = :operationId, current_version = current_version + 1, updated_at = :deletedAt "
            + "WHERE id IN (SELECT id FROM subtree)",
            nativeQuery = true)
    void softDeleteDescendantFolders(
            @Param("rootId") UUID rootId,
            @Param("deletedBy") String deletedBy,
            @Param("deletedAt") Instant deletedAt,
            @Param("operationId") UUID operationId
    );

    /** 복구 대상 폴더를 지정한 부모·순서로 되살린다. 원래 부모가 삭제 상태면 최상위(null)로 배치한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Folder f SET f.currentVersion = f.currentVersion + 1, "
            + "f.deletedAt = NULL, f.deletedBy = NULL, f.deleteOperationId = NULL, "
            + "f.parentFolderId = :parentFolderId, f.sortOrder = :sortOrder, f.updatedAt = :restoredAt "
            + "WHERE f.id = :id AND f.workspaceId = :workspaceId "
            + "AND f.deletedAt IS NOT NULL AND f.currentVersion = :baseVersion")
    int restoreRootIfVersionMatches(
            @Param("id") UUID id,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("parentFolderId") UUID parentFolderId,
            @Param("sortOrder") long sortOrder,
            @Param("restoredAt") Instant restoredAt
    );

    /** 복구 대상 폴더의 하위 트리 중 같은 삭제 작업으로 삭제된 폴더만 되살린다(대상 폴더 자신은 제외). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "WITH RECURSIVE subtree AS ("
            + "SELECT id FROM folders WHERE parent_folder_id = :rootId "
            + "AND deleted_at IS NOT NULL AND delete_operation_id = :operationId "
            + "UNION ALL "
            + "SELECT f.id FROM folders f JOIN subtree s ON f.parent_folder_id = s.id "
            + "WHERE f.deleted_at IS NOT NULL AND f.delete_operation_id = :operationId) "
            + "UPDATE folders SET deleted_at = NULL, deleted_by = NULL, delete_operation_id = NULL, "
            + "current_version = current_version + 1, updated_at = :restoredAt "
            + "WHERE id IN (SELECT id FROM subtree)",
            nativeQuery = true)
    void restoreDescendantFolders(
            @Param("rootId") UUID rootId,
            @Param("operationId") UUID operationId,
            @Param("restoredAt") Instant restoredAt
    );
}
