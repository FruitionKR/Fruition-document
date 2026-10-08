package fruition.core.document.exception;

/** 사용자가 지정할 수 없는 문서 origin 값. */
public class InvalidDocumentOriginException extends RuntimeException {
    public InvalidDocumentOriginException(String message) {
        super(message);
    }
}
