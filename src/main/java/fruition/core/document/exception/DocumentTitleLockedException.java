package fruition.core.document.exception;

/** 채팅에서 만든 문서처럼 이름이 잠긴 문서의 이름 변경 요청. HTTP 409로 매핑한다. */
public class DocumentTitleLockedException extends RuntimeException {
    public DocumentTitleLockedException(String message) {
        super(message);
    }
}
