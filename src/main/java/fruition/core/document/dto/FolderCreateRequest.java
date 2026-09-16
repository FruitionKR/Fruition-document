package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record FolderCreateRequest(
        @NotBlank
        @Schema(description = "폴더 이름", example = "설계")
        String name,

        @JsonProperty("parent_folder_id")
        @Schema(description = "상위 폴더 ID. 생략하면 루트에 만든다.",
                example = "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2")
        UUID parentFolderId
) {
}
