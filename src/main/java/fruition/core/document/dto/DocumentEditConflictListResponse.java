package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record DocumentEditConflictListResponse(
        @Schema(description = "미해결 충돌. 오래된 순")
        List<Item> conflicts
) {

    public record Item(
            @Schema(description = "충돌 본")
            DocumentEditConflictResponse conflict,

            @JsonProperty("document_name")
            @Schema(description = "문서 이름")
            String documentName,

            @Schema(description = "지금 서버에 있는 본문")
            Server server
    ) {}

    public record Server(
            @Schema(description = "서버 현재 Markdown 본문")
            String markdown,

            @Schema(description = "서버 현재 revision. 해결 요청의 base_revision으로 쓴다.", example = "5")
            long revision,

            @JsonProperty("updated_by")
            @Schema(description = "마지막으로 본문을 저장한 사용자")
            String updatedBy,

            @JsonProperty("updated_at")
            @Schema(description = "서버 본문 마지막 저장 시각")
            Instant updatedAt
    ) {}
}
