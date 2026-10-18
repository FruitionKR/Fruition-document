package fruition.core.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "저장하지 않는 Skill 테스트 실행 요청. 게시 전 정의를 문서 하나에 적용해 본다.")
public record SkillTestRunRequest(
        @NotBlank
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$")
        @Schema(description = "Skill 이름. 소문자·숫자로 시작하고 하이픈을 쓸 수 있다(1~63자).",
                pattern = "^[a-z0-9][a-z0-9-]{0,62}$", example = "research-summary")
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
        @Schema(description = "Skill capability. Tool 허용 목록은 AI가 이 값으로 계산한다.")
        List<@NotBlank String> capabilities,

        @JsonProperty("document_id")
        @NotBlank
        @Schema(description = "테스트할 문서 ID. 읽기 권한이 있어야 한다.", example = "doc_123")
        String documentId,

        @Schema(description = "에디터의 저장 안 된 본문. 생략하면 저장된 최신 본문을 쓴다.")
        String markdown,

        @Valid
        @Schema(description = "적용 범위. 생략하면 문서 전체다.")
        Target target
) {

    @Schema(description = "적용 범위(1-based 줄 번호). Agent 턴 editor_snapshot.target과 같은 형식이다.")
    public record Target(
            @NotBlank
            @Pattern(regexp = "selection|current_section|whole_document")
            @Schema(description = "범위 종류", allowableValues = {"selection", "current_section", "whole_document"},
                    example = "selection")
            String type,

            @JsonProperty("start_line")
            @Min(1)
            @Schema(description = "시작 줄 번호(1부터)", minimum = "1", example = "10")
            int startLine,

            @JsonProperty("end_line")
            @Min(1)
            @Schema(description = "끝 줄 번호(1부터, 포함)", minimum = "1", example = "24")
            int endLine
    ) {}
}
