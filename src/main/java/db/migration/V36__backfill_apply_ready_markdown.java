package db.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aitask.service.AiTaskResultApplier;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.ResultSet;

/** V35 이전 agent 결과의 적용용 canonical Markdown을 receipt에서 한 번 채운다. */
public class V36__backfill_apply_ready_markdown extends BaseJavaMigration {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var select = connection.prepareStatement("""
                SELECT p.run_id, r.event_payload::text
                FROM agent_apply_projections p
                LEFT JOIN ai_task_result_receipts r ON r.run_id = p.run_id AND r.task_kind = 'agent'
                WHERE p.status = 'ready' AND p.ready_markdown IS NULL
                FOR UPDATE OF p
                """)) {
            try (ResultSet rows = select.executeQuery();
                 var updateReady = connection.prepareStatement("""
                         UPDATE agent_apply_projections
                         SET ready_markdown = ?, updated_at = now()
                         WHERE run_id = ? AND status = 'ready' AND ready_markdown IS NULL
                         """ );
                 var updateFailed = connection.prepareStatement("""
                         UPDATE agent_apply_projections
                         SET status = 'failed', error_code = 'ready_markdown_migration_unrecoverable', updated_at = now()
                         WHERE run_id = ? AND status = 'ready' AND ready_markdown IS NULL
                         """)) {
                while (rows.next()) {
                    String runId = rows.getString(1);
                    String markdown = null;
                    try {
                        JsonNode event = objectMapper.readTree(rows.getString(2));
                        markdown = AiTaskResultApplier.expectedMarkdown(event);
                    } catch (Exception ignored) {
                        // 원본이 불완전하면 적용 가능 상태로 남기지 않는다.
                    }
                    if (markdown != null) {
                        updateReady.setString(1, markdown);
                        updateReady.setString(2, runId);
                        updateReady.executeUpdate();
                    } else {
                        updateFailed.setString(1, runId);
                        updateFailed.executeUpdate();
                    }
                }
            }
        }
    }
}
