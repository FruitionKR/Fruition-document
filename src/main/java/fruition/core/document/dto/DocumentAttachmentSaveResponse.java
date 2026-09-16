package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "본문 저장과 함께 등록된 이미지 asset")
public record DocumentAttachmentSaveResponse(
        @JsonProperty("attachment_id")
        @Schema(description = "문서-asset 연결 ID")
        UUID attachmentId,

        @JsonProperty("asset_id")
        @Schema(description = "asset ID. 내려받기 경로에 넣는다.")
        UUID assetId,

        @JsonProperty("content_path")
        @Schema(description = "Markdown 본문에서 이 이미지를 가리키는 경로")
        String contentPath
) {
}
