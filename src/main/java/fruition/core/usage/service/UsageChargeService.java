package fruition.core.usage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import fruition.core.aitask.service.AiTaskCancellationService;
import fruition.shared.http.PipelineClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/**
 * 호출 단위 AI 사용 금액(#78). AI 원장의 호출 1건을 {@code usage_charges} 1행으로 옮기며 금액을 계산한다.
 *
 * <ul>
 *   <li>수집: 실행이 끝나면 run_id를 대기열에 넣고, worker가 AI {@code GET /internal/model-usage/calls?run_id=}로
 *       호출을 가져온다. 늦게 커밋된 호출은 1시간 넘게 지난 종료 시각 구간을 하루치씩 다시 조회해 채운다.</li>
 *   <li>같은 호출(call_id)은 여러 번 수집해도 한 행이다. 금액이 확정된({@code charged}) 행은 다시 계산하지 않아
 *       이후 단가가 바뀌어도 과거 청구가 변하지 않는다.</li>
 *   <li>단가·환율·정책 버전은 호출 {@code started_at}에 적용 중인 행을 고른다.</li>
 *   <li>단가·환율·정책이 없으면 {@code unpriced}(금액 비움), 토큰을 모르는 호출은 {@code needs_review}(금액 0)다.</li>
 * </ul>
 */
@Service
public class UsageChargeService {

    /** ai-svc가 HTTP 동기 호출의 사용량을 귀속할 run_id를 받는 헤더. 본문·쿼리로 보내면 버려지거나 거부된다. */
    public static final String RUN_ID_HEADER = "X-Request-Id";

    private static final Logger log = LoggerFactory.getLogger(UsageChargeService.class);
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration RECONCILE_DELAY = Duration.ofHours(1);
    private static final Duration RECONCILE_CHUNK = Duration.ofDays(1);
    private static final Duration MAX_PERIOD = Duration.ofDays(366);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AiTaskCancellationService runs;
    private final CreditService credits;
    private final ObjectMapper mapper;
    private final RestClient client;
    private final String endpoint;

    public UsageChargeService(JdbcTemplate jdbc, PlatformTransactionManager manager, AiTaskCancellationService runs,
                              CreditService credits,
                              ObjectMapper mapper, PipelineClientFactory factory,
                              @Value("${app.usage-charge.calls-endpoint}") String endpoint) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.runs = runs;
        this.credits = credits;
        this.mapper = mapper;
        this.client = factory.restClient(30);
        this.endpoint = endpoint;
    }

    /** 실행이 끝난 run_id를 수집 대기열에 넣는다. 호출자 트랜잭션이 있으면 함께 커밋된 뒤에 수집된다. */
    public void enqueue(String runId) {
        jdbc.update("INSERT INTO usage_collect_queue (run_id) VALUES (?) ON CONFLICT (run_id) DO NOTHING", runId);
    }

    /**
     * 실행 ID가 없는 동기 AI 호출을 {@code ai_task_runs}에 실행으로 등록하고 그 ID로 호출한다.
     * AI는 이 ID를 run_id로 원장에 남긴다. 호출이 실패하면 실행을 {@code failed}로 닫는다. 실패해도 공급사 호출은
     * 일어났을 수 있어 항상 수집 대기열에 넣는다.
     */
    public <T> T track(String kind, String workspaceId, String userId, Function<String, T> call) {
        String runId = startRun(kind, workspaceId, userId);
        try {
            T result = call.apply(runId);
            runs.finish(runId);
            return result;
        } catch (RuntimeException e) {
            failRun(runId);
            throw e;
        } finally {
            enqueue(runId);
        }
    }

    /** 실패한 동기 실행을 닫는다. 취소 중이거나 이미 끝난 실행은 그대로 둔다. */
    public void failRun(String runId) {
        jdbc.update("UPDATE ai_task_runs SET status = 'failed', updated_at = now() WHERE id = ? AND status = 'running'", runId);
    }

    /**
     * 실행 ID가 없는 AI 사용을 {@code ai_task_runs}에 등록하고 크레딧을 예약한다. 잔액이 모자라면(enforce)
     * {@link CreditService.InsufficientCreditException}을 던지고 아무것도 등록하지 않는다.
     */
    public String startRun(String kind, String workspaceId, String userId) {
        String runId = kind + ":" + UUID.randomUUID();
        runs.register(mapper.createObjectNode().put("run_id", runId).put("kind", kind)
                .put("workspace_id", workspaceId).put("user_id", userId));
        return runId;
    }

    /** {@link #startRun}으로 시작한 실행을 끝내고 사용량 수집·정산을 대기열에 넣는다. */
    public void endRun(String runId) {
        jdbc.update("UPDATE ai_task_runs SET status = 'completed', updated_at = now() WHERE id = ? AND status = 'running'",
                runId);
        enqueue(runId);
    }

    /** 대기열의 run을 하나씩 선점해 수집한다. AI 장애면 시도 횟수만큼 늦춰 다시 시도하고, 끝내 실패하면 대사에 맡긴다. */
    @Scheduled(fixedDelayString = "${app.usage-charge.collect-interval-ms:5000}")
    public void collectPending() {
        for (int i = 0; i < 20; i++) {
            // 선점은 UPDATE 한 번으로 커밋한다. AI를 부르는 동안 행 잠금을 쥐지 않는다.
            var claimed = jdbc.queryForList("""
                    UPDATE usage_collect_queue SET attempts = attempts + 1, available_at = now() + interval '5 minutes'
                    WHERE run_id = (SELECT run_id FROM usage_collect_queue WHERE available_at <= now()
                                    ORDER BY available_at LIMIT 1 FOR UPDATE SKIP LOCKED)
                    RETURNING run_id, attempts
                    """);
            if (claimed.isEmpty()) return;
            String runId = (String) claimed.getFirst().get("run_id");
            int attempts = ((Number) claimed.getFirst().get("attempts")).intValue();
            try {
                collectRun(runId);
                jdbc.update("DELETE FROM usage_collect_queue WHERE run_id = ?", runId);
            } catch (RuntimeException e) {
                if (attempts >= MAX_ATTEMPTS) {
                    log.warn("[사용 금액 수집 포기] runId={} attempts={} 종료 시각 대사가 채운다. error={}", runId, attempts, e.toString());
                    jdbc.update("DELETE FROM usage_collect_queue WHERE run_id = ?", runId);
                } else {
                    log.warn("[사용 금액 수집 실패] runId={} attempts={} error={}", runId, attempts, e.toString());
                    jdbc.update("UPDATE usage_collect_queue SET available_at = now() + make_interval(mins => ?) "
                            + "WHERE run_id = ?", attempts * attempts, runId);
                }
                return;
            }
        }
    }

    /** 실행 하나의 호출을 가져와 청구 행을 만들고 크레딧을 정산한다. 호출이 없어도 끝난 실행의 예약은 푼다. */
    public void collectRun(String runId) {
        Set<String> runIds = recordAll(fetch(Map.of("run_id", runId)));
        runIds.remove(runId);
        credits.settle(runId);
        settleQuietly(runIds);
    }

    /**
     * 진행 중인 실행의 지금까지 호출을 청구 행으로 만들고 차감한 뒤 다음 구간을 다시 예약한다(실시간 전사).
     * enforce에서 잔액이 모자라면 {@link CreditService.InsufficientCreditException}을 던진다.
     */
    public void renewRun(String runId, String userId, String kind, int seq) {
        recordAll(fetch(Map.of("run_id", runId)));
        credits.renew(runId, userId, kind, seq);
    }

    /** 종료 시각이 [from, to)인 호출을 가져와 빠진 청구 행을 채우고 정산할 run_id를 돌려준다. */
    public Set<String> collectFinished(Instant from, Instant to) {
        return recordAll(fetch(Map.of("finished_from", from.toString(), "finished_to", to.toString())));
    }

    /**
     * 하루 단위 대사. 커서부터 1시간 전까지를 하루치씩 조회한다. 커서 행 잠금으로 여러 Pod가 같은 구간을 동시에 돌지 않고,
     * 조회가 실패하면 커서를 넘기지 않아 다음 주기에 같은 구간을 다시 본다. 청구 행 기록과 커서 갱신만 한 트랜잭션에
     * 두고, 크레딧 정산은 커밋한 뒤 실행별로 한다. 정산 중 사용자 계정 행을 구간 내내 잠그지 않는다.
     */
    @Scheduled(initialDelay = 60_000, fixedDelayString = "${app.usage-charge.reconcile-interval-ms:86400000}")
    public void reconcile() {
        try {
            Chunk chunk;
            do {
                chunk = transaction.execute(tx -> reconcileNextChunk());
                if (chunk == null) return;
                settleQuietly(chunk.runIds());
            } while (chunk.more());  // 밀린 구간을 모두 따라잡는다.
        } catch (RuntimeException e) {
            log.warn("[사용 금액 대사 실패] error={}", e.toString());
        }
    }

    private record Chunk(Set<String> runIds, boolean more) {
    }

    private Chunk reconcileNextChunk() {
        var cursor = jdbc.queryForList("SELECT reconciled_to FROM usage_reconcile_cursor FOR UPDATE SKIP LOCKED",
                Timestamp.class);
        if (cursor.isEmpty()) return null;
        Instant from = cursor.getFirst().toInstant();
        Instant limit = Instant.now().minus(RECONCILE_DELAY);
        Instant to = from.plus(RECONCILE_CHUNK).isBefore(limit) ? from.plus(RECONCILE_CHUNK) : limit;
        if (!to.isAfter(from)) return null;
        Set<String> runIds = collectFinished(from, to);
        jdbc.update("UPDATE usage_reconcile_cursor SET reconciled_to = ?", Timestamp.from(to));
        return new Chunk(runIds, to.isBefore(limit));
    }

    /** 실행별로 정산한다. 한 실행이 실패해도 나머지는 정산하고, 실패한 실행은 다음 수집·정리 작업이 다시 정산한다. */
    private void settleQuietly(Set<String> runIds) {
        for (String runId : runIds) {
            try {
                credits.settle(runId);
            } catch (RuntimeException e) {
                log.warn("[크레딧 정산 실패] runId={} error={}", runId, e.toString());
            }
        }
    }

    private JsonNode fetch(Map<String, String> params) {
        var uri = UriComponentsBuilder.fromUriString(endpoint);
        params.forEach(uri::queryParam);
        JsonNode body = client.get().uri(uri.build().encode().toUri()).retrieve().body(JsonNode.class);
        if (body == null || !body.path("calls").isArray()) {
            throw new IllegalStateException("AI 호출 사용량 응답이 올바르지 않습니다.");
        }
        return body.path("calls");
    }

    /**
     * 청구 행을 만들고 정산할 run_id를 돌려준다. 형식이 잘못된 호출 1건은 로그를 남기고 건너뛰어 나머지 호출과 대사 커서가
     * 멈추지 않게 한다.
     */
    private Set<String> recordAll(JsonNode calls) {
        Set<String> runIds = new TreeSet<>();
        for (JsonNode call : calls) {
            try {
                record(call);
            } catch (DateTimeParseException | IllegalArgumentException e) {
                // ponytail: 값 해석 오류만 건너뛴다. SQL 오류는 트랜잭션이 중단되므로 청크째 다시 시도한다.
                log.error("[AI 사용량 호출 건너뜀] callId={} runId={} error={}", call.path("id").asText(),
                        call.path("run_id").asText(), e.toString());
                continue;
            }
            if (call.path("run_id").isTextual()) runIds.add(call.path("run_id").asText());
        }
        return runIds;
    }

    /**
     * AI 원장 호출 1건을 청구 행으로 만든다. 아직 끝나지 않은 호출은 건너뛰고 종료 시각 대사가 가져온다.
     * 청구 사용자는 실행을 예약한 사용자({@code ai_task_runs.user_id})다. 실행 행이 없을 때만 AI가 남긴 user_id를 쓴다
     * (AI가 사용자를 몰라 {@code unattributed}로 남긴 호출도 실행자에게 청구된다).
     */
    private void record(JsonNode call) {
        String callStatus = call.path("status").asText();
        if ("started".equals(callStatus)) return;
        String callId = call.path("id").asText();
        if (callId.isBlank()) throw new IllegalArgumentException("호출 id가 없습니다.");
        Timestamp startedAt = Timestamp.from(Instant.parse(call.path("started_at").asText()));
        String provider = call.path("provider").asText(null);
        // 단가는 실제 응답 모델 기준이다. 실패해 응답 모델이 없으면 요청 모델을 남긴다.
        String model = call.path("model").isTextual() ? call.path("model").asText() : call.path("requested_model").asText(null);
        Long input = count(call, "input_tokens");
        Long cached = count(call, "cached_input_tokens");
        Long creation = count(call, "cache_creation_tokens");
        Long output = count(call, "output_tokens");
        BigDecimal audioSeconds = call.path("audio_seconds").isNumber() ? call.path("audio_seconds").decimalValue() : null;
        Long ttsCharacters = count(call, "input_characters");

        String status;
        Timestamp priceFrom = null, fxFrom = null, policyFrom = null;
        Long cost = null, charge = null;
        if (!"succeeded".equals(callStatus)) {
            status = "needs_review";
            cost = 0L;
            charge = 0L;
            log.warn("[AI 사용량 확인 필요] callId={} runId={} status={}", callId, call.path("run_id").asText(), callStatus);
        } else {
            var prices = jdbc.query("SELECT * FROM ai_model_prices WHERE provider = ? AND model = ? AND effective_from <= ? "
                    + "ORDER BY effective_from DESC LIMIT 1", (rs, i) -> Map.entry(rs.getTimestamp("effective_from"),
                    UsagePricing.Price.from(rs)), provider, model, startedAt);
            var fx = jdbc.queryForList("SELECT * FROM fx_rates WHERE effective_from <= ? ORDER BY effective_from DESC LIMIT 1",
                    startedAt);
            var policy = jdbc.queryForList("SELECT * FROM pricing_policies WHERE effective_from <= ? "
                    + "ORDER BY effective_from DESC LIMIT 1", startedAt);
            BigDecimal costUsd = prices.isEmpty() ? null : UsagePricing.callCostUsd(prices.getFirst().getValue(),
                    zero(input), zero(cached), zero(creation), zero(output), audioSeconds, zero(ttsCharacters));
            if (costUsd != null) {
                priceFrom = prices.getFirst().getKey();
                cost = UsagePricing.costUsdMicro(costUsd);
            }
            if (costUsd != null && !fx.isEmpty() && !policy.isEmpty()) {
                status = "charged";
                fxFrom = (Timestamp) fx.getFirst().get("effective_from");
                policyFrom = (Timestamp) policy.getFirst().get("effective_from");
                charge = UsagePricing.chargeKrwMilli(costUsd, (BigDecimal) fx.getFirst().get("krw_per_usd"),
                        ((Number) policy.getFirst().get("margin_bp")).intValue(),
                        ((Number) policy.getFirst().get("vat_bp")).intValue());
            } else {
                status = "unpriced";
                log.warn("[AI 단가 없음] callId={} provider={} model={} price={} fx={} policy={}", callId, provider, model,
                        costUsd != null, !fx.isEmpty(), !policy.isEmpty());
            }
        }
        // 확정된 청구는 다시 계산하지 않는다. 단가 없음·확인 필요 행은 단가를 넣은 뒤 다시 수집하면 갱신된다.
        jdbc.update("""
                INSERT INTO usage_charges (call_id, user_id, workspace_id, run_id, kind, provider, model, call_status,
                    input_tokens, cached_input_tokens, cache_creation_tokens, output_tokens, reasoning_tokens,
                    audio_seconds, tts_characters, price_effective_from, fx_effective_from, policy_effective_from,
                    cost_usd_micro, charge_krw_milli, status, started_at)
                VALUES (?, coalesce((SELECT nullif(user_id, '') FROM ai_task_runs WHERE id = ?), ?),
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (call_id) DO UPDATE SET user_id = EXCLUDED.user_id, workspace_id = EXCLUDED.workspace_id,
                    run_id = EXCLUDED.run_id, kind = EXCLUDED.kind, provider = EXCLUDED.provider, model = EXCLUDED.model,
                    call_status = EXCLUDED.call_status, input_tokens = EXCLUDED.input_tokens,
                    cached_input_tokens = EXCLUDED.cached_input_tokens, cache_creation_tokens = EXCLUDED.cache_creation_tokens,
                    output_tokens = EXCLUDED.output_tokens, reasoning_tokens = EXCLUDED.reasoning_tokens,
                    audio_seconds = EXCLUDED.audio_seconds, tts_characters = EXCLUDED.tts_characters,
                    price_effective_from = EXCLUDED.price_effective_from, fx_effective_from = EXCLUDED.fx_effective_from,
                    policy_effective_from = EXCLUDED.policy_effective_from, cost_usd_micro = EXCLUDED.cost_usd_micro,
                    charge_krw_milli = EXCLUDED.charge_krw_milli, status = EXCLUDED.status, started_at = EXCLUDED.started_at
                WHERE usage_charges.status <> 'charged'
                """, callId, call.path("run_id").asText(null), call.path("user_id").asText("unattributed"),
                call.path("workspace_id").asText(null),
                call.path("run_id").asText(null), call.path("kind").asText(null), provider, model, callStatus,
                input, cached, creation, output, count(call, "reasoning_tokens"), audioSeconds, ttsCharacters,
                priceFrom, fxFrom, policyFrom, cost, charge, status, startedAt);
    }

    private static Long count(JsonNode call, String field) {
        return call.path(field).isNumber() ? call.path(field).asLong() : null;
    }

    private static long zero(Long value) {
        return value == null ? 0 : value;
    }

    /** 사용자 본인의 기간 [from, to) 청구 합계. 워크스페이스를 가로질러 합친다. */
    public ChargeSummary summary(String userId, Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회 시작은 종료보다 앞서야 합니다.");
        }
        if (Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회 기간은 366일 이하여야 합니다.");
        }
        List<ModelCharge> models = jdbc.query("""
                SELECT provider, model, count(*) AS calls,
                       coalesce(sum(input_tokens), 0) AS input_tokens,
                       coalesce(sum(cached_input_tokens), 0) AS cached_input_tokens,
                       coalesce(sum(cache_creation_tokens), 0) AS cache_creation_tokens,
                       coalesce(sum(output_tokens), 0) AS output_tokens,
                       coalesce(sum(reasoning_tokens), 0) AS reasoning_tokens,
                       coalesce(sum(audio_seconds), 0) AS audio_seconds,
                       coalesce(sum(tts_characters), 0) AS tts_characters,
                       coalesce(sum(charge_krw_milli), 0) AS charge_krw_milli,
                       count(*) FILTER (WHERE status = 'unpriced') AS unpriced_calls,
                       count(*) FILTER (WHERE status = 'needs_review') AS needs_review_calls
                FROM usage_charges WHERE user_id = ? AND started_at >= ? AND started_at < ?
                GROUP BY provider, model ORDER BY provider, model
                """, (rs, i) -> new ModelCharge(rs.getString("provider"), rs.getString("model"), rs.getLong("calls"),
                rs.getLong("input_tokens"), rs.getLong("cached_input_tokens"), rs.getLong("cache_creation_tokens"),
                rs.getLong("output_tokens"), rs.getLong("reasoning_tokens"), rs.getBigDecimal("audio_seconds"),
                rs.getLong("tts_characters"), rs.getLong("charge_krw_milli"), rs.getLong("unpriced_calls"),
                rs.getLong("needs_review_calls")), userId, Timestamp.from(from), Timestamp.from(to));
        return new ChargeSummary(userId, from, to, "KRW",
                models.stream().mapToLong(ModelCharge::chargeKrwMilli).sum(),
                models.stream().mapToLong(ModelCharge::unpricedCalls).sum(),
                models.stream().mapToLong(ModelCharge::needsReviewCalls).sum(), models);
    }

    /** 금액은 milli-KRW 정수다(부가세 포함). 단가 없음 호출은 합계에 들어가지 않고 건수로만 드러난다. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChargeSummary(String userId, Instant fromAt, Instant toAt, String currency, long chargeKrwMilli,
                                long unpricedCalls, long needsReviewCalls, List<ModelCharge> models) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ModelCharge(String provider, String model, long calls, long inputTokens, long cachedInputTokens,
                              long cacheCreationTokens, long outputTokens, long reasoningTokens, BigDecimal audioSeconds,
                              long ttsCharacters, long chargeKrwMilli, long unpricedCalls, long needsReviewCalls) {
    }
}
