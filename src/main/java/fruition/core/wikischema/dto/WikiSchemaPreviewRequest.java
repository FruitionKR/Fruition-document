package fruition.core.wikischema.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Wiki 스키마 미리보기 요청. 저장하지 않고 결과만 돌려준다.")
public record WikiSchemaPreviewRequest(
        @NotBlank
        @Schema(description = "스키마를 뽑아낼 원본 Markdown", example = "# 설계\n\n## 구성요소")
        String rawMarkdown
) {}
