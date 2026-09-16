package fruition.core.document.exception;

public class MarkdownContentTooLargeException extends RuntimeException {
    public MarkdownContentTooLargeException(String message) {
        super(message);
    }
}
