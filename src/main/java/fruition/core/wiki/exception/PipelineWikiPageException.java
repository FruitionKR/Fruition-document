package fruition.core.wiki.exception;

public class PipelineWikiPageException extends RuntimeException {

    private final int httpStatus;
    private final String responseBody;

    public PipelineWikiPageException(String message, int httpStatus, String responseBody) {
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
