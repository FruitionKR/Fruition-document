package fruition.core.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NotificationListResponse(
        @Schema(description = "나에게 온 알림. 최신순")
        List<Notification> notifications
) {

    public record Notification(
            @Schema(description = "알림 ID")
            UUID id,

            @Schema(description = "edit_conflict_registered(OWNER 대상) | edit_conflict_resolved(충돌 작성자 대상)",
                    example = "edit_conflict_registered")
            String type,

            @Schema(description = "type별 내용. edit_conflict_registered: conflict_id, document_id, document_name, author_user_id."
                    + " edit_conflict_resolved: conflict_id, document_id, choice, resolved_by")
            JsonNode payload,

            @Schema(description = "내가 읽었는지")
            boolean read,

            @JsonProperty("created_at")
            @Schema(description = "알림 생성 시각. 다음 페이지 요청의 before로 쓴다.")
            Instant createdAt
    ) {}
}
