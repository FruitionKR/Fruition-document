package fruition.core.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * @param totalDocuments 읽을 수 있고 본문이 있는 문서 전체 수(max_documents로 자르기 전). 본문이 없는 문서는 세지 않는다.
 */
public record SkillFolderSourceReadResponse(
        @JsonProperty("total_documents") int totalDocuments,
        List<Item> items
) {
    /** markdown은 저장된 최신 본문이다. 본문을 만들지 못한 문서는 항목으로 싣지 않는다. */
    @Schema(name = "SkillFolderSourceItem")
    public record Item(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("updated_at") Instant updatedAt,
            String markdown
    ) {}
}
