package fruition.shared.util;

import fruition.shared.ai.InvalidAiModelException;
import fruition.shared.idempotency.IdempotencyConflictException;
import fruition.shared.idempotency.IdempotencyInProgressException;
import fruition.shared.idempotency.InvalidIdempotencyKeyException;
import fruition.shared.logging.LoggableUri;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 양쪽 앱(access-svc·document-svc)이 공유하는 공통 예외 매핑.
 * 각 앱의 {@code @RestControllerAdvice}가 이 클래스를 상속해 앱별 예외 매핑을 더한다.
 */
public abstract class BaseExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BaseExceptionHandler.class);

    /**
     * 처리된 예외를 한 줄로 남긴다. 4xx는 warn, 5xx는 error(스택트레이스 포함)로 나눈다.
     * 잘못된 입력·권한 거부처럼 정상 운영 중에도 생기는 4xx가 error로 쌓이면
     * 실제로 봐야 하는 시스템 오류가 묻히기 때문이다.
     */
    protected void logHandled(Exception e, HttpStatus status, String code) {
        logHandled(e, status.value(), code);
    }

    protected void logHandled(Exception e, int status, String code) {
        if (status >= 500) {
            log.error("[예외] type={} code={} status={} {} message={}",
                    e.getClass().getSimpleName(), code, status, currentRequestLine(), e.getMessage(), e);
        } else {
            log.warn("[예외] type={} code={} status={} {} message={}",
                    e.getClass().getSimpleName(), code, status, currentRequestLine(), e.getMessage());
        }
    }

    /** 로그에 붙일 요청 정보. 요청 컨텍스트 밖(비동기 등)에서 호출되면 값을 비운다. */
    private String currentRequestLine() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest request = servletAttributes.getRequest();
            return "method=" + request.getMethod() + " uri=" + LoggableUri.mask(request.getRequestURI());
        }
        return "method=- uri=-";
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_REQUEST", "요청 형식이 올바르지 않습니다."));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipartException(MultipartException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_REQUEST", "파일이 없거나 비어 있습니다."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        List<ErrorResponse.ErrorDetail.FieldError> details = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.ErrorDetail.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.ofValidation(details));
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<ErrorResponse> handleInvalidIdempotencyKey(InvalidIdempotencyKeyException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_IDEMPOTENCY_KEY", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException e) {
        logHandled(e, HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("IDEMPOTENCY_CONFLICT", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyInProgressException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyInProgress(IdempotencyInProgressException e) {
        logHandled(e, HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("IDEMPOTENCY_IN_PROGRESS", e.getMessage()));
    }

    @ExceptionHandler(InvalidAiModelException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAiModel(InvalidAiModelException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "INVALID_AI_MODEL");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_AI_MODEL", e.getMessage()));
    }

    /**
     * 상태 코드를 직접 지정해 던지는 예외. fallback이 500으로 뭉개지 않도록 원래 상태를 그대로 보존한다.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException e) {
        int status = e.getStatusCode().value();
        String message = e.getReason() != null ? e.getReason() : "요청을 처리할 수 없습니다.";
        logHandled(e, status, "REQUEST_FAILED");
        return ResponseEntity
                .status(status)
                .body(ErrorResponse.of("REQUEST_FAILED", message));
    }

    /**
     * 매핑되지 않은 예외의 최종 처리. 이 자리가 비어 있으면 예상 못 한 오류가
     * 어느 요청에서 났는지 기록 없이 사라진다.
     *
     * <p>Spring이 상태 코드를 담아 던지는 예외(없는 경로의 404 등)는 그 상태를 그대로 보존한다.
     * 보존하지 않으면 404여야 할 응답이 500으로 바뀐다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        String duplicateNameMessage = DuplicateResourceName.message(e);
        if (duplicateNameMessage != null) {
            logHandled(e, HttpStatus.CONFLICT, "DUPLICATE_NAME");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.of("DUPLICATE_NAME", duplicateNameMessage));
        }
        if (e instanceof org.springframework.web.ErrorResponse springError) {
            int status = springError.getStatusCode().value();
            logHandled(e, status, "REQUEST_FAILED");
            return ResponseEntity
                    .status(status)
                    .body(ErrorResponse.of("REQUEST_FAILED", "요청을 처리할 수 없습니다."));
        }
        logHandled(e, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "서버 오류가 발생했습니다."));
    }
}
