package fruition.core.upload;

import org.springframework.http.HttpStatus;

/** 업로드 한도 위반. 상태 코드와 오류 코드를 함께 담아 CoreExceptionHandler 한 곳에서 응답한다. */
public class UploadLimitException extends RuntimeException {
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    public static final String TOO_MANY_FILES = "TOO_MANY_FILES";
    public static final String STORAGE_QUOTA_EXCEEDED = "STORAGE_QUOTA_EXCEEDED";

    private final HttpStatus status;
    private final String code;

    public UploadLimitException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static UploadLimitException tooManyFiles(String message) {
        return new UploadLimitException(HttpStatus.BAD_REQUEST, TOO_MANY_FILES, message);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
