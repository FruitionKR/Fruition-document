package fruition.core.usage.limit;

import fruition.core.CoreExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AiUsageLimitResponseTest {

    @Test
    void dailyTokenLimitRespondsWith429ResetTimeInSeoulAndRetryAfter() {
        Instant resetAt = AiUsageLimitPolicy.nextReset(Instant.now());
        var e = new AiUsageLimitExceededException(AiUsageLimitType.DAILY_TOKENS, resetAt);

        var response = new CoreExceptionHandler().handleAiUsageLimit(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        var error = response.getBody().error();
        assertThat(error.code()).isEqualTo("AI_USAGE_LIMIT_EXCEEDED");
        assertThat(error.message()).isEqualTo(AiUsageLimitType.DAILY_TOKENS.message());
        assertThat(error.limitType()).isEqualTo("DAILY_TOKENS");
        assertThat(error.resetAt()).matches("\\d{4}-\\d{2}-\\d{2}T00:00:00\\+09:00");
        assertThat(java.time.OffsetDateTime.parse(error.resetAt()).toInstant()).isEqualTo(resetAt);
        long retryAfter = Long.parseLong(response.getHeaders().getFirst("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 86_400L);
    }
}
