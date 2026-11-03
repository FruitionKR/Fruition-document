package fruition.core.usage.limit;

/**
 * 사용자별 AI 사용 한도 종류. 거절 응답의 {@code error.limit_type} 값이다.
 * 응답으로 내보내기만 하고 저장·역직렬화하지 않으므로, 없앤 종류(CONCURRENT·DAILY_COST)를 호환용으로 남기지 않는다.
 */
public enum AiUsageLimitType {
    /** 오늘(Asia/Seoul) 수집된 입력·출력 토큰 합계. 다음 날 0시(KST)에 풀린다. */
    DAILY_TOKENS;

    /** 사용자에게 그대로 보여도 되는 한국어 문구. */
    public String message() {
        return "오늘 처리할 수 있는 AI 토큰 한도를 모두 사용했습니다. 내일 0시(한국 시간)부터 다시 이용할 수 있습니다.";
    }
}
