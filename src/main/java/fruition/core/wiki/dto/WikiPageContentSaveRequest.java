package fruition.core.wiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Wiki 페이지 본문 수정 요청")
public record WikiPageContentSaveRequest(
        @Schema(description = "저장할 전체 Markdown 본문(UTF-8 5MB 이하)")
        String markdown,

        @JsonProperty("base_revision")
        @Schema(description = "클라이언트가 조회한 현재 revision(상세 조회의 revision). 버전 이력이 없는 페이지는 0", example = "3")
        Long baseRevision,

        @JsonProperty("revision_write_id")
        @Schema(description = "같은 저장 재시도에 재사용하는 write ID(1~255자)")
        String revisionWriteId
) {}
