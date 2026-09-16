package fruition.core.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_asset_orphans")
public class DocumentAssetOrphan {

    @Id
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "storage_key", nullable = false, length = 512, unique = true)
    private String storageKey;

    @Column(name = "failed_at", nullable = false)
    private Instant failedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected DocumentAssetOrphan() {}

    public DocumentAssetOrphan(UUID assetId, String storageKey, Instant failedAt, String lastError) {
        this.id = UUID.randomUUID();
        this.assetId = assetId;
        this.storageKey = storageKey;
        this.failedAt = failedAt;
        this.lastError = truncate(lastError);
    }

    public void recordRetryFailure(Instant failedAt, String lastError) {
        this.failedAt = failedAt;
        this.retryCount += 1;
        this.lastError = truncate(lastError);
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    public UUID getId() { return id; }
    public UUID getAssetId() { return assetId; }
    public String getStorageKey() { return storageKey; }
    public Instant getFailedAt() { return failedAt; }
    public int getRetryCount() { return retryCount; }
    public String getLastError() { return lastError; }
}
