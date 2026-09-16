package fruition.core.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record ChatSessionResponse(
        @Schema(description = "채팅 세션 ID", example = "session_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String id,

        @Schema(description = "세션 제목", example = "검색 인덱싱 질문")
        String title,

        @JsonProperty("created_at")
        @Schema(description = "생성 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant createdAt,

        @JsonProperty("last_message_at")
        @Schema(description = "마지막 메시지 시각(ISO-8601 UTC). 목록 정렬에 쓴다.",
                example = "2026-08-13T04:25:24.371948Z")
        Instant lastMessageAt
) {}
