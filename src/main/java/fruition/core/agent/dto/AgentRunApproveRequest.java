package fruition.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Agent 편집안 승인 요청. 정의되지 않은 필드가 있으면 거절된다.")
public record AgentRunApproveRequest(
        @JsonProperty("plan_version") @Min(1)
        @Schema(description = "승인 대상 plan의 버전. 서버 값과 다르면 거절된다.", minimum = "1", example = "1")
        int planVersion,

        @JsonProperty("operation_hash") @NotBlank @Size(min = 64, max = 64)
        @Schema(description = "승인 대상 편집안의 해시(64자). 내용이 바뀌면 값이 달라져 잘못된 승인을 막는다.",
                minLength = 64, maxLength = 64)
        String operationHash,

        @JsonProperty("operation_ids") @Size(min = 1, max = 20)
        @Schema(description = "실행할 작업(plan.operations[].id)만 고를 때 보낸다. 생략하면 계획 전체를 실행한다. "
                + "계획에 없는 ID·중복이 있거나, 고른 작업이 고르지 않은 작업에 의존(depends_on)하면 거절된다. "
                + "계획의 모든 작업을 고르면 생략과 같다.",
                nullable = true)
        List<@NotBlank String> operationIds
) {
    public AgentRunApproveRequest(int planVersion, String operationHash) {
        this(planVersion, operationHash, null);
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown property: " + name);
    }
}
