package fruition.core.meeting;

import org.springframework.http.HttpStatus;

/** 회의 API 오류. 상태 코드와 오류 코드를 함께 담아 한 handler로 응답한다. */
public class MeetingException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public MeetingException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static MeetingException notFound() {
        return new MeetingException(HttpStatus.NOT_FOUND, "MEETING_NOT_FOUND", "회의를 찾을 수 없습니다.");
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
