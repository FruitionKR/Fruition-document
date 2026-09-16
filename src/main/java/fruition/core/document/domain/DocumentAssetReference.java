package fruition.core.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_asset_references")
public class DocumentAssetReference {

    @EmbeddedId
    private DocumentAssetReferenceId id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentAssetReference() {}

    public DocumentAssetReference(String documentId, UUID assetId, Instant createdAt) {
        this.id = new DocumentAssetReferenceId(documentId, assetId);
        this.createdAt = createdAt;
    }

    public String getDocumentId() { return id.getDocumentId(); }
    public UUID getAssetId() { return id.getAssetId(); }
    public Instant getCreatedAt() { return createdAt; }
}
