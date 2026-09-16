package fruition.core.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ChatSessionCreateRequest(
        @Schema(description = "세션 제목. 생략하면 서버가 기본 제목을 붙인다.", example = "검색 인덱싱 질문")
        String title) {}
