package fruition.core.document.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** PDF → Markdown 변환 대기열 행. documentId는 변환 결과를 담을 placeholder Markdown 문서다. */
@Entity
@Table(name = "document_convert_queue")
public class DocumentConvertQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false, unique = true)
    private String documentId;

    @Column(name = "source_document_id", nullable = false)
    private String sourceDocumentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private String status;

    protected DocumentConvertQueue() {}

    public DocumentConvertQueue(String documentId, String sourceDocumentId) {
        this.documentId = documentId;
        this.sourceDocumentId = sourceDocumentId;
        this.createdAt = Instant.now();
        this.status = "pending";
    }

    public Long getId() { return id; }
    public String getDocumentId() { return documentId; }
    public String getSourceDocumentId() { return sourceDocumentId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
