package fruition.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Agent 편집안 수정 요청. 정의되지 않은 필드가 있으면 거절된다.")
public record AgentRunReviseRequest(
        @NotBlank @Size(max = 1000)
        @Schema(description = "어떻게 고칠지 알려주는 지시문(1000자 이하)",
                maxLength = 1000, example = "표를 목록으로 바꿔줘")
        String instruction
) {
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown property: " + name);
    }
}
