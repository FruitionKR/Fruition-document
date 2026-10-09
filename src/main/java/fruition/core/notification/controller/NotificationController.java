package fruition.core.notification.controller;

import fruition.core.notification.dto.NotificationListResponse;
import fruition.core.notification.service.NotificationService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@Tag(name = "Notifications", description = "앱 안 알림 목록과 읽음 처리")
@RestController
@RequestMapping("/api/workspaces/{workspace_id}/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(summary = "내 알림 목록",
            description = "나에게 온 알림과, OWNER면 OWNER 대상 알림을 최신순으로 반환합니다."
                    + " 다음 페이지는 마지막 항목의 created_at을 before로 보냅니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<NotificationListResponse> listNotifications(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @RequestParam(value = "unread_only", defaultValue = "false") boolean unreadOnly,
            @Schema(description = "기본 50, 최대 100") @RequestParam(value = "limit", required = false) Integer limit,
            @Schema(description = "이 시각보다 먼저 만든 알림만") @RequestParam(value = "before", required = false) Instant before) {
        return ResponseEntity.ok(notificationService.list(workspaceId, userId, unreadOnly, limit, before));
    }

    @Operation(summary = "알림 읽음 처리", description = "이미 읽은 알림이어도 204입니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "읽음 처리됨"),
        @ApiResponse(responseCode = "404", description = "내가 볼 수 없는 알림(NOTIFICATION_NOT_FOUND) 또는 워크스페이스 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{notification_id}/read")
    public ResponseEntity<Void> readNotification(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("notification_id") UUID notificationId) {
        notificationService.markRead(workspaceId, userId, notificationId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "알림 전부 읽음 처리", description = "지금 나에게 보이는 알림을 모두 읽음으로 표시합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "읽음 처리됨"),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/read-all")
    public ResponseEntity<Void> readAllNotifications(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        notificationService.markAllRead(workspaceId, userId);
        return ResponseEntity.noContent().build();
    }
}
