package fruition.core.wiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

public record WikiPageRenameRequest(
        @Schema(description = "새 페이지 제목", example = "검색 인덱싱")
        String title,

        @JsonProperty("update_slug")
        @Schema(description = "제목에 맞춰 slug도 바꿀지 여부. slug가 바뀌면 기존 링크가 깨질 수 있다.",
                example = "false")
        Boolean updateSlug
) {}
