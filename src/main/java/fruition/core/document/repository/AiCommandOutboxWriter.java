package fruition.core.document.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.domain.AiCommandOutbox;
import fruition.core.usage.limit.AiUsageLimiter;
import fruition.core.usage.service.CreditService;
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
    private final CreditService credits;
    private final AiUsageLimiter limits;

    public AiCommandOutboxWriter(AiCommandOutboxRepository repository, ObjectMapper objectMapper,
                                JdbcTemplate jdbcTemplate, EntityManager entityManager, CreditService credits,
                                AiUsageLimiter limits) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.credits = credits;
        this.limits = limits;
    }

    public void begin(String runId, String workspaceId, String userId, String kind) {
        begin(runId, workspaceId, userId, kind, null);
    }

    /**
     * 견적으로 승인·예약하고 견적 기준을 {@code cost_quote}에 남긴다. {@code command}에는 넣지 않는다: command는 발행한
     * payload 그대로여야 AI 취소가 같은 해시로 대조된다. autoIngest면 total에 들어 있는 편입 몫을 함께 남긴다.
     */
    public void beginEstimated(String runId, String workspaceId, String userId,
            fruition.core.usage.service.DocumentCostEstimator.Quote quote, long total, boolean autoIngest) {
        begin(runId, workspaceId, userId, quote.kind(), total);
        jdbcTemplate.update("UPDATE ai_task_runs SET cost_quote = jsonb_build_object('base', ?::bigint, 'provider', ?::text, "
                + "'model', ?::text, 'auto_ingest', ?::boolean, 'estimate', ?::bigint, 'ingest_hold', ?::bigint) WHERE id = ?",
                quote.base(), quote.provider(), quote.model(), autoIngest, total,
                autoIngest ? total - quote.amount() : 0, runId);
    }

    public void beginConvertedIngest(String runId, String parentRunId, String workspaceId, String userId,
            fruition.core.usage.service.DocumentCostEstimator.Quote quote) {
        // 견적 없이 승인된(사전 확인 배포 전) 변환의 자동 편입은 새 편입과 같이 전액 승인을 거친다.
        if (Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM ai_task_runs "
                + "WHERE id=? AND user_id=? AND workspace_id=? AND kind='convert' AND cost_quote IS NULL)",
                Boolean.class, parentRunId, userId, workspaceId))) {
            beginEstimated(runId, workspaceId, userId, quote, quote.amount(), false);
            return;
        }
        entityManager.flush();
        jdbcTemplate.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind) VALUES (?, ?, ?, 'document') "
                + "ON CONFLICT(id) DO NOTHING", runId, workspaceId, userId);
        credits.reserveConvertedIngest(parentRunId, runId, workspaceId, userId, quote.amount());
        beginEstimated(runId, workspaceId, userId, quote, quote.amount(), false);
    }

    private void begin(String runId, String workspaceId, String userId, String kind, Long estimate) {
        // 작업 시작 전에 대기 중이던 사용자 변경을 이번 작업의 변경으로 잘못 기록하지 않는다.
        entityManager.flush();
        int inserted = jdbcTemplate.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(id) DO NOTHING", runId, workspaceId, userId, kind);
        // 새로 등록한 실행만 사용자별 사용 한도를 본다(enqueue가 같은 run으로 begin을 다시 부른다). 넘으면 등록과 함께 롤백된다.
        if (inserted == 1) limits.check(userId, kind);
        // outbox 발행 전에 같은 트랜잭션에서 크레딧을 예약한다. 잔액이 모자라면 작업 등록과 함께 롤백된다(#79).
        if (estimate == null) credits.reserve(runId, userId, kind);
        else credits.reserve(runId, userId, kind, estimate);
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
        limits.check(userId, kind);
        credits.reserve(runId, userId, kind);
        jdbcTemplate.queryForObject("SELECT set_config('app.ai_task_run_id', ?, true)", String.class, runId);
    }

    /** 실행을 끝내고 같은 트랜잭션에서 사용 금액 수집·크레딧 정산 대기열에 넣는다(#78·#79). */
    public void complete(String runId) {
        close(runId, "completed");
    }

    /**
     * 변환을 끝내고 같은 트랜잭션에서 예약을 푼다. 자동 편입을 승인한 변환이면 먼저 편입 몫을 따로 예약해 두어
     * 변환 정산이 그 몫을 풀지 않게 한다. 남은 몫은 자동 편입 등록 뒤 {@link #releaseAutoIngestHold}가 푼다.
     */
    public void completeConvert(String runId) {
        var held = jdbcTemplate.queryForList("SELECT user_id, (cost_quote->>'ingest_hold')::bigint AS hold FROM ai_task_runs "
                + "WHERE id = ? AND status = 'running' AND cost_quote->>'auto_ingest' = 'true'", runId);
        if (!held.isEmpty()) credits.holdAutoIngest(runId, (String) held.getFirst().get("user_id"),
                ((Number) held.getFirst().get("hold")).longValue());
        close(runId, "completed");
        credits.settle(runId);
    }

    public void releaseAutoIngestHold(String convertRunId) {
        credits.releaseAutoIngestHold(convertRunId);
    }

    /** 다시 시도하지 않을 실패로 실행을 닫는다. 실패 전까지의 공급사 호출도 수집·정산한다. */
    public void fail(String runId) {
        close(runId, "failed");
    }

    /** 재시도하지 않을 변환 실패의 예약을 즉시 푼다. 늦은 사용 금액은 수집 대기열이 계속 정산한다. */
    public void failAndSettle(String runId) {
        close(runId, "failed");
        credits.settle(runId);
    }

    private void close(String runId, String status) {
        if (jdbcTemplate.update("UPDATE ai_task_runs SET status = ?, updated_at = now() WHERE id = ? AND status = 'running'",
                status, runId) == 1) {
            jdbcTemplate.update("INSERT INTO usage_collect_queue (run_id) VALUES (?) ON CONFLICT (run_id) DO NOTHING", runId);
        }
    }

    /** core가 기록한 실행 상태(status, error_code). 요청한 workspace·user의 실행만 찾는다. */
    public java.util.Optional<java.util.Map<String, Object>> findRun(String runId, String workspaceId, String userId) {
        return jdbcTemplate.queryForList("SELECT status, error_code FROM ai_task_runs WHERE id = ? AND workspace_id = ? AND user_id = ?",
                runId, workspaceId, userId).stream().findFirst();
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

    /**
     * 이미 등록·예약된 부모 실행에 딸린 command를 outbox에만 저장한다. {@link #enqueue}와 달리 이 runId로
     * 실행 행을 만들거나 크레딧을 예약하지 않는다(PDF 변환 batch는 변환 run 하나가 예약한다).
     */
    public void enqueueJoined(String runId, String topic, String messageKey, String payload) {
        repository.save(new AiCommandOutbox(UUID.randomUUID().toString(), runId, topic, messageKey, payload));
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
