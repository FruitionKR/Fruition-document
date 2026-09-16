package fruition.core.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Skill 초안 작성 요청. 실제 작성은 pipeline에 위임한다.")
public record SkillAuthoringRequest(
        @JsonProperty("scope_type")
        @NotBlank
        @Pattern(regexp = "personal|team")
        @Schema(description = "Skill 공개 범위", allowableValues = {"personal", "team"}, example = "personal")
        String scopeType,

        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$")
        @Schema(description = "Skill 이름. 소문자·숫자로 시작하고 하이픈을 쓸 수 있다(1~63자).",
                pattern = "^[a-z0-9][a-z0-9-]{0,62}$", example = "meeting-notes")
        String name,

        @Size(max = 500)
        @Schema(description = "Skill 설명(500자 이하)", maxLength = 500)
        String description,

        @NotBlank
        @Size(max = 30000)
        @Schema(description = "작성 지시문(30000자 이하)", maxLength = 30000)
        String instruction,

        @JsonProperty("authoring_mode")
        @Pattern(regexp = "preserve|enhance|regenerate")
        @Schema(description = "기존 내용을 얼마나 살릴지. preserve는 유지, enhance는 보강, regenerate는 새로 씀.",
                allowableValues = {"preserve", "enhance", "regenerate"},
                defaultValue = "enhance", example = "enhance")
        String authoringMode,

        @JsonProperty("reference_document_ids")
        @Size(max = 3)
        @Schema(description = "작성에 참고할 문서 ID 목록(최대 3개)")
        List<@NotBlank String> referenceDocumentIds
) {
    public SkillAuthoringRequest {
        authoringMode = authoringMode == null ? "enhance" : authoringMode;
        referenceDocumentIds = referenceDocumentIds == null ? List.of() : List.copyOf(referenceDocumentIds);
    }
}
