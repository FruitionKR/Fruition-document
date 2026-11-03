package fruition.core.usage.limit;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AiUsageLimitPolicyTest {

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
    void onlyTheDailyTokenLimitRemainsAndItsMessageIsKorean() {
        assertThat(AiUsageLimitType.values()).containsExactly(AiUsageLimitType.DAILY_TOKENS);
        assertThat(AiUsageLimitType.DAILY_TOKENS.message()).contains("오늘").contains("토큰");
    }
}
