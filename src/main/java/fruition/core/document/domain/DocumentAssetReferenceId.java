package fruition.core.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class DocumentAssetReferenceId implements Serializable {

    @Column(name = "document_id")
    private String documentId;

    @Column(name = "asset_id")
    private UUID assetId;

    protected DocumentAssetReferenceId() {}

    public DocumentAssetReferenceId(String documentId, UUID assetId) {
        this.documentId = documentId;
        this.assetId = assetId;
    }

    public String getDocumentId() { return documentId; }
    public UUID getAssetId() { return assetId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DocumentAssetReferenceId that)) return false;
        return Objects.equals(documentId, that.documentId) && Objects.equals(assetId, that.assetId);
    }

    @Override
    public int hashCode() { return Objects.hash(documentId, assetId); }
}
