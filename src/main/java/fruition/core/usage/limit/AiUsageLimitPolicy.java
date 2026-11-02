package fruition.core.usage.limit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** 사용자별 AI 하루 한도의 경계(KST 0시) 계산. */
public final class AiUsageLimitPolicy {

    /** 하루 한도의 경계 시간대. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private AiUsageLimitPolicy() {
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
