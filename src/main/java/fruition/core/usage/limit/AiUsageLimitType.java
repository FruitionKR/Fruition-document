package fruition.core.usage.limit;

/** 사용자별 AI 사용 한도 종류. 거절 응답의 {@code error.limit_type} 값이다. */
public enum AiUsageLimitType {
    /** 끝나지 않은 AI 실행 수. 하루가 지나도 풀리지 않고 진행 중인 실행이 끝나면 풀린다. */
    CONCURRENT(false),
    /** 오늘(Asia/Seoul) 실제 비용 + 진행 중 실행의 예상 비용 + 이번 실행의 예상 비용(USD). */
    DAILY_COST(true),
    /** 오늘(Asia/Seoul) 수집된 입력·출력 토큰 합계. */
    DAILY_TOKENS(true);

    private final boolean daily;

    AiUsageLimitType(boolean daily) {
        this.daily = daily;
    }

    /** 다음 날 0시(KST)에 풀리는 한도인지. */
    public boolean daily() {
        return daily;
    }

    /** 사용자에게 그대로 보여도 되는 한국어 문구. */
    public String message(int concurrentLimit) {
        return switch (this) {
            case CONCURRENT -> "동시에 진행할 수 있는 AI 작업은 최대 " + concurrentLimit
                    + "개입니다. 진행 중인 작업이 끝난 뒤 다시 시도해 주세요.";
            case DAILY_COST -> "오늘 사용할 수 있는 AI 사용량을 모두 사용했습니다. 내일 0시(한국 시간)부터 다시 이용할 수 있습니다.";
            case DAILY_TOKENS -> "오늘 처리할 수 있는 AI 토큰 한도를 모두 사용했습니다. 내일 0시(한국 시간)부터 다시 이용할 수 있습니다.";
        };
    }
}
