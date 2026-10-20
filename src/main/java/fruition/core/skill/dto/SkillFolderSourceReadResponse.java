package fruition.core.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record SkillFolderSourceReadResponse(
        @JsonProperty("total_documents") int totalDocuments,
        List<Item> items
) {
    /** markdown은 저장된 최신 본문이다. 편집 상태를 만들지 못했으면 null로 둔다. */
    @Schema(name = "SkillFolderSourceItem")
    public record Item(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("updated_at") Instant updatedAt,
            @Schema(nullable = true) String markdown
    ) {}
}
