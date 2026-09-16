package fruition.core.wiki.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Wiki 페이지의 본문 이력.
 *
 * <p>{@code revision}은 단조 증가하며 되돌려도 줄지 않는다. 복구도 새 revision을 append한다.
 * 화면에 보여줄 버전이자 복구 좌표다.
 *
 * <p>{@code contributionCount}는 그 시점 살아 있던 기여 수다. 되돌리면 줄어들 수 있어
 * 같은 값이 서로 다른 revision에 나타날 수 있으므로 버전으로 쓰지 않는다.
 */
@Entity
@Table(name = "wiki_page_versions")
public class WikiPageVersion {

    @EmbeddedId
    private WikiPageVersionId id;

    @Column(name = "contribution_count", nullable = false)
    private int contributionCount;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String markdown;

    /** 그 본문이 담긴 불변 object key. 복구는 이 값을 재사용하고 저장소에 쓰지 않는다. */
    @Column(name = "markdown_key", nullable = false, columnDefinition = "TEXT")
    private String markdownKey;

    @Column(name = "content_hash", nullable = false, length = 71)
    private String contentHash;

    @Column(name = "operation_id")
    private String operationId;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WikiPageVersion() {}

    public WikiPageVersion(String pageId, long revision, int contributionCount,
                           String markdown, String markdownKey, String contentHash,
                           String operationId, String createdBy, Instant createdAt) {
        this.id = new WikiPageVersionId(pageId, revision);
        this.contributionCount = contributionCount;
        this.markdown = markdown;
        this.markdownKey = markdownKey;
        this.contentHash = contentHash;
        this.operationId = operationId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public String getPageId() { return id.getPageId(); }
    public long getRevision() { return id.getRevision(); }
    public int getContributionCount() { return contributionCount; }
    public String getMarkdown() { return markdown; }
    public String getMarkdownKey() { return markdownKey; }
    public String getContentHash() { return contentHash; }
    public String getOperationId() { return operationId; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
