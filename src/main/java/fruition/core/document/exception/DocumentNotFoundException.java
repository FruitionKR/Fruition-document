package fruition.core.document.exception;

public class DocumentNotFoundException extends RuntimeException {
    public DocumentNotFoundException(String id) {
        super("문서를 찾을 수 없습니다: id=" + id);
    }
}
