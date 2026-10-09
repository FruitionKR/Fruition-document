package fruition.core.usage.service;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 사용자 선불 크레딧(#79). 금액은 milli-KRW 정수이고 원장 {@code credit_entries}는 추가만 한다.
 *
 * <ul>
 *   <li>사전 승인: AI 요청 전에 계정 행을 잠그고 kind별 예상 상한을 예약한다. {@code app.billing.enforce}가 true일 때만
 *       가용 잔액(잔액 − 예약)이 상한보다 작거나 잔액이 음수면 402로 거절한다. false면 기록만 한다.</li>
 *   <li>정산: 실행의 청구 행({@code usage_charges}) 합계를 차감하고 남은 예약을 푼다. 실제 금액이 예약보다 크면
 *       그대로 차감해 잔액이 음수가 될 수 있다.</li>
 *   <li>멱등: 원장 키가 run_id 기반이라 같은 실행을 여러 번 정산해도 같은 금액은 한 번만 차감한다.</li>
 * </ul>
 */
@Service
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Environment environment;
    private final boolean enforce;
    private final long defaultEstimate;
    private final int staleHours;

    public CreditService(JdbcTemplate jdbc, PlatformTransactionManager manager, Environment environment,
                         @Value("${app.billing.enforce:false}") boolean enforce,
                         @Value("${app.billing.default-estimate-krw-milli:500000}") long defaultEstimate,
                         @Value("${app.billing.stale-reservation-hours:48}") int staleHours) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.environment = environment;
        this.enforce = enforce;
        this.defaultEstimate = defaultEstimate;
        this.staleHours = staleHours;
    }

    /**
     * AI 요청 전에 kind별 예상 상한을 예약한다. 호출자 트랜잭션이 있으면 그 안에서 계정 행을 잠근다.
     * 같은 실행은 한 번만 예약한다. 사용자가 없는 시스템 작업은 예약하지 않는다(Fruition 부담).
     */
    public void reserve(String runId, String userId, String kind) {
        if (userId == null || userId.isBlank()) return;
        long estimate = environment.getProperty("app.billing.estimate." + kind, Long.class, defaultEstimate);
        transaction.executeWithoutResult(tx -> {
            Map<String, Object> account = lock(userId);
            if (!jdbc.queryForList("SELECT id FROM credit_entries WHERE idempotency_key = ?", Long.class,
                    "reserve:" + runId).isEmpty()) return;
            long balance = ((Number) account.get("balance")).longValue();
            long reserved = ((Number) account.get("reserved")).longValue();
            if (enforce && (balance < 0 || balance - reserved < estimate)) {
                throw new InsufficientCreditException();
            }
            if (append(userId, "reserve", estimate, runId, "reserve:" + runId, kind)) {
                jdbc.update("UPDATE credit_accounts SET reserved = reserved + ?, updated_at = now() WHERE user_id = ?",
                        estimate, userId);
            }
        });
    }

    /**
     * 실행의 청구 합계를 차감하고 남은 예약을 푼다. 차감 키는 그때까지의 누적 청구액이라 같은 합계로 다시 불러도
     * 한 번만 차감하고, 늦게 수집된 호출은 늘어난 만큼만 더 차감한다.
     */
    public void settle(String runId) {
        transaction.executeWithoutResult(tx -> {
            Map<String, Long> totals = new TreeMap<>();
            jdbc.queryForList("SELECT user_id, coalesce(sum(charge_krw_milli), 0) AS total FROM usage_charges "
                    + "WHERE run_id = ? GROUP BY user_id", runId)
                    .forEach(row -> totals.put((String) row.get("user_id"), ((Number) row.get("total")).longValue()));
            jdbc.queryForList("SELECT DISTINCT user_id FROM credit_entries WHERE run_id = ? AND type = 'reserve'",
                    String.class, runId).forEach(user -> totals.putIfAbsent(user, 0L));
            // 잠금 순서를 user_id 순으로 고정해 정산끼리 교착하지 않는다.
            totals.forEach((userId, total) -> {
                lock(userId);
                Long charged = jdbc.queryForObject("SELECT -coalesce(sum(amount), 0) FROM credit_entries "
                        + "WHERE run_id = ? AND user_id = ? AND type = 'charge'", Long.class, runId, userId);
                long delta = total - charged;
                if (delta > 0 && append(userId, "charge", -delta, runId, "charge:" + runId + ":" + userId + ":" + total, null)) {
                    jdbc.update("UPDATE credit_accounts SET balance = balance - ?, updated_at = now() WHERE user_id = ?",
                            delta, userId);
                }
                Long open = jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM credit_entries "
                        + "WHERE run_id = ? AND user_id = ? AND type IN ('reserve', 'release')", Long.class, runId, userId);
                if (open > 0 && append(userId, "release", -open, runId, "release:" + runId, null)) {
                    jdbc.update("UPDATE credit_accounts SET reserved = reserved - ?, updated_at = now() WHERE user_id = ?",
                            open, userId);
                }
            });
        });
    }

    /**
     * 종료 신호 없이 남은 예약을 정리한다. 하루 단위 대사가 호출을 채울 시간을 둔 뒤 그때까지의 청구로 정산한다.
     * 여러 Pod가 같은 실행을 골라도 원장 키가 같아 한 번만 반영된다.
     */
    @Scheduled(initialDelay = 120_000, fixedDelayString = "${app.billing.release-interval-ms:3600000}")
    public void releaseStale() {
        for (String runId : jdbc.queryForList("""
                SELECT r.run_id FROM credit_entries r
                WHERE r.type = 'reserve' AND r.created_at < now() - make_interval(hours => ?)
                  AND NOT EXISTS (SELECT 1 FROM credit_entries e WHERE e.idempotency_key = 'release:' || r.run_id)
                ORDER BY r.created_at LIMIT 100
                """, String.class, staleHours)) {
            try {
                settle(runId);
            } catch (RuntimeException e) {
                log.warn("[크레딧 예약 정리 실패] runId={} error={}", runId, e.toString());
            }
        }
    }

    /** 본인의 잔액·예약과 최근 원장 50건. */
    public Credits credits(String userId) {
        var accounts = jdbc.queryForList("SELECT balance, reserved FROM credit_accounts WHERE user_id = ?", userId);
        long balance = accounts.isEmpty() ? 0 : ((Number) accounts.getFirst().get("balance")).longValue();
        long reserved = accounts.isEmpty() ? 0 : ((Number) accounts.getFirst().get("reserved")).longValue();
        List<Entry> entries = jdbc.query("SELECT type, amount, run_id, reason, created_at FROM credit_entries "
                + "WHERE user_id = ? ORDER BY id DESC LIMIT 50", (rs, i) -> new Entry(rs.getString("type"),
                rs.getLong("amount"), rs.getString("run_id"), rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()), userId);
        return new Credits(userId, "KRW", balance, reserved, balance - reserved, entries);
    }

    private Map<String, Object> lock(String userId) {
        jdbc.update("INSERT INTO credit_accounts (user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING", userId);
        return jdbc.queryForMap("SELECT balance, reserved FROM credit_accounts WHERE user_id = ? FOR UPDATE", userId);
    }

    private boolean append(String userId, String type, long amount, String runId, String key, String reason) {
        return jdbc.update("INSERT INTO credit_entries (user_id, type, amount, run_id, idempotency_key, reason) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING",
                userId, type, amount, runId, key, reason) == 1;
    }

    /** 금액은 milli-KRW다. available은 잔액 − 예약이며 음수일 수 있다. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Credits(String userId, String currency, long balanceKrwMilli, long reservedKrwMilli,
                          long availableKrwMilli, List<Entry> entries) {
    }

    /** amount는 원장 부호 그대로다(reserve +, release·charge −). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Entry(String type, long amountKrwMilli, String runId, String reason, Instant createdAt) {
    }

    /** 잔액이 모자라 AI 요청 전에 거절한다. 402 {@code INSUFFICIENT_CREDIT}로 응답한다. */
    public static class InsufficientCreditException extends RuntimeException {
        public InsufficientCreditException() {
            super("크레딧 잔액이 부족합니다. 크레딧을 충전한 뒤 다시 시도해 주세요.");
        }
    }
}
