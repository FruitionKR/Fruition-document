package fruition.core.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "document_content_versions")
public class DocumentContentVersion {

    @EmbeddedId
    private DocumentContentVersionId id;

    @Column(nullable = false)
    private long revision;

    @Column(name = "record_type", nullable = false)
    private String recordType = "legacy";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String markdown;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 이 버전을 만든 AI 작업. 사용자가 직접 편집한 버전이면 NULL이다. */
    @Column(name = "operation_id")
    private String operationId;

    /** 버전 복원으로 만든 버전이면 복원 대상 버전 번호. 그 밖의 저장이면 NULL이다. */
    @Column(name = "restored_from_version")
    private Long restoredFromVersion;

    protected DocumentContentVersion() {}

    public DocumentContentVersion(String documentId, long version, String markdown,
                                  String contentHash, String createdBy, Instant createdAt) {
        this.id = new DocumentContentVersionId(documentId, version);
        this.revision = version;
        this.markdown = markdown;
        this.contentHash = contentHash;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public DocumentContentVersion(String documentId, long version, long revision, String markdown,
                                  String contentHash, String createdBy, Instant createdAt, String recordType) {
        this(documentId, version, markdown, contentHash, createdBy, createdAt);
        this.revision = revision;
        this.recordType = recordType;
    }

    public String getDocumentId() { return id.getDocumentId(); }
    public long getVersion() { return id.getVersion(); }
    public long getRevision() { return revision; }
    public String getRecordType() { return recordType; }
    public String getMarkdown() { return markdown; }
    public String getContentHash() { return contentHash; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getOperationId() { return operationId; }
    public Long getRestoredFromVersion() { return restoredFromVersion; }
}
