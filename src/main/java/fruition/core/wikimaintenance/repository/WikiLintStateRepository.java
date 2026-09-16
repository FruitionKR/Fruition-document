package fruition.core.wikimaintenance.repository;

import fruition.core.wikimaintenance.domain.WikiLintState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface WikiLintStateRepository extends JpaRepository<WikiLintState, String> {

    /** lint 성공 시각을 기록한다. 동시 실행에도 안전하게 upsert한다. */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO wiki_lint_state(workspace_id, last_lint_at)
            VALUES (:workspaceId, :lastLintAt)
            ON CONFLICT (workspace_id) DO UPDATE SET last_lint_at = EXCLUDED.last_lint_at
            """, nativeQuery = true)
    int upsert(@Param("workspaceId") String workspaceId, @Param("lastLintAt") Instant lastLintAt);
}
