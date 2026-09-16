package fruition.core.document.exception;

public class HierarchyCycleException extends RuntimeException {
    public HierarchyCycleException(String message) {
        super(message);
    }
}
