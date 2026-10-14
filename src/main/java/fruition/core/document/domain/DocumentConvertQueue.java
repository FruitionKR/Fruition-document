package fruition.core.document.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * PDF → Markdown 변환 진행 상태 행. documentId는 변환 결과를 담을 placeholder Markdown 문서다.
 * completedPages는 쪽 순서로 병합을 마친 쪽 수, nextStartPage는 다음에 발행할 batch의 시작 쪽(0부터)이다.
 */
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

    @Column(name = "completed_pages", nullable = false)
    private int completedPages;
    @Column(name = "total_pages", nullable = false)
    private int totalPages;
    @Column(name = "next_start_page", nullable = false)
    private int nextStartPage;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public int getCompletedPages() { return completedPages; }
    public int getTotalPages() { return totalPages; }
    public int getNextStartPage() { return nextStartPage; }
    public void checkpoint(int pages, int total) {
        completedPages = Math.max(completedPages, pages);
        totalPages = total;
        updatedAt = Instant.now();
    }
    /** 첫 결과에서 전체 쪽수를 알게 된다. 병합 전에도 다음 batch 발행에 쓴다. */
    public void recordTotalPages(int total) {
        totalPages = total;
        updatedAt = Instant.now();
    }
    public void dispatchedUpTo(int nextStartPage) {
        this.nextStartPage = nextStartPage;
        updatedAt = Instant.now();
    }

    protected DocumentConvertQueue() {}

    public DocumentConvertQueue(String documentId, String sourceDocumentId) {
        this.documentId = documentId;
        this.sourceDocumentId = sourceDocumentId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.status = "processing";
    }

    public Long getId() { return id; }
    public String getDocumentId() { return documentId; }
    public String getSourceDocumentId() { return sourceDocumentId; }
    public String getStatus() { return status; }
}
