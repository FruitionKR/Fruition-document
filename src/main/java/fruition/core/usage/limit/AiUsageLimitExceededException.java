package fruition.core.usage.limit;

import java.time.Instant;

/** 사용자별 AI 사용 한도를 넘어 실행을 시작하지 않았다. 429 {@code AI_USAGE_LIMIT_EXCEEDED}로 응답한다. */
public class AiUsageLimitExceededException extends RuntimeException {
    private final AiUsageLimitType type;
    private final Instant resetAt;

    /** resetAt은 하루 한도가 풀리는 시각이며 동시 실행 한도는 null이다. */
    public AiUsageLimitExceededException(AiUsageLimitType type, Instant resetAt, String message) {
        super(message);
        this.type = type;
        this.resetAt = resetAt;
    }

    public AiUsageLimitType getType() { return type; }
    public Instant getResetAt() { return resetAt; }
}
