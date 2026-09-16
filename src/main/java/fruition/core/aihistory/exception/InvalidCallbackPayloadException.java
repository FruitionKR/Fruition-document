package fruition.core.aihistory.exception;

/** 콜백 본문이 계약에 맞지 않거나 객체를 읽을 수 없을 때. llmPipeline이 다시 쓰고 재전송해야 한다. */
public class InvalidCallbackPayloadException extends RuntimeException {
    public InvalidCallbackPayloadException(String message) {
        super(message);
    }
}
