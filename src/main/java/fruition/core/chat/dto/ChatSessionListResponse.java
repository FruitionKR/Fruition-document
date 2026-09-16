package fruition.core.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record ChatSessionListResponse(
        @Schema(description = "워크스페이스의 채팅 세션 목록")
        List<ChatSessionResponse> sessions) {}
