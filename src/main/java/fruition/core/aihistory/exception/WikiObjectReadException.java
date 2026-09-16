package fruition.core.aihistory.exception;

/**
 * 경로는 맞는데 저장소에서 읽지 못한 경우. 콜백 payload 문제가 아니라 우리 쪽 장애다.
 *
 * <p>{@link InvalidCallbackPayloadException}(422)과 구분해야 한다. 422는 계약상
 * "다시 쓰고 재전송"이라, 저장소가 죽었을 때 422를 주면 llmPipeline이 무의미하게 재작업한다.
 */
public class WikiObjectReadException extends RuntimeException {
    public WikiObjectReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
