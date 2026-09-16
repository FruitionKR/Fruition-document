package fruition.core.wikimaintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** workspace별 마지막 위키 lint 성공 시각. needs_lint 판단의 기준점이다. */
@Entity
@Table(name = "wiki_lint_state")
public class WikiLintState {

    @Id
    @Column(name = "workspace_id")
    private String workspaceId;

    @Column(name = "last_lint_at", nullable = false)
    private Instant lastLintAt;

    protected WikiLintState() {}

    public WikiLintState(String workspaceId, Instant lastLintAt) {
        this.workspaceId = workspaceId;
        this.lastLintAt = lastLintAt;
    }

    public String getWorkspaceId() { return workspaceId; }
    public Instant getLastLintAt() { return lastLintAt; }
}
