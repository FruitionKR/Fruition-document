package fruition.core.wikischema.exception;

public class PipelineWikiSchemaException extends RuntimeException {

    private final int httpStatus;
    private final String responseBody;

    public PipelineWikiSchemaException(String message, int httpStatus, String responseBody) {
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
