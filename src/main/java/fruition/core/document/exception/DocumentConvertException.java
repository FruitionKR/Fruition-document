package fruition.core.document.exception;

/** PDF → Markdown 변환 실패(변환기 호출 실패·원본 읽기 실패 등). worker가 잡아 문서를 failed로 반영한다. */
public class DocumentConvertException extends RuntimeException {
    public DocumentConvertException(String message) {
        super(message);
    }

    public DocumentConvertException(String message, Throwable cause) {
        super(message, cause);
    }
}
