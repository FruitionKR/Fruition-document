package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

public record DocumentBlockResponse(
        @JsonProperty("block_id")
        @Schema(description = "영구 block ID. 채팅 근거의 source_block_ids, source_refs[].source_block_id와 같은 값이다.",
                example = "B0440")
        String blockId,

        @Schema(description = "block을 만든 Markdown 스냅샷 안에서의 순서(1부터). 위치 정보가 없는 block이면 null",
                example = "2", nullable = true)
        Integer position,

        @JsonProperty("line_start")
        @Schema(description = "스냅샷 기준 시작 줄(1부터, 포함). 위치 정보가 없는 block(chat_export 등)이면 null",
                example = "3", nullable = true)
        Integer lineStart,

        @JsonProperty("line_end")
        @Schema(description = "스냅샷 기준 끝 줄(1부터, 포함). 위치 정보가 없는 block이면 null",
                example = "5", nullable = true)
        Integer lineEnd,

        @JsonProperty("block_type")
        @Schema(description = "block 종류(heading, paragraph, list, code). 알 수 없으면 null",
                example = "paragraph", nullable = true)
        String blockType,

        @Schema(description = "저장된 block 텍스트(공백 정규화됨)")
        String text
) {}
