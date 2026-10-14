package fruition.core.wiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Wiki 페이지 본문 수정 결과")
public record WikiPageContentSaveResponse(
        @JsonProperty("page_id")
        @Schema(description = "Wiki 페이지 ID")
        String pageId,

        @Schema(description = "저장 뒤 revision. 본문이 같으면 현재 revision 그대로", example = "4")
        long revision,

        @JsonProperty("updated_at")
        @Schema(description = "이 revision이 만들어진 시각")
        Instant updatedAt,

        @Schema(description = "저장된 본문")
        String markdown
) {}
