package fruition.core.aitask.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aitask.repository.PipelineTaskCancellationClient;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.query.service.QueryRunStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

@Service
public class AiTaskCancellationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PipelineTaskCancellationClient pipeline;
    private final WorkspaceAccessGuard access;
    private final ObjectMapper mapper;
    private final QueryRunStore queryRuns;
    private final fruition.core.query.service.QueryEventBroker events;
    private final io.minio.MinioClient storage;
    private final fruition.shared.util.StorageProperties storageProperties;

    public AiTaskCancellationService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            PipelineTaskCancellationClient pipeline, WorkspaceAccessGuard access, ObjectMapper mapper,
            QueryRunStore queryRuns, fruition.core.query.service.QueryEventBroker events, io.minio.MinioClient storage, fruition.shared.util.StorageProperties storageProperties) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.pipeline = pipeline;
        this.access = access;
        this.mapper = mapper;
        this.queryRuns = queryRuns;
        this.events = events;
        this.storage = storage;
        this.storageProperties = storageProperties;
    }

    public void register(JsonNode command) {
        String id = command.path("run_id").asText();
        if (!id.matches("[a-zA-Z0-9:_-]{1,120}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "작업 ID가 올바르지 않습니다.");
        transaction.executeWithoutResult(tx -> {
            jdbc.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind, command) VALUES (?, ?, ?, ?, CAST(? AS jsonb)) "
                    + "ON CONFLICT (id) DO NOTHING", id, command.path("workspace_id").asText(), command.path("user_id").asText(),
                    command.path("kind").asText(), command.toString());
            var matching = jdbc.queryForList("SELECT id FROM ai_task_runs WHERE id = ? AND command = CAST(? AS jsonb)",
                    String.class, id, command.toString());
            if (matching.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "작업 ID의 요청 내용이 다릅니다.");
        });
    }

    public void finish(String id) {
        int updated = jdbc.update("UPDATE ai_task_runs SET status = 'completed', updated_at = now() "
                + "WHERE id = ? AND status IN ('running', 'completed')", id);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "취소한 작업의 결과를 반환할 수 없습니다.");
    }

    public Map<String, Object> cancel(String id, String workspaceId, String userId) {
        access.requireMember(workspaceId, userId);
        transaction.executeWithoutResult(tx -> {
            owned(id, workspaceId, userId, true);
            jdbc.update("UPDATE ai_task_runs SET status = 'cancel_requested', error_code = NULL, updated_at = now() "
                    + "WHERE id = ? AND status NOT IN ('cancelled', 'rolling_back')", id);
        });
        advance(id);
        return status(id, workspaceId, userId);
    }

    public boolean cancelDocumentTasks(String documentId, String workspaceId, String userId) {
        var tasks = jdbc.queryForList("SELECT id FROM ai_task_runs WHERE workspace_id = ? AND user_id = ? "
                + "AND command->>'document_id' = ? ORDER BY created_at DESC", String.class, workspaceId, userId, documentId);
        for (String id : tasks) {
            transaction.executeWithoutResult(tx -> {
                owned(id, workspaceId, userId, true);
                jdbc.update("UPDATE ai_task_runs SET status = 'cancel_requested', error_code = NULL "
                        + "WHERE id = ? AND status NOT IN ('cancelled', 'rolling_back')", id);
            });
        }
        for (String id : tasks) {
            advance(id);
            if (!owned(id, workspaceId, userId, false).get("status").equals("cancelled")) return false;
        }
        return pipeline.cancelDocumentTasks(documentId, workspaceId, userId);
    }

    public java.util.Optional<Map<String, Object>> findStatus(String id, String workspaceId, String userId) {
        var rows = jdbc.queryForList("SELECT id, status, error_code FROM ai_task_runs WHERE id = ? AND workspace_id = ? AND user_id = ?",
                id, workspaceId, userId);
        return rows.stream().findFirst();
    }

    public Map<String, Object> status(String id, String workspaceId, String userId) {
        access.requireMember(workspaceId, userId);
        Map<String, Object> row = owned(id, workspaceId, userId, false);
        return Map.of("id", id, "status", row.get("status"), "error_code",
                row.get("error_code") == null ? "" : row.get("error_code"));
    }

    @Scheduled(fixedDelay = 1000)
    public void resume() {
        for (String id : jdbc.queryForList("SELECT id FROM ai_task_runs WHERE status IN "
                + "('cancel_requested', 'rolling_back') ORDER BY updated_at LIMIT 20", String.class)) {
            advance(id);
        }
    }

    private void advance(String id) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM ai_task_runs WHERE id = ?", id);
        if (!List.of("cancel_requested", "rolling_back").contains(row.get("status"))) return;
        String workspaceId = (String) row.get("workspace_id");
        String userId = (String) row.get("user_id");
        try {
            boolean converting = row.get("kind").equals("convert");
            JsonNode command = converting ? mapper.valueToTree(Map.of("run_id", id, "kind", "convert",
                    "workspace_id", workspaceId, "user_id", userId)) : mapper.readTree(row.get("command").toString());
            if (converting && !jdbc.queryForList("SELECT id FROM document_convert_queue WHERE document_id = ? AND status = 'processing'",
                    Long.class, id.substring("convert:".length())).isEmpty()) return;
            // DB 취소를 먼저 커밋한다. Python 복구 중 돌아오는 내부 요청과 잠금이 충돌하지 않는다.
            var result = converting ? new PipelineTaskCancellationClient.TaskStatus(id, "cancelled", null)
                    : row.get("status").equals("cancel_requested")
                    ? pipeline.cancel(id, workspaceId, userId, command)
                    : pipeline.status(id, workspaceId, userId);
            if (result == null) return;
            if (result.status().equals("rollback_failed")) {
                fail(id, result.errorCode());
                return;
            }
            jdbc.update("UPDATE ai_task_runs SET status = 'rolling_back', updated_at = now() "
                    + "WHERE id = ? AND status = 'cancel_requested'", id);
            if (!result.status().equals("cancelled")) return;
            pipeline.rollbackBackend(id, workspaceId, userId, command);
            transaction.executeWithoutResult(tx -> {
                var current = owned(id, workspaceId, userId, true);
                if (current.get("status").equals("cancelled")) return;
                if (!jdbc.queryForList("SELECT id FROM ai_task_changes WHERE run_id = ? AND NOT undone", Long.class, id).isEmpty())
                    throw new IllegalStateException("복구하지 않은 변경이 남아 있습니다.");
                if (!jdbc.queryForList("SELECT c.id FROM ai_task_changes c WHERE c.run_id = ? "
                        + "AND c.table_name = 'document_edit_states' AND NOT EXISTS (SELECT 1 FROM document_edit_outbox o "
                        + "WHERE o.event_id = 'rollback:' || c.run_id || ':' || (c.row_key->>'document_id'))", Long.class, id).isEmpty())
                    throw new IllegalStateException("복구 본문의 revision 확정이 남아 있습니다.");
                queryRuns.markCancelled(id);
                events.cancel(id);
                jdbc.update("UPDATE ai_task_runs SET status = 'cancelled', error_code = NULL, updated_at = now() WHERE id = ?", id);
            });
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
            fail(id, "rollback_conflict");
        } catch (Exception e) {
            // 연결 장애는 다음 주기에 재시도한다. 복구 확인 전 완료로 표시하지 않는다.
            jdbc.update("UPDATE ai_task_runs SET error_code = 'rollback_retry_pending', updated_at = now() "
                    + "WHERE id = ? AND status IN ('cancel_requested', 'rolling_back')", id);
        }
    }

    private void fail(String id, String code) {
        jdbc.update("UPDATE ai_task_runs SET status = 'rollback_failed', error_code = ?, updated_at = now() "
                + "WHERE id = ? AND status IN ('cancel_requested', 'rolling_back')", code, id);
    }

    public List<Long> changes(String id, String workspaceId, String userId) {
        return transaction.execute(tx -> {
            requireRollingBack(owned(id, workspaceId, userId, true));
            return jdbc.queryForList("SELECT id FROM ai_task_changes WHERE run_id = ? AND NOT undone ORDER BY id DESC", Long.class, id);
        });
    }

    public List<Map<String, Object>> finalizeEdits(String id, String workspaceId, String userId) {
        return transaction.execute(tx -> {
            requireRollingBack(owned(id, workspaceId, userId, true));
            return jdbc.queryForList("SELECT event_id, document_id, workspace_id, revision, content_hash, created_at::text AS created_at "
                    + "FROM finalize_ai_task_edits(?)", id);
        });
    }

    public void undo(String id, String workspaceId, String userId, long changeId) {
        transaction.executeWithoutResult(tx -> {
            requireRollingBack(owned(id, workspaceId, userId, true));
            Map<String, Object> change = jdbc.queryForMap("SELECT * FROM ai_task_changes WHERE run_id = ? AND id = ?", id, changeId);
            if (Boolean.TRUE.equals(change.get("undone"))) return;
            jdbc.queryForObject("SELECT undo_ai_task_change(?, ?)", Object.class, id, changeId);
            if (change.get("before_value") == null && java.util.List.of("documents", "document_assets").contains(change.get("table_name"))) {
                try {
                    JsonNode value = mapper.readTree(change.get("after_value").toString());
                    String field = change.get("table_name").equals("documents") ? "source_uri" : "storage_key";
                    String key = value.path(field).asText(null);
                    if (key != null && !key.isBlank()) {
                        storage.removeObject(io.minio.RemoveObjectArgs.builder().bucket(storageProperties.getBucket()).object(key).build());
                    }
                } catch (Exception e) {
                    throw new IllegalStateException("생성한 파일을 복구하지 못했습니다.", e);
                }
            }
        });
    }

    private void requireRollingBack(Map<String, Object> row) {
        if (!List.of("cancel_requested", "rolling_back").contains(row.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "취소 중인 작업만 복구할 수 있습니다.");
    }

    private Map<String, Object> owned(String id, String workspaceId, String userId, boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM ai_task_runs WHERE id = ? AND workspace_id = ? AND user_id = ?"
                + (lock ? " FOR UPDATE" : ""), id, workspaceId, userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "작업을 찾을 수 없습니다.");
        return rows.getFirst();
    }
}
