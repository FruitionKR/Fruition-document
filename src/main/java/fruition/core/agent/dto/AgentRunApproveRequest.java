package fruition.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Agent 편집안 승인 요청. 정의되지 않은 필드가 있으면 거절된다.")
public record AgentRunApproveRequest(
        @JsonProperty("plan_version") @Min(1)
        @Schema(description = "승인 대상 plan의 버전. 서버 값과 다르면 거절된다.", minimum = "1", example = "1")
        int planVersion,

        @JsonProperty("operation_hash") @NotBlank @Size(min = 64, max = 64)
        @Schema(description = "승인 대상 편집안의 해시(64자). 내용이 바뀌면 값이 달라져 잘못된 승인을 막는다.",
                minLength = 64, maxLength = 64)
        String operationHash
) {
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown property: " + name);
    }
}
