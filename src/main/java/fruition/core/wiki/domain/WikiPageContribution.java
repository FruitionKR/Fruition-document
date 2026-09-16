package fruition.core.wiki.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 페이지를 구성하는 ingest 기여 한 건. "지금 누가 이 페이지를 받치고 있나"의 원본이다.
 *
 * <p>복구 판정이 전부 이 테이블에서 나온다. 살아 있는 기여를 {@code sequenceRevision} 순으로 놓고
 * 제외할 작업의 위치를 보면 삭제·복원·재조립이 갈린다.
 *
 * <p>복구는 행을 지우지 않고 {@code active}를 끈다. 지우면 연속 복구에서 이전에 제외한 기여가
 * 다시 살아난다.
 *
 * <p>lint는 원문 기여가 아니므로 이 행을 만들지 않는다.
 */
@Entity
@Table(name = "wiki_page_contributions")
public class WikiPageContribution {

    @EmbeddedId
    private WikiPageContributionId id;

    @Column(name = "source_document_id")
    private String sourceDocumentId;

    /** 이 기여가 처음 적용된 페이지 revision. 조립 순서를 정하는 기준이다. */
    @Column(name = "sequence_revision", nullable = false)
    private long sequenceRevision;

    /** 재조립에 사용할 불변 기여 조각 key. */
    @Column(name = "object_key", nullable = false, columnDefinition = "TEXT")
    private String objectKey;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "deactivated_by")
    private String deactivatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WikiPageContribution() {}

    public WikiPageContribution(String pageId, String ingestOperationId, String sourceDocumentId,
                                long sequenceRevision, String objectKey, Instant createdAt) {
        this.id = new WikiPageContributionId(pageId, ingestOperationId);
        this.sourceDocumentId = sourceDocumentId;
        this.sequenceRevision = sequenceRevision;
        this.objectKey = objectKey;
        this.active = true;
        this.createdAt = createdAt;
    }

    /** 복구가 이 기여를 현재 본문에서 제외한다. 행은 남긴다. */
    public void deactivate(String restoreOperationId) {
        this.active = false;
        this.deactivatedBy = restoreOperationId;
    }

    public String getPageId() { return id.getPageId(); }
    public String getIngestOperationId() { return id.getIngestOperationId(); }
    public String getSourceDocumentId() { return sourceDocumentId; }
    public long getSequenceRevision() { return sequenceRevision; }
    public String getObjectKey() { return objectKey; }
    public boolean isActive() { return active; }
    public String getDeactivatedBy() { return deactivatedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
