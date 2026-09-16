package fruition.core.document.exception;

/** 다른 사용자가 편집 잠금을 보유 중이라 획득/쓰기가 거부됐다. HTTP 423으로 매핑한다. */
public class DocumentLockedException extends RuntimeException {
    public DocumentLockedException(String message) {
        super(message);
    }
}
