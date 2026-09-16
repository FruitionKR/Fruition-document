package fruition.core.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class DocumentContentVersionId implements Serializable {

    @Column(name = "document_id", nullable = false)
    private String documentId;

    @Column(name = "version", nullable = false)
    private long version;

    protected DocumentContentVersionId() {}

    public DocumentContentVersionId(String documentId, long version) {
        this.documentId = documentId;
        this.version = version;
    }

    public String getDocumentId() { return documentId; }
    public long getVersion() { return version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DocumentContentVersionId that)) return false;
        return version == that.version && Objects.equals(documentId, that.documentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, version);
    }
}
