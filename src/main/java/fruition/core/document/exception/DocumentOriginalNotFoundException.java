package fruition.core.document.exception;

public class DocumentOriginalNotFoundException extends RuntimeException {
    public DocumentOriginalNotFoundException(String documentId) {
        super("원본 파일을 찾을 수 없습니다: " + documentId);
    }
}
