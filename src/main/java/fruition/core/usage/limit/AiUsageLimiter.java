package fruition.core.usage.limit;

import fruition.core.usage.service.CreditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 사용자별 AI 사용 한도(하루 토큰). 워크스페이스와 무관하게 사용자 단위로 센다.
 *
 * <p>두 관문({@code AiCommandOutboxWriter.begin/reserve}, {@code AiTaskCancellationService.register})이
 * {@code ai_task_runs} 행을 새로 넣은 같은 트랜잭션에서 {@link #check}를 부른다. 거절하면 예외로 등록이 롤백된다.
 *
 * <p>오늘(KST) 수집된 입력+출력 토큰이 한도에 닿으면 새 실행을 거절한다. 실행 전에는 토큰을 알 수 없어 예약 없이 사후 차단하므로
 * 한도 직전에 시작한 실행들은 한도를 넘겨 끝날 수 있다. 예약이 없어 판정을 직렬화해도 막을 수 있는 경합이 없으므로 잠금을 잡지 않는다.
 * 모델을 부르지 않는 복원(restore_*)과 사용자가 없는 시스템 command는 세지 않는다.
 */
@Component
public class AiUsageLimiter {

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final long dailyTokens;

    public AiUsageLimiter(JdbcTemplate jdbc,
                          @Value("${app.ai-limit.enabled:true}") boolean enabled,
                          @Value("${app.ai-limit.daily-tokens:10000000}") long dailyTokens) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.dailyTokens = dailyTokens;
    }

    /** 오늘 토큰이 한도에 닿았으면 {@link AiUsageLimitExceededException}을 던진다. */
    public void check(String userId, String kind) {
        if (!enabled || userId == null || userId.isBlank() || CreditService.isFree(kind)) return;
        Instant now = Instant.now();
        Instant resetAt = AiUsageLimitPolicy.nextReset(now);
        Long tokens = jdbc.queryForObject("""
                SELECT coalesce(sum(coalesce(input_tokens, 0) + coalesce(output_tokens, 0)), 0)
                FROM usage_charges WHERE user_id = ? AND started_at >= ? AND started_at < ?
                """, Long.class, userId, Timestamp.from(AiUsageLimitPolicy.dayStart(now)), Timestamp.from(resetAt));
        if (tokens >= dailyTokens) throw new AiUsageLimitExceededException(AiUsageLimitType.DAILY_TOKENS, resetAt);
    }

    /** 적용 중인 하루 토큰 한도. */
    public long dailyTokens() {
        return dailyTokens;
    }
}
