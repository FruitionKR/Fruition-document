package fruition.core.document.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.domain.AiCommandOutbox;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.persistence.EntityManager;

import java.util.UUID;

/** AI command를 현재 DB 트랜잭션의 outbox에 저장한다. */
@Component
public class AiCommandOutboxWriter {

    private final AiCommandOutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    public AiCommandOutboxWriter(AiCommandOutboxRepository repository, ObjectMapper objectMapper,
                                JdbcTemplate jdbcTemplate, EntityManager entityManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
    }

    public void begin(String runId, String workspaceId, String userId, String kind) {
        // 작업 시작 전에 대기 중이던 사용자 변경을 이번 작업의 변경으로 잘못 기록하지 않는다.
        entityManager.flush();
        jdbcTemplate.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(id) DO NOTHING", runId, workspaceId, userId, kind);
        jdbcTemplate.queryForObject("SELECT set_config('app.ai_task_run_id', ?, true)", String.class, runId);
    }

    public void reserve(String runId, String workspaceId, String userId, String kind) {
        if (!runId.matches("[a-zA-Z0-9:_-]{1,120}")) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "작업 ID가 올바르지 않습니다.");
        entityManager.flush();
        int inserted = jdbcTemplate.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(id) DO NOTHING", runId, workspaceId, userId, kind);
        if (inserted != 1) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "이미 사용한 작업 ID입니다.");
        jdbcTemplate.queryForObject("SELECT set_config('app.ai_task_run_id', ?, true)", String.class, runId);
    }

    public void complete(String runId) {
        jdbcTemplate.update("UPDATE ai_task_runs SET status = 'completed', updated_at = now() "
                + "WHERE id = ? AND status = 'running'", runId);
    }

    public boolean active(String runId) {
        return !jdbcTemplate.queryForList("SELECT id FROM ai_task_runs WHERE id = ? AND status IN ('running', 'completed')",
                String.class, runId).isEmpty();
    }

    public boolean join(String runId) {
        var states = jdbcTemplate.queryForList("SELECT status FROM ai_task_runs WHERE id = ? FOR UPDATE", String.class, runId);
        if (states.isEmpty() || !java.util.List.of("running", "completed").contains(states.getFirst())) return false;
        jdbcTemplate.queryForObject("SELECT set_config('app.ai_task_run_id', ?, true)", String.class, runId);
        return true;
    }

    public void enqueue(String runId, String topic, String messageKey, Object command) {
        try {
            var payload = objectMapper.valueToTree(command);
            if (payload.hasNonNull("user_id") && payload.hasNonNull("workspace_id")) {
                begin(runId, payload.path("workspace_id").asText(), payload.path("user_id").asText(), payload.path("kind").asText());
                jdbcTemplate.update("UPDATE ai_task_runs SET command = CAST(? AS jsonb) WHERE id = ?", payload.toString(), runId);
            }
            repository.save(new AiCommandOutbox(
                    UUID.randomUUID().toString(), runId, topic, messageKey,
                    objectMapper.writeValueAsString(command)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AI command 직렬화 실패: runId=" + runId, e);
        }
    }
}
