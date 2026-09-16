package fruition.core.document.exception;

public class DocumentVersionConflictException extends RuntimeException {
    public DocumentVersionConflictException(String message) {
        super(message);
    }
}
