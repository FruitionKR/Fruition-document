package fruition.core.skill.exception;

public class PipelineSkillException extends RuntimeException {
    private final int httpStatus;
    private final String responseBody;
    private final String code;

    public PipelineSkillException(String message, int httpStatus, String responseBody) {
        this(message, httpStatus, responseBody, null);
    }

    /** code: 클라이언트에 줄 오류 코드. null이면 상태로 정한다(4xx는 SKILL_REQUEST_REJECTED). */
    public PipelineSkillException(String message, int httpStatus, String responseBody, String code) {
        super(message);
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
        this.code = code;
    }

    public int getHttpStatus() { return httpStatus; }
    public String getResponseBody() { return responseBody; }
    public String getCode() { return code; }
}
