package fruition.shared.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "컨트롤러까지 도달한 요청이 실패했을 때 모든 API가 공유하는 에러 응답 형식. "
        + "인증 필터가 막는 401은 HttpStatusEntryPoint가 상태 코드만 내보내므로 이 본문이 없다.")
public record ErrorResponse(
        @Schema(description = "에러 상세")
        ErrorDetail error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDetail(
            @Schema(description = "분기 기준으로 쓰는 값. message가 아니라 이 값으로 분기한다.",
                    example = "INVALID_REQUEST")
            String code,

            @Schema(description = "사용자에게 그대로 보여도 되는 한국어 문구",
                    example = "요청 형식이 올바르지 않습니다.")
            String message,

            @Schema(description = "검증 실패(400 INVALID_REQUEST)일 때만 존재한다. 값이 없으면 키 자체가 빠지므로 null 체크가 아니라 존재 여부로 확인한다.")
            List<FieldError> details) {

        public record FieldError(
                @Schema(description = "문제가 된 요청 필드명", example = "email")
                String field,

                @Schema(description = "그 필드의 위반 사유", example = "email은 필수입니다.")
                String reason) {}
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new ErrorDetail(code, message, null));
    }

    public static ErrorResponse ofValidation(List<ErrorDetail.FieldError> details) {
        return new ErrorResponse(new ErrorDetail("INVALID_REQUEST", "요청 형식이 올바르지 않습니다.", details));
    }
}
