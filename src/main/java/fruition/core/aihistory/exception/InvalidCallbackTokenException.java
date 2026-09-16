package fruition.core.aihistory.exception;

/** 내부 콜백 토큰이 맞지 않는 경우. 저장소 객체를 읽기 전에 거절한다. */
public class InvalidCallbackTokenException extends RuntimeException {
    public InvalidCallbackTokenException() {
        super("내부 콜백 토큰이 올바르지 않습니다.");
    }
}
