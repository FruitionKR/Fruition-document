package fruition.core.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_assets")
public class DocumentAsset {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "uploaded_by")
    private String uploadedBy;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "storage_key", nullable = false, length = 512, unique = true)
    private String storageKey;

    @Column(name = "unreferenced_since")
    private Instant unreferencedSince;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentAsset() {}

    public DocumentAsset(UUID id, String workspaceId, String uploadedBy, String originalFilename,
                         String contentType, long byteSize, int width, int height,
                         String contentHash, String storageKey, Instant createdAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.uploadedBy = uploadedBy;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.byteSize = byteSize;
        this.width = width;
        this.height = height;
        this.contentHash = contentHash;
        this.storageKey = storageKey;
        this.createdAt = createdAt;
    }

    public void markUnreferenced(Instant since) { this.unreferencedSince = since; }
    public void markReferenced() { this.unreferencedSince = null; }

    public UUID getId() { return id; }
    public String getWorkspaceId() { return workspaceId; }
    public String getUploadedBy() { return uploadedBy; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public long getByteSize() { return byteSize; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public String getContentHash() { return contentHash; }
    public String getStorageKey() { return storageKey; }
    public Instant getUnreferencedSince() { return unreferencedSince; }
    public Instant getCreatedAt() { return createdAt; }
}
