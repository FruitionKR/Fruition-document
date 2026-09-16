package fruition.core.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "이미 게시된 Skill의 내용 수정 요청. scope_type은 바꿀 수 없다.")
public record SkillUpdateRequest(
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
        String instructionsMarkdown
) {}
