package fruition.core.agent.exception;

public class PipelineAgentException extends RuntimeException {

    private final int httpStatus;
    private final String responseBody;

    public PipelineAgentException(String message, int httpStatus, String responseBody) {
        super(message);
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
