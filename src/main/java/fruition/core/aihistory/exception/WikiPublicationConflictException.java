package fruition.core.aihistory.exception;

/** The same publication, operation, or event identity was reused for different content. */
public class WikiPublicationConflictException extends RuntimeException {
    public WikiPublicationConflictException(String message) {
        super(message);
    }
}
