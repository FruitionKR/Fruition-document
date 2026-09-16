package fruition.core.query.exception;

public class PipelineQueryException extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;
    private final String pipelineErrorBody;

    public PipelineQueryException(String errorCode, String message, int httpStatus, String pipelineErrorBody) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.pipelineErrorBody = pipelineErrorBody;
    }

    public String getErrorCode() { return errorCode; }
    public int getHttpStatus() { return httpStatus; }
    public String getPipelineErrorBody() { return pipelineErrorBody; }
}
