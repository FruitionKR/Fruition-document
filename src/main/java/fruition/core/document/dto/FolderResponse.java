package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.document.domain.Folder;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record FolderResponse(
        @Schema(description = "폴더 ID", example = "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2")
        UUID id,

        @JsonProperty("parent_folder_id")
        @Schema(description = "상위 폴더 ID. 루트 폴더면 null이다.")
        UUID parentFolderId,

        @Schema(description = "폴더 이름", example = "설계")
        String name,

        @JsonProperty("sort_order")
        @Schema(description = "같은 부모 안에서의 정렬 순서", example = "1024")
        long sortOrder,

        @JsonProperty("current_version")
        @Schema(description = "낙관적 잠금 버전. 이후 쓰기 요청의 base_version에 그대로 넣는다.", example = "1")
        long currentVersion,

        @JsonProperty("created_at")
        @Schema(description = "생성 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant createdAt,

        @JsonProperty("updated_at")
        @Schema(description = "마지막 변경 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant updatedAt
) {
    public static FolderResponse from(Folder folder) {
        return new FolderResponse(
                folder.getId(),
                folder.getParentFolderId(),
                folder.getName(),
                folder.getSortOrder(),
                folder.getCurrentVersion(),
                folder.getCreatedAt(),
                folder.getUpdatedAt()
        );
    }
}
