package fruition.core.usage.limit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/** 사용자별 AI 사용 한도 판정. DB를 보지 않는 순수 계산이라 {@link AiUsageLimiter}가 모은 값으로 판정한다. */
public final class AiUsageLimitPolicy {

    /** 하루 한도의 경계 시간대. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private AiUsageLimitPolicy() {
    }

    /** 한도 설정. 금액은 USD micro(1 USD = 1,000,000)다. */
    public record Limits(boolean enabled, long dailyCostUsdMicro, long dailyTokens, int concurrent) {
    }

    /**
     * 판정 시점의 사용량. activeRuns는 이번 실행을 뺀 끝나지 않은 실행 수, inFlightEstimateUsdMicro는 그 실행들의 예상 비용 중
     * 아직 청구되지 않은 몫, todayCostUsdMicro·todayTokens는 오늘 수집된 청구 합계다.
     */
    public record Usage(int activeRuns, long todayCostUsdMicro, long inFlightEstimateUsdMicro, long todayTokens) {
    }

    /** 넘는 한도가 있으면 그 종류를 돌려준다. 여럿이면 동시 실행 → 하루 비용 → 하루 토큰 순으로 하나만 알린다. */
    public static Optional<AiUsageLimitType> evaluate(Limits limits, Usage usage, long runEstimateUsdMicro) {
        if (!limits.enabled()) return Optional.empty();
        if (usage.activeRuns() >= limits.concurrent()) return Optional.of(AiUsageLimitType.CONCURRENT);
        long projected = usage.todayCostUsdMicro() + usage.inFlightEstimateUsdMicro() + runEstimateUsdMicro;
        if (projected > limits.dailyCostUsdMicro()) return Optional.of(AiUsageLimitType.DAILY_COST);
        if (usage.todayTokens() >= limits.dailyTokens()) return Optional.of(AiUsageLimitType.DAILY_TOKENS);
        return Optional.empty();
    }

    /** now가 속한 하루(KST)의 시작. */
    public static Instant dayStart(Instant now) {
        return LocalDate.ofInstant(now, ZONE).atStartOfDay(ZONE).toInstant();
    }

    /** 하루 한도가 풀리는 다음 0시(KST). */
    public static Instant nextReset(Instant now) {
        return LocalDate.ofInstant(now, ZONE).plusDays(1).atStartOfDay(ZONE).toInstant();
    }
}
