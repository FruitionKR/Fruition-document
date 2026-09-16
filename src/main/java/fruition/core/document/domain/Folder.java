package fruition.core.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "folders")
public class Folder {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "parent_folder_id")
    private UUID parentFolderId;

    @Column(nullable = false)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private long sortOrder;

    @Column(name = "current_version", nullable = false)
    private long currentVersion;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private String deletedBy;

    @Column(name = "delete_operation_id")
    private UUID deleteOperationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Folder() {}

    public Folder(UUID id, String workspaceId, UUID parentFolderId, String name, long sortOrder) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.parentFolderId = parentFolderId;
        this.name = name;
        this.sortOrder = sortOrder;
        this.currentVersion = 1;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() { return id; }
    public String getWorkspaceId() { return workspaceId; }
    public UUID getParentFolderId() { return parentFolderId; }
    public String getName() { return name; }
    public long getSortOrder() { return sortOrder; }
    public long getCurrentVersion() { return currentVersion; }
    public Instant getDeletedAt() { return deletedAt; }
    public String getDeletedBy() { return deletedBy; }
    public UUID getDeleteOperationId() { return deleteOperationId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
