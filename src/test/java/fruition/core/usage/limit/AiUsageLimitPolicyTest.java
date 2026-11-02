package fruition.core.usage.limit;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AiUsageLimitPolicyTest {

    private static final AiUsageLimitPolicy.Limits LIMITS =
            new AiUsageLimitPolicy.Limits(true, 5_000_000, 2_000_000, 3);

    @Test
    void passesWhenEveryUsageIsBelowItsLimit() {
        var usage = new AiUsageLimitPolicy.Usage(2, 3_000_000, 1_000_000, 1_999_999);

        assertThat(AiUsageLimitPolicy.evaluate(LIMITS, usage, 900_000)).isEmpty();
    }

    @Test
    void rejectsWhenUnfinishedRunsAlreadyFillEverySlot() {
        var usage = new AiUsageLimitPolicy.Usage(3, 0, 0, 0);

        assertThat(AiUsageLimitPolicy.evaluate(LIMITS, usage, 1)).contains(AiUsageLimitType.CONCURRENT);
    }

    @Test
    void rejectsWhenProjectedCostIncludingThisRunWouldExceedDailyCap() {
        // 오늘 실제 3.0 + 진행 중 예상 1.5 + 이번 예상 0.6 = 5.1 > 5.0
        var usage = new AiUsageLimitPolicy.Usage(1, 3_000_000, 1_500_000, 0);

        assertThat(AiUsageLimitPolicy.evaluate(LIMITS, usage, 600_000)).contains(AiUsageLimitType.DAILY_COST);
    }

    @Test
    void allowsProjectedCostExactlyAtDailyCap() {
        var usage = new AiUsageLimitPolicy.Usage(0, 3_000_000, 1_500_000, 0);

        assertThat(AiUsageLimitPolicy.evaluate(LIMITS, usage, 500_000)).isEmpty();
    }

    @Test
    void rejectsWhenTodaysTokensReachedDailyCap() {
        var usage = new AiUsageLimitPolicy.Usage(0, 0, 0, 2_000_000);

        assertThat(AiUsageLimitPolicy.evaluate(LIMITS, usage, 1)).contains(AiUsageLimitType.DAILY_TOKENS);
    }

    @Test
    void concurrencyIsReportedBeforeDailyLimits() {
        var usage = new AiUsageLimitPolicy.Usage(3, 9_000_000, 0, 9_000_000);

        assertThat(AiUsageLimitPolicy.evaluate(LIMITS, usage, 1)).contains(AiUsageLimitType.CONCURRENT);
    }

    @Test
    void disabledSwitchPassesEverything() {
        var disabled = new AiUsageLimitPolicy.Limits(false, 0, 0, 0);
        var usage = new AiUsageLimitPolicy.Usage(100, 100_000_000, 100_000_000, 100_000_000);

        assertThat(AiUsageLimitPolicy.evaluate(disabled, usage, 1_000_000)).isEmpty();
    }

    @Test
    void dayStartsAndResetsAtSeoulMidnight() {
        // 2026-11-02 14:59:59Z = 11-02 23:59:59 KST
        Instant beforeMidnight = Instant.parse("2026-11-02T14:59:59Z");
        assertThat(AiUsageLimitPolicy.dayStart(beforeMidnight)).isEqualTo(Instant.parse("2026-11-01T15:00:00Z"));
        assertThat(AiUsageLimitPolicy.nextReset(beforeMidnight)).isEqualTo(Instant.parse("2026-11-02T15:00:00Z"));

        // 2026-11-02 15:00:00Z = 11-03 00:00 KST, 새 하루의 시작
        Instant midnight = Instant.parse("2026-11-02T15:00:00Z");
        assertThat(AiUsageLimitPolicy.dayStart(midnight)).isEqualTo(midnight);
        assertThat(AiUsageLimitPolicy.nextReset(midnight)).isEqualTo(Instant.parse("2026-11-03T15:00:00Z"));
    }

    @Test
    void messagesAreKoreanAndNameTheConcurrencyLimit() {
        assertThat(AiUsageLimitType.CONCURRENT.message(3)).contains("3개");
        assertThat(AiUsageLimitType.DAILY_COST.message(3)).contains("오늘");
        assertThat(AiUsageLimitType.DAILY_TOKENS.message(3)).contains("오늘");
        assertThat(AiUsageLimitType.CONCURRENT.daily()).isFalse();
        assertThat(AiUsageLimitType.DAILY_COST.daily()).isTrue();
    }
}
