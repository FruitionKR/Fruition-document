package fruition.core.aihistory.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Serializes the final Document apply with durable user/workspace purge fences. */
@Component
public class WikiPublicationPurgeFence {

    private final JdbcTemplate jdbc;

    public WikiPublicationPurgeFence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void markPurged(String scopeType, String scopeId) {
        lock(scopeType, scopeId);
        jdbc.update("""
                INSERT INTO wiki_publication_purge_fences(scope_type, scope_id)
                VALUES (?, ?) ON CONFLICT DO NOTHING
                """, scopeType, scopeId);
    }

    public void lockAndRejectPurged(String workspaceId, String userId) {
        List<Scope> scopes = java.util.stream.Stream.of(
                        new Scope("user", userId), new Scope("workspace", workspaceId))
                .sorted(java.util.Comparator.comparing(Scope::lockKey))
                .toList();
        scopes.forEach(scope -> lock(scope.type(), scope.id()));
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM wiki_publication_purge_fences
                WHERE (scope_type = 'workspace' AND scope_id = ?)
                   OR (scope_type = 'user' AND scope_id = ?)
                """, Integer.class, workspaceId, userId);
        if (count != null && count > 0) {
            throw new WikiPublicationPurgedScopeException();
        }
    }

    private void lock(String type, String id) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { },
                "wiki-publication-purge:" + type + ":" + id);
    }

    private record Scope(String type, String id) {
        String lockKey() { return type + ":" + id; }
    }

    public static class WikiPublicationPurgedScopeException extends RuntimeException {
        public WikiPublicationPurgedScopeException() {
            super("파기된 범위의 Wiki publication은 반영할 수 없습니다.");
        }
    }
}
