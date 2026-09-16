package fruition.core.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatSessionRenameRequest(
        @NotBlank
        @Size(max = 255)
        @Schema(description = "새 세션 제목", example = "검색 인덱싱 질문")
        String title) {}
