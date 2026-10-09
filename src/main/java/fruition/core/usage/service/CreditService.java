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
 *       그대로 차감해 잔액이 음수가 될 수 있다. 실행이 아직 진행 중이면 차감만 하고 예약은 두며, 종료 신호 없이
 *       {@code app.billing.stale-reservation-hours}가 지난 실행은 끝난 것으로 본다.</li>
 *   <li>재예약: 실시간 전사처럼 긴 실행은 구간마다 그때까지의 청구를 차감하고 예약을 다음 구간 상한으로 바꾼다.</li>
 *   <li>멱등: 원장 키가 run_id 기반이라 같은 실행을 여러 번 정산해도 같은 금액은 한 번만 차감한다.</li>
 * </ul>
 */
@Service
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);
    /** {@code ai_task_runs} 행이 진행 중인 실행이라는 조건. 인자는 오래된 예약 기준 시간이다. */
    private static final String IN_PROGRESS = "t.status IN ('running', 'cancel_requested', 'rolling_back') "
            + "AND t.updated_at > now() - make_interval(hours => ?)";

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
            boolean inProgress = inProgress(runId);
            // 잠금 순서를 user_id 순으로 고정해 정산끼리 교착하지 않는다.
            totals.forEach((userId, total) -> {
                lock(userId);
                charge(runId, userId, total);
                long open = open(runId, userId);
                if (!inProgress && open > 0 && append(userId, "release", -open, runId, "release:" + runId, null)) {
                    jdbc.update("UPDATE credit_accounts SET reserved = reserved - ?, updated_at = now() WHERE user_id = ?",
                            open, userId);
                }
            });
        });
    }

    /**
     * 진행 중인 실행의 청구를 차감하고, 남은 예약을 풀어 다음 구간 상한으로 다시 예약한다. enforce에서 차감 뒤 가용 잔액이
     * 상한보다 작으면 {@link InsufficientCreditException}을 던지고 아무것도 반영하지 않는다(실행 종료 정산이 차감한다).
     * seq는 구간 번호이며 같은 구간을 다시 불러도 한 번만 반영한다. 이미 끝난 실행은 차감만 한다.
     */
    public void renew(String runId, String userId, String kind, int seq) {
        long estimate = environment.getProperty("app.billing.estimate." + kind, Long.class, defaultEstimate);
        transaction.executeWithoutResult(tx -> {
            lock(userId);
            Long total = jdbc.queryForObject("SELECT coalesce(sum(charge_krw_milli), 0) FROM usage_charges "
                    + "WHERE run_id = ? AND user_id = ?", Long.class, runId, userId);
            charge(runId, userId, total);
            // 계정 행을 잠근 뒤 확인한다. 그사이 끝난 실행을 다시 예약하면 종료 정산이 풀지 못한다.
            if (!inProgress(runId)) return;
            Map<String, Object> account = jdbc.queryForMap("SELECT balance, reserved FROM credit_accounts WHERE user_id = ?", userId);
            long balance = ((Number) account.get("balance")).longValue();
            long open = open(runId, userId);
            long othersReserved = ((Number) account.get("reserved")).longValue() - open;
            if (enforce && (balance < 0 || balance - othersReserved < estimate)) {
                throw new InsufficientCreditException();
            }
            if (open > 0 && append(userId, "release", -open, runId, "release:" + runId + ":" + seq, null)) {
                jdbc.update("UPDATE credit_accounts SET reserved = reserved - ?, updated_at = now() WHERE user_id = ?",
                        open, userId);
            }
            if (append(userId, "reserve", estimate, runId, "reserve:" + runId + ":" + seq, kind)) {
                jdbc.update("UPDATE credit_accounts SET reserved = reserved + ?, updated_at = now() WHERE user_id = ?",
                        estimate, userId);
            }
        });
    }

    /** 실행의 청구 합계 중 아직 차감하지 않은 만큼 차감한다. 키가 누적 청구액이라 같은 합계로는 한 번만 차감한다. */
    private void charge(String runId, String userId, long total) {
        Long charged = jdbc.queryForObject("SELECT -coalesce(sum(amount), 0) FROM credit_entries "
                + "WHERE run_id = ? AND user_id = ? AND type = 'charge'", Long.class, runId, userId);
        long delta = total - charged;
        if (delta > 0 && append(userId, "charge", -delta, runId, "charge:" + runId + ":" + userId + ":" + total, null)) {
            jdbc.update("UPDATE credit_accounts SET balance = balance - ?, updated_at = now() WHERE user_id = ?",
                    delta, userId);
        }
    }

    /** 실행에 아직 남은 예약. */
    private long open(String runId, String userId) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM credit_entries "
                + "WHERE run_id = ? AND user_id = ? AND type IN ('reserve', 'release')", Long.class, runId, userId);
    }

    /** 실행이 아직 진행 중인지. 종료 신호 없이 오래된 실행(프로세스 종료 등)은 끝난 것으로 본다. */
    private boolean inProgress(String runId) {
        return !jdbc.queryForList("SELECT t.id FROM ai_task_runs t WHERE t.id = ? AND " + IN_PROGRESS, String.class,
                runId, staleHours).isEmpty();
    }

    /**
     * 종료 신호 없이 남은 예약을 정리한다. 하루 단위 대사가 호출을 채울 시간을 둔 뒤 그때까지의 청구로 정산한다.
     * 아직 진행 중인 실행은 건너뛴다. 여러 Pod가 같은 실행을 골라도 원장 키가 같아 한 번만 반영된다.
     */
    @Scheduled(initialDelay = 120_000, fixedDelayString = "${app.billing.release-interval-ms:3600000}")
    public void releaseStale() {
        for (String runId : jdbc.queryForList("SELECT r.run_id FROM credit_entries r "
                + "WHERE r.type = 'reserve' AND r.created_at < now() - make_interval(hours => ?) "
                + "AND NOT EXISTS (SELECT 1 FROM credit_entries e WHERE e.idempotency_key = 'release:' || r.run_id) "
                + "AND NOT EXISTS (SELECT 1 FROM ai_task_runs t WHERE t.id = r.run_id AND " + IN_PROGRESS + ") "
                + "GROUP BY r.run_id ORDER BY min(r.created_at) LIMIT 100", String.class, staleHours, staleHours)) {
            try {
                settle(runId);
            } catch (RuntimeException e) {
                log.warn("[크레딧 예약 정리 실패] runId={} error={}", runId, e.toString());
            }
        }
    }

    /**
     * 잔액에 반영되는 원장 행(purchase·refund 등)을 쓴다. 호출자 트랜잭션에서 계정 행을 잠그며 같은 키는 한 번만 반영한다.
     *
     * @return 이번에 반영했으면 true
     */
    public boolean post(String userId, String type, long amount, String key, String reason) {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            lock(userId);
            if (!append(userId, type, amount, null, key, reason)) return false;
            jdbc.update("UPDATE credit_accounts SET balance = balance + ?, updated_at = now() WHERE user_id = ?",
                    amount, userId);
            return true;
        }));
    }

    /** 계정 행을 잠그고 가용 잔액(잔액 − 예약)을 돌려준다. 잠금은 호출자 트랜잭션이 끝날 때까지 유지된다. */
    public long lockAvailable(String userId) {
        Map<String, Object> account = lock(userId);
        return ((Number) account.get("balance")).longValue() - ((Number) account.get("reserved")).longValue();
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
