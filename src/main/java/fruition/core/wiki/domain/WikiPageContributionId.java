package fruition.core.wiki.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * 한 작업이 한 페이지에 남기는 기여는 하나뿐이므로 이 조합이 자연키가 된다.
 * 콜백이 재전송돼도 중복 행이 생기지 않는다.
 */
@Embeddable
public class WikiPageContributionId implements Serializable {

    @Column(name = "page_id", nullable = false)
    private String pageId;

    @Column(name = "ingest_operation_id", nullable = false)
    private String ingestOperationId;

    protected WikiPageContributionId() {}

    public WikiPageContributionId(String pageId, String ingestOperationId) {
        this.pageId = pageId;
        this.ingestOperationId = ingestOperationId;
    }

    public String getPageId() { return pageId; }
    public String getIngestOperationId() { return ingestOperationId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WikiPageContributionId that)) return false;
        return Objects.equals(pageId, that.pageId)
                && Objects.equals(ingestOperationId, that.ingestOperationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageId, ingestOperationId);
    }
}
