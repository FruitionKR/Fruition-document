package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record DocumentBlocksResponse(
        @JsonProperty("document_id")
        @Schema(description = "문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String documentId,

        @JsonProperty("source_content_hash")
        @Schema(description = "이 block 집합을 만든 ingest 입력 Markdown의 SHA-256(AI가 저장한 값). 알 수 없으면 null",
                nullable = true)
        String sourceContentHash,

        @JsonProperty("current_content_hash")
        @Schema(description = "source_content_hash와 비교하는 현재 해시. 편집 문서는 현재 본문, chat_export는 AI 입력 Markdown의 SHA-256",
                nullable = true)
        String currentContentHash,

        @JsonProperty("is_stale")
        @Schema(description = "block을 만든 뒤 문서가 바뀌었으면 true. source_content_hash를 모르면 판단할 수 없어 null",
                nullable = true)
        Boolean isStale,

        @Schema(description = "원문 block 목록. 문서 내 순서(position) 오름차순이다. Wiki 근거 링크가 이 block을 가리킨다.")
        List<DocumentBlockResponse> blocks
) {}
