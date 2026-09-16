package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

public record DocumentBlockResponse(
        @JsonProperty("block_id")
        @Schema(description = "block ID. Wiki 근거가 이 값으로 원문 위치를 가리킨다.")
        String blockId,

        @Schema(description = "block 본문 텍스트")
        String text
) {}
