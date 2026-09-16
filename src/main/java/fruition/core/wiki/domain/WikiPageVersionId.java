package fruition.core.wiki.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class WikiPageVersionId implements Serializable {

    @Column(name = "page_id", nullable = false)
    private String pageId;

    @Column(name = "revision", nullable = false)
    private long revision;

    protected WikiPageVersionId() {}

    public WikiPageVersionId(String pageId, long revision) {
        this.pageId = pageId;
        this.revision = revision;
    }

    public String getPageId() { return pageId; }
    public long getRevision() { return revision; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WikiPageVersionId that)) return false;
        return revision == that.revision && Objects.equals(pageId, that.pageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageId, revision);
    }
}
