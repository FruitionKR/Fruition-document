package fruition.core.imports;

import org.springframework.http.HttpStatus;

/** 가져오기 세션 오류. 상태 코드와 오류 코드를 함께 담아 CoreExceptionHandler 한 곳에서 응답한다. */
public class DocumentImportException extends RuntimeException {
    public static final String IMPORT_INVALID = "IMPORT_INVALID";
    public static final String IMPORT_NOT_FOUND = "IMPORT_NOT_FOUND";
    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    private final HttpStatus status;
    private final String code;

    public DocumentImportException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static DocumentImportException invalid() {
        return new DocumentImportException(HttpStatus.BAD_REQUEST, IMPORT_INVALID,
                "가져오기 세션이 없거나 만료·완료되었습니다. 가져오기를 다시 시작해 주세요.");
    }

    static DocumentImportException notFound() {
        return new DocumentImportException(HttpStatus.NOT_FOUND, IMPORT_NOT_FOUND, "가져오기 기록을 찾을 수 없습니다.");
    }

    static DocumentImportException badRequest(String message) {
        return new DocumentImportException(HttpStatus.BAD_REQUEST, INVALID_REQUEST, message);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
