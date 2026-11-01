package fruition.core.wiki.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 페이지를 구성하는 기여 한 건(ingest·사람 수정·lint). "지금 누가 이 페이지를 받치고 있나"의 원본이다.
 *
 * <p>복구 판정이 전부 이 테이블에서 나온다. 살아 있는 기여를 {@code sequenceRevision} 순으로 놓고
 * 제외할 작업의 위치를 보면 삭제·복원·재조립이 갈린다.
 *
 * <p>복구는 행을 지우지 않고 {@code active}를 끈다. 지우면 연속 복구에서 이전에 제외한 기여가
 * 다시 살아난다.
 *
 * <p>같은 문서를 다시 편입하면 이전 편입의 행을 {@code deactivatedBy}=새 편입으로 끈다. 새 편입을 되돌리면
 * 그 행만 다시 켠다.
 *
 * <p>lint는 결과의 {@code ledger_contributions}로 두 종류 행을 남긴다(ai ADR 0042). 자기 하위 항목
 * 기록(작업 id = lint)과, 원래 편입 작업 id로 다른 페이지에 이은 편입 기록이다. 둘 다
 * {@code lintOperationId}에 그 lint를 적어 lint 복원이 함께 끈다.
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

    /** 이 행을 남긴 lint. ingest·사람 수정 기여는 null이다. */
    @Column(name = "lint_operation_id")
    private String lintOperationId;

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

    /** lint가 남긴 행. 자기 기록이면 {@code ingestOperationId}가 lint id와 같다. */
    public static WikiPageContribution lint(String pageId, String operationId, String sourceDocumentId,
                                            long sequenceRevision, String objectKey,
                                            String lintOperationId, Instant createdAt) {
        WikiPageContribution contribution = new WikiPageContribution(
                pageId, operationId, sourceDocumentId, sequenceRevision, objectKey, createdAt);
        contribution.lintOperationId = lintOperationId;
        return contribution;
    }

    /**
     * 꺼진 행에 lint가 같은 (페이지, 작업)을 다시 잇는다. 기록이 페이지·작업마다 하나라 새 행을 만들 수 없다.
     * 예: 다시 합치기를 lint 복원한 뒤 다음 lint가 다시 합친다.
     */
    public void relink(String sourceDocumentId, long sequenceRevision, String objectKey,
                       String lintOperationId) {
        this.sourceDocumentId = sourceDocumentId;
        this.sequenceRevision = sequenceRevision;
        this.objectKey = objectKey;
        this.lintOperationId = lintOperationId;
        this.active = true;
        this.deactivatedBy = null;
    }

    /** 복구가 이 기여를 현재 본문에서 제외한다. 행은 남긴다. */
    public void deactivate(String restoreOperationId) {
        this.active = false;
        this.deactivatedBy = restoreOperationId;
    }

    /** 대체한 재편입을 되돌리면 그 재편입이 끈 이전 편입 기여를 다시 켠다. */
    public void reactivate() {
        this.active = true;
        this.deactivatedBy = null;
    }

    public String getPageId() { return id.getPageId(); }
    public String getIngestOperationId() { return id.getIngestOperationId(); }
    public String getSourceDocumentId() { return sourceDocumentId; }
    public long getSequenceRevision() { return sequenceRevision; }
    public String getObjectKey() { return objectKey; }
    public boolean isActive() { return active; }
    public String getDeactivatedBy() { return deactivatedBy; }
    public String getLintOperationId() { return lintOperationId; }

    public Instant getCreatedAt() { return createdAt; }
}
