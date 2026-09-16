package fruition.core.wikischema.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Wiki 스키마 초안 생성 요청")
public record WikiSchemaDraftRequest(
        @NotBlank
        @Schema(description = "스키마를 뽑아낼 원본 Markdown", example = "# 설계\n\n## 구성요소")
        String rawMarkdown,

        @Schema(description = "초안 이름. 생략하면 서버가 붙인다.", example = "설계 문서 스키마")
        String name
) {}
