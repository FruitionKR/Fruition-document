package fruition.core.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Skill 게시 요청. 게시하면 새 버전이 만들어진다.")
public record SkillPublishRequest(
        @JsonProperty("scope_type")
        @NotBlank
        @Pattern(regexp = "personal|team")
        @Schema(description = "Skill 공개 범위", allowableValues = {"personal", "team"}, example = "personal")
        String scopeType,

        @NotBlank
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$")
        @Schema(description = "Skill 이름. 소문자·숫자로 시작하고 하이픈을 쓸 수 있다(1~63자).",
                pattern = "^[a-z0-9][a-z0-9-]{0,62}$", example = "meeting-notes")
        String name,

        @NotBlank
        @Size(max = 500)
        @Schema(description = "Skill 설명(500자 이하)", maxLength = 500)
        String description,

        @JsonProperty("instructions_markdown")
        @NotBlank
        @Size(max = 30000)
        @Schema(description = "Skill 지침 Markdown(30000자 이하)", maxLength = 30000)
        String instructionsMarkdown,

        @NotNull
        @Size(min = 1)
        @Schema(description = "사용자가 승인한 초안의 capability")
        List<String> capabilities,

        @JsonProperty("allowed_tools")
        @NotNull
        @Size(min = 1)
        @Schema(description = "사용자가 승인한 초안의 Tool 허용 목록")
        List<String> allowedTools
) {}
