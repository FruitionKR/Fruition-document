package fruition.core.query.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.query.domain.QueryRun;
import fruition.core.query.dto.QueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * query run 상태 저장소. 다중 인스턴스에서 어느 인스턴스가 콜백·조회를 받아도
 * 같은 상태를 보도록 Redis에 JSON으로 저장한다. 만료는 Redis TTL이 처리한다.
 */
@Component
public class QueryRunStore {

    private static final Logger log = LoggerFactory.getLogger(QueryRunStore.class);
    private static final String KEY_PREFIX = "query:run:";
    private static final Duration ACTIVE_RUN_TTL = Duration.ofHours(24);
    private static final Duration FINISHED_RUN_TTL = Duration.ofMinutes(10);

    private final Clock clock;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public QueryRunStore(Clock clock, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.clock = clock;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public QueryRun create(String workspaceId, String sessionId, String provider, String model,
                           boolean webSearchEnabled, String question) {
        String requestId = "query_" + UUID.randomUUID();
        QueryRun run = QueryRun.pending(requestId, workspaceId, sessionId, provider, model,
                webSearchEnabled, question, clock.instant());
        write(run, ACTIVE_RUN_TTL);
        log.info("[질의 run 저장] requestId={} sessionId={} status=pending questionLength={}",
                requestId, sessionId, question.length());
        return run;
    }

    public QueryRun create(String workspaceId, String sessionId, String provider, String model, String question) {
        return create(workspaceId, sessionId, provider, model, false, question);
    }

    public QueryRun create(String workspaceId, String sessionId, String question) {
        return create(workspaceId, sessionId, "openai", "gpt-5-nano", false, question);
    }

    public QueryRun createWithId(String requestId, String workspaceId, String sessionId,
                                 String provider, String model, boolean webSearchEnabled, String question) {
        QueryRun run = QueryRun.pending(requestId, workspaceId, sessionId, provider, model,
                webSearchEnabled, question, clock.instant());
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                KEY_PREFIX + requestId, serialize(run), ACTIVE_RUN_TTL))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "이미 사용한 작업 ID입니다.");
        }
        return run;
    }

    public Optional<QueryRun> find(String requestId) {
        Optional<QueryRun> run = Optional.ofNullable(redisTemplate.opsForValue().get(KEY_PREFIX + requestId))
                .map(this::deserialize);
        log.debug("[질의 run 조회] requestId={} result={}", requestId, run.isPresent() ? "hit" : "miss");
        return run;
    }

    public void markRunning(String requestId) {
        update(requestId, run -> {
            log.info("[질의 run 상태 변경] requestId={} {}->running", requestId, run.status());
            return run.running();
        }, ACTIVE_RUN_TTL);
    }

    public boolean markCompleted(String requestId, QueryResponse result) {
        return finish(requestId, run -> {
            log.info("[질의 run 상태 변경] requestId={} {}->completed", requestId, run.status());
            return run.completed(result, clock.instant());
        });
    }

    public boolean markFailed(String requestId, String errorMessage) {
        return finish(requestId, run -> {
            log.warn("[질의 run 상태 변경] requestId={} {}->failed error={}",
                    requestId, run.status(), errorMessage);
            return run.failed(errorMessage, clock.instant());
        });
    }

    public boolean markCancelled(String requestId) {
        return compareAndSet(requestId, run -> run.cancelled(clock.instant()), FINISHED_RUN_TTL, true);
    }

    private boolean finish(String requestId, UnaryOperator<QueryRun> mutation) {
        return compareAndSet(requestId, mutation, FINISHED_RUN_TTL, false);
    }

    private void update(String requestId, UnaryOperator<QueryRun> mutation, Duration ttl) {
        compareAndSet(requestId, mutation, ttl, false);
    }

    private boolean compareAndSet(String requestId, UnaryOperator<QueryRun> mutation, Duration ttl, boolean cancelling) {
        String key = KEY_PREFIX + requestId;
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end "
                + "redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]); return 1", Long.class);
        for (int attempt = 0; attempt < 10; attempt++) {
            String current = redisTemplate.opsForValue().get(key);
            if (current == null) return false;
            QueryRun run = deserialize(current);
            if (run.status() == fruition.core.query.domain.QueryRunStatus.CANCELLED
                    || (!cancelling && run.isFinished())) return false;
            Long changed = redisTemplate.execute(script, java.util.List.of(key), current,
                    serialize(mutation.apply(run)), Long.toString(ttl.toSeconds()));
            if (Long.valueOf(1).equals(changed)) return true;
        }
        throw new IllegalStateException("질의 상태가 계속 변경되어 재시도가 필요합니다.");
    }

    private void write(QueryRun run, Duration ttl) {
        redisTemplate.opsForValue().set(KEY_PREFIX + run.requestId(), serialize(run), ttl);
        log.debug("[질의 run Redis 저장 완료] requestId={} status={} ttlSeconds={}",
                run.requestId(), run.status(), ttl.toSeconds());
    }

    private String serialize(QueryRun run) {
        try {
            return objectMapper.writeValueAsString(run);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("query run 직렬화 실패: " + run.requestId(), e);
        }
    }

    private QueryRun deserialize(String json) {
        try {
            return objectMapper.readValue(json, QueryRun.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("query run 역직렬화 실패", e);
        }
    }
}
