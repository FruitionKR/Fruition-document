package fruition.core.usage.limit;

import fruition.core.usage.service.CreditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/**
 * 사용자별 AI 사용 한도(외부 AI 비용 상한). 워크스페이스와 무관하게 사용자 단위로 센다.
 *
 * <p>두 관문({@code AiCommandOutboxWriter.begin/reserve}, {@code AiTaskCancellationService.register})이
 * {@code ai_task_runs} 행을 새로 넣은 같은 트랜잭션에서 {@link #check}를 부른다. 사용자별 트랜잭션 advisory lock으로
 * 판정을 직렬화하므로 같은 사용자의 동시 요청이 마지막 한 자리를 함께 통과하지 못한다. 거절하면 예외로 등록이 롤백된다.
 *
 * <ul>
 *   <li>동시 실행: 끝나지 않은 실행(running·cancel_requested·rolling_back) 중 {@code stale-minutes} 안에 갱신된 것만 센다.
 *       종료 신호 없이 멈춘 실행이 자리를 영원히 막지 않는다.</li>
 *   <li>하루 비용: 오늘(KST) {@code usage_charges.cost_usd_micro} 합계 + 진행 중 실행의 예상 비용 중 아직 청구되지 않은 몫
 *       + 이번 실행의 예상 비용이 한도를 넘으면 거절한다. 예상 비용은 크레딧 예약의 kind별 상한(milli-KRW)을 최신 환율로
 *       USD로 바꾼 값이다(마진·부가세가 포함된 값이라 실제 원가보다 크게 잡힌다).</li>
 *   <li>하루 토큰: 오늘 수집된 입력+출력 토큰이 한도에 닿으면 거절한다. 실행 전에는 토큰을 알 수 없어 사후 차단이다.</li>
 * </ul>
 * 모델을 부르지 않는 복원(restore_*)과 사용자가 없는 시스템 command는 세지 않는다.
 */
@Component
public class AiUsageLimiter {

    private static final String LOCK_NAMESPACE = "ai_usage_limit";

    private final JdbcTemplate jdbc;
    private final CreditService credits;
    private final AiUsageLimitPolicy.Limits limits;
    private final int staleMinutes;

    public AiUsageLimiter(JdbcTemplate jdbc, CreditService credits,
                          @Value("${app.ai-limit.enabled:true}") boolean enabled,
                          @Value("${app.ai-limit.daily-cost-usd:5.00}") BigDecimal dailyCostUsd,
                          @Value("${app.ai-limit.daily-tokens:2000000}") long dailyTokens,
                          @Value("${app.ai-limit.concurrent:3}") int concurrent,
                          @Value("${app.ai-limit.stale-minutes:60}") int staleMinutes) {
        this.jdbc = jdbc;
        this.credits = credits;
        this.limits = new AiUsageLimitPolicy.Limits(enabled,
                dailyCostUsd.movePointRight(6).setScale(0, RoundingMode.DOWN).longValueExact(), dailyTokens, concurrent);
        this.staleMinutes = staleMinutes;
    }

    /**
     * 방금 등록한 실행(runId)을 빼고 한도를 판정한다. 넘으면 {@link AiUsageLimitExceededException}을 던진다.
     * 호출자 트랜잭션 안에서만 부른다. 잠금은 그 트랜잭션이 끝날 때 풀린다.
     */
    public void check(String runId, String userId, String kind) {
        if (!limits.enabled() || userId == null || userId.isBlank() || CreditService.isFree(kind)) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("AI 사용 한도는 실행 등록 트랜잭션 안에서 판정해야 합니다: runId=" + runId);
        }
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))", rs -> { }, LOCK_NAMESPACE, userId);

        long krwPerUsdMilli = krwPerUsdMilli();
        int active = 0;
        long inFlight = 0;
        for (Map<String, Object> run : jdbc.queryForList("""
                SELECT r.kind, coalesce((SELECT sum(c.cost_usd_micro) FROM usage_charges c WHERE c.run_id = r.id), 0) AS spent
                FROM ai_task_runs r
                WHERE r.user_id = ? AND r.id <> ? AND r.status IN ('running', 'cancel_requested', 'rolling_back')
                  AND r.updated_at > now() - make_interval(mins => ?) AND r.kind NOT LIKE 'restore\\_%'
                """, userId, runId, staleMinutes)) {
            active++;
            long spent = ((Number) run.get("spent")).longValue();
            inFlight += Math.max(0, estimateUsdMicro((String) run.get("kind"), krwPerUsdMilli) - spent);
        }

        Instant now = Instant.now();
        Map<String, Object> today = jdbc.queryForMap("""
                SELECT coalesce(sum(cost_usd_micro), 0) AS cost,
                       coalesce(sum(coalesce(input_tokens, 0) + coalesce(output_tokens, 0)), 0) AS tokens
                FROM usage_charges WHERE user_id = ? AND started_at >= ? AND started_at < ?
                """, userId, Timestamp.from(AiUsageLimitPolicy.dayStart(now)),
                Timestamp.from(AiUsageLimitPolicy.nextReset(now)));
        var usage = new AiUsageLimitPolicy.Usage(active, ((Number) today.get("cost")).longValue(), inFlight,
                ((Number) today.get("tokens")).longValue());

        AiUsageLimitPolicy.evaluate(limits, usage, estimateUsdMicro(kind, krwPerUsdMilli)).ifPresent(type -> {
            throw new AiUsageLimitExceededException(type, type.daily() ? AiUsageLimitPolicy.nextReset(now) : null,
                    type.message(limits.concurrent()));
        });
    }

    /** kind 실행 한 건의 예상 비용(USD micro). */
    public long estimateUsdMicro(String kind) {
        return estimateUsdMicro(kind, krwPerUsdMilli());
    }

    /** 예약 상한(milli-KRW)을 USD micro로: KRW = milli/1000, USD = KRW/환율, micro = USD×10^6. */
    private long estimateUsdMicro(String kind, long krwPerUsdMilli) {
        return Math.ceilDiv(credits.estimate(kind) * 1_000_000L, krwPerUsdMilli);
    }

    /** 최신 USD→KRW 환율 ×1000(정수). 환율 행은 V72가 기본값을 넣는다. */
    private long krwPerUsdMilli() {
        BigDecimal rate = jdbc.queryForObject("SELECT krw_per_usd FROM fx_rates WHERE effective_from <= now() "
                + "ORDER BY effective_from DESC LIMIT 1", BigDecimal.class);
        return rate.movePointRight(3).setScale(0, RoundingMode.UP).longValueExact();
    }
}
