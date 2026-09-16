package fruition.core.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * @param previewToken 미리보기 응답에서 받은 값. 그사이 대상이 바뀌면 409로 거절한다
 */
@Schema(description = "복구 실행 요청. 미리보기에서 받은 토큰으로 대상이 그대로인지 확인한다.")
public record RestoreExecuteRequest(
        @JsonProperty("preview_token") @NotBlank
        @Schema(description = "미리보기 응답에서 받은 토큰. 그사이 대상이 바뀌었으면 409로 거절된다.")
        String previewToken
) {}
