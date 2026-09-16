package fruition.core.document.exception;

public class DocumentAssetExportException extends RuntimeException {
    public DocumentAssetExportException(String message) {
        super(message);
    }

    public DocumentAssetExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
