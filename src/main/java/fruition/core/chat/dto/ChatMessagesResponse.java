package fruition.core.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record ChatMessagesResponse(
        @Schema(description = "세션의 메시지 기록. 오래된 것부터 온다.")
        List<ChatMessageResponse> messages) {}
