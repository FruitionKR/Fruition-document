package fruition.core.wiki.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 워크스페이스 위키 버전(#68). 위키를 바꾸는 결과를 반영할 때 올리고, wiki/graph가 ETag로 쓴다. */
@Repository
public class WikiWorkspaceVersionRepository {

    private final JdbcTemplate jdbc;

    public WikiWorkspaceVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void bump(String workspaceId) {
        jdbc.update("""
                INSERT INTO wiki_workspace_versions(workspace_id, version, updated_at) VALUES (?, 1, now())
                ON CONFLICT (workspace_id) DO UPDATE
                SET version = wiki_workspace_versions.version + 1, updated_at = now()
                """, workspaceId);
    }

    /** 한 번도 바뀌지 않은 워크스페이스는 0이다. */
    public long current(String workspaceId) {
        return jdbc.query("SELECT version FROM wiki_workspace_versions WHERE workspace_id = ?",
                rs -> rs.next() ? rs.getLong(1) : 0L, workspaceId);
    }
}
