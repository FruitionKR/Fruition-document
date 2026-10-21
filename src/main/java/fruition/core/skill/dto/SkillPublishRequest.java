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
        @Schema(description = "사용자가 승인한 초안의 Tool 허용 목록. 생략하거나 []이면 AI가 capabilities로 계산한다.")
        List<@NotBlank String> allowedTools,

        @Size(max = 2)
        @Schema(description = "실행 대상. 문서 Skill에서만 의미가 있다. 생략하면 AI 기본값([\"current_document\"])을 쓴다.")
        List<@NotBlank @Pattern(regexp = "current_document|selection") String> targets,

        @JsonProperty("result_mode")
        @Pattern(regexp = "create|edit|auto")
        @Schema(description = "결과 방식. create는 새 문서, edit는 현재 문서 수정, auto는 상황에 맞게. capabilities와 맞아야 한다(AI가 검증). 생략하면 키를 보내지 않는다(기본값은 AI가 정함).",
                allowableValues = {"create", "edit", "auto"}, example = "auto")
        String resultMode,

        @JsonProperty("requires_source")
        @Schema(description = "원문 문서가 필요한 Skill인지 여부. 사용자가 명시한 값이다. 생략(null)하면 키를 보내지 않고 AI가 판단한다.",
                nullable = true, example = "true")
        Boolean requiresSource
) {}
