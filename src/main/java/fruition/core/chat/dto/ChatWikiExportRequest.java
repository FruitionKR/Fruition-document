package fruition.core.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 채팅 Wiki page화 export 요청.
 *
 * @param pairIds 위키화할 문답(pair) id 목록. 세션 전체를 내보낼 때도 모든 pair id를 담아 보낸다.
 */
@Schema(description = "채팅 내용을 Markdown 원문 문서로 저장하는 요청")
public record ChatWikiExportRequest(
        @JsonProperty("pair_ids")
        @Schema(description = "내보낼 문답(pair) ID 목록. 비어 있을 수 없다.")
        List<String> pairIds
) {
}
