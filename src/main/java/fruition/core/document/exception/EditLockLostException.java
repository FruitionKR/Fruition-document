package fruition.core.document.exception;

/** heartbeat 시점에 보유자가 아니거나 잠금이 이미 만료됐다(잠금 상실). HTTP 409로 매핑한다. */
public class EditLockLostException extends RuntimeException {
    public EditLockLostException(String message) {
        super(message);
    }
}
