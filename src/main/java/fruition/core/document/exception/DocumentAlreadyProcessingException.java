package fruition.core.document.exception;

public class DocumentAlreadyProcessingException extends RuntimeException {
    public DocumentAlreadyProcessingException(String message) {
        super(message);
    }
}
