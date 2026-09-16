package fruition.core.aitask.service;

import fruition.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AiTaskRollbackIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired AiTaskCancellationService cancellation;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @org.springframework.boot.test.mock.mockito.MockBean
    fruition.core.aitask.repository.PipelineTaskCancellationClient pipeline;

    @Test
    void cancellationWaitsForPythonThenRestoresCoreAndRejectsWrongActor() throws Exception {
        String id = "cancel-" + UUID.randomUUID();
        String workspace = "ws-" + id;
        redis.opsForValue().set("authz:role:" + workspace + ":user", "OWNER");
        redis.opsForValue().set("authz:role:" + workspace + ":other", "MEMBER");
        var command = mapper.createObjectNode().put("run_id", id).put("kind", "query")
                .put("workspace_id", workspace).put("user_id", "user");
        cancellation.register(command);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.ai_task_run_id', ?, true)", String.class, id);
            jdbc.update("INSERT INTO chat_sessions(id, workspace_id, user_id, created_at, title) VALUES (?, ?, 'user', now(), 'AI 결과')", id, workspace);
        });
        org.mockito.Mockito.when(pipeline.cancel(id, workspace, "user", command)).thenReturn(
                new fruition.core.aitask.repository.PipelineTaskCancellationClient.TaskStatus(id, "rolling_back", null));
        org.mockito.Mockito.when(pipeline.status(id, workspace, "user")).thenReturn(
                new fruition.core.aitask.repository.PipelineTaskCancellationClient.TaskStatus(id, "rolling_back", null));
        assertThatThrownBy(() -> cancellation.cancel(id, workspace, "other"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(cancellation.cancel(id, workspace, "user").get("status")).isEqualTo("rolling_back");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_sessions WHERE id = ?", Integer.class, id)).isEqualTo(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            for (long change : cancellation.changes(id, workspace, "user")) cancellation.undo(id, workspace, "user", change);
            return null;
        }).when(pipeline).rollbackBackend(id, workspace, "user", command);
        org.mockito.Mockito.when(pipeline.status(id, workspace, "user")).thenReturn(
                new fruition.core.aitask.repository.PipelineTaskCancellationClient.TaskStatus(id, "cancelled", null));
        cancellation.resume();
        assertThat(cancellation.status(id, workspace, "user").get("status")).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_sessions WHERE id = ?", Integer.class, id)).isZero();
        assertThat(redis.opsForList().range("query:events:" + id, 0, -1)).allSatisfy(event -> assertThat(event).contains("query.cancelled"));
        assertThat(cancellation.cancel(id, workspace, "user").get("status")).isEqualTo("cancelled");
        assertThatThrownBy(() -> cancellation.finish(id))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }


    @Test
    void reverseCommittedChangesAndRejectLateWrites() {
        String id = "test-" + UUID.randomUUID();
        jdbc.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind) VALUES (?, 'ws', 'user', 'query')", id);
        // 세션은 이미 존재했다. 작업이 여러 번 저장해도 정확히 처음 값으로 돌아간다.
        jdbc.update("INSERT INTO chat_sessions(id, workspace_id, user_id, created_at, title) VALUES (?, 'ws', 'user', now(), '이전')", id);
        String original = jdbc.queryForObject("SELECT to_jsonb(t)::text FROM chat_sessions t WHERE id = ?", String.class, id);
        var tx = new TransactionTemplate(manager);
        for (String title : new String[]{"중간", "완료"}) {
            tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT set_config('app.ai_task_run_id', ?, true)", String.class, id);
                jdbc.update("UPDATE chat_sessions SET title = ? WHERE id = ?", title, id);
            });
        }
        jdbc.update("UPDATE ai_task_runs SET status = 'rolling_back' WHERE id = ?", id);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.ai_task_run_id', ?, true)", String.class, id);
            jdbc.update("UPDATE chat_sessions SET title = '늦은 결과' WHERE id = ?", id);
        })).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var changes = jdbc.queryForList("SELECT id FROM ai_task_changes WHERE run_id = ? ORDER BY id DESC", Long.class, id);
        assertThat(changes).hasSize(2);
        for (long change : changes) {
            jdbc.queryForObject("SELECT undo_ai_task_change(?, ?)", Object.class, id, change);
            jdbc.queryForObject("SELECT undo_ai_task_change(?, ?)", Object.class, id, change);
        }
        assertThat(jdbc.queryForObject("SELECT to_jsonb(t)::text FROM chat_sessions t WHERE id = ?", String.class, id))
                .isEqualTo(original);
    }

    @Test
    void preserveLaterUserChange() {
        String id = "test-" + UUID.randomUUID();
        jdbc.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind) VALUES (?, 'ws', 'user', 'query')", id);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.ai_task_run_id', ?, true)", String.class, id);
            jdbc.update("INSERT INTO chat_sessions(id, workspace_id, user_id, created_at, title) VALUES (?, 'ws', 'user', now(), 'AI')", id);
        });
        jdbc.update("UPDATE chat_sessions SET title = '사용자 수정' WHERE id = ?", id);
        jdbc.update("UPDATE ai_task_runs SET status = 'rolling_back' WHERE id = ?", id);
        long change = jdbc.queryForObject("SELECT id FROM ai_task_changes WHERE run_id = ?", Long.class, id);
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT undo_ai_task_change(?, ?)", Object.class, id, change))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT title FROM chat_sessions WHERE id = ?", String.class, id)).isEqualTo("사용자 수정");
    }
    @Test
    void restoredBodyUsesNewRevisionAndFinalizationReplaysOneEvent() {
        String id = "edit-" + UUID.randomUUID();
        jdbc.update("INSERT INTO documents(id, byte_size, content_hash, filename, display_name, normalized_filename, "
                + "mime_type, status, uploaded_at, updated_at, user_id, workspace_id, current_content_hash, current_version, document_role, sort_order) "
                + "VALUES (?, 1, 'before', ?, ?, ?, 'text/markdown', 'completed', now(), now(), 'user', 'ws', 'before', 1, 'EDITABLE', 0)", id, id, id, id);
        jdbc.update("INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at) "
                + "VALUES (?, '원래 본문', 'before', 1, now(), now())", id);
        jdbc.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind) VALUES (?, 'ws', 'user', 'agent')", id);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT set_config('app.ai_task_run_id', ?, true)", String.class, id);
            jdbc.update("UPDATE document_edit_states SET markdown = 'AI 본문', content_hash = 'after', revision = 2 WHERE document_id = ?", id);
        });
        jdbc.update("UPDATE ai_task_runs SET status = 'rolling_back' WHERE id = ?", id);
        for (long change : cancellation.changes(id, "ws", "user")) cancellation.undo(id, "ws", "user", change);
        var events = cancellation.finalizeEdits(id, "ws", "user");
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("revision")).isEqualTo(3L);
        assertThat(events.getFirst().get("content_hash")).isEqualTo("before");
        assertThat(jdbc.queryForObject("SELECT markdown FROM document_edit_states WHERE document_id = ?", String.class, id)).isEqualTo("원래 본문");
        assertThat(jdbc.queryForObject("SELECT revision FROM document_edit_states WHERE document_id = ?", Long.class, id)).isEqualTo(3);
        assertThat(cancellation.finalizeEdits(id, "ws", "user")).isEqualTo(events);
    }

}
