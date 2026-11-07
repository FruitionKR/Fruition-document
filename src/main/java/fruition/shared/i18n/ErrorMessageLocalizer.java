package fruition.shared.i18n;

import fruition.shared.util.ErrorResponse;
import org.springframework.context.MessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.List;

/**
 * 공통 {@link ErrorResponse}의 {@code message}를 요청 언어로 맞춘다.
 *
 * <p>code는 그대로 둔다. 한국어(기본) 요청은 예외가 만든 상세 문구를 그대로 내보내고, 영어 요청은 code별 영어 문구
 * ({@code messages_en.properties}의 {@code error.<code>}, 없으면 {@code error.default})로 바꾼다. 검증 실패의
 * {@code details[].reason}도 영어 요청이면 {@code error.field.invalid}로 바꾼다. pipeline 응답을 그대로 중계하는 본문(문자열)은
 * 우리 형식이 아니라 건드리지 않는다.
 */
@ControllerAdvice
public class ErrorMessageLocalizer implements ResponseBodyAdvice<Object> {

    private final MessageSource messageSource;

    public ErrorMessageLocalizer(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ErrorResponse error && error.error() != null) {
            return localize(error, ResponseLanguage.current());
        }
        return body;
    }

    ErrorResponse localize(ErrorResponse response, ResponseLanguage language) {
        ErrorResponse.ErrorDetail detail = response.error();
        if (!language.isEnglish()) {
            if (detail.message() != null && !detail.message().isBlank()) {
                return response;
            }
            return new ErrorResponse(withMessage(detail, message(detail.code(), language), detail.details()));
        }
        List<ErrorResponse.ErrorDetail.FieldError> details = detail.details() == null ? null
                : detail.details().stream()
                .map(field -> new ErrorResponse.ErrorDetail.FieldError(field.field(), message("field.invalid", language)))
                .toList();
        return new ErrorResponse(withMessage(detail, message(detail.code(), language), details));
    }

    /** code의 문구. 키가 없으면 기본 문구다. 인자를 넘기지 않으므로 문구의 작은따옴표는 그대로 나간다. */
    public String message(String code, ResponseLanguage language) {
        String fallback = messageSource.getMessage("error.default", null, "", language.locale());
        return code == null ? fallback : messageSource.getMessage("error." + code, null, fallback, language.locale());
    }

    private static ErrorResponse.ErrorDetail withMessage(ErrorResponse.ErrorDetail detail, String message,
                                                         List<ErrorResponse.ErrorDetail.FieldError> details) {
        return new ErrorResponse.ErrorDetail(detail.code(), message, details, detail.currentRevision(),
                detail.limitType(), detail.resetAt(), detail.lockedDocumentName());
    }
}
