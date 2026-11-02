package fruition.core.upload;

import fruition.core.authz.WorkspaceAccessGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 내 저장 공간 사용량. 한도는 사용자 단위이므로 어느 워크스페이스에서 불러도 같은 값을 돌려준다. */
@Tag(name = "Storage", description = "사용자별 업로드 저장 공간")
@RestController
public class StorageUsageController {
    private final UploadLimitService limits;
    private final WorkspaceAccessGuard access;

    public StorageUsageController(UploadLimitService limits, WorkspaceAccessGuard access) {
        this.limits = limits;
        this.access = access;
    }

    @Schema(name = "StorageUsageResponse")
    public record StorageUsageResponse(
            @Schema(description = "내가 올린 문서·이미지·회의 녹음의 바이트 합(모든 워크스페이스, 휴지통 포함)") long used_bytes,
            @Schema(description = "저장 공간 한도(바이트). 한도가 꺼져 있으면 null", nullable = true) Long limit_bytes,
            @Schema(description = "한도 적용 단위. 항상 user", example = "user") String scope) {}

    @Operation(operationId = "getStorageUsage", summary = "내 저장 공간 사용량",
            description = "워크스페이스 멤버만 부를 수 있습니다. 멤버가 아니면 404 WORKSPACE_NOT_FOUND입니다.")
    @GetMapping("/api/workspaces/{workspace_id}/storage")
    public StorageUsageResponse usage(@PathVariable("workspace_id") String workspaceId,
                                      @AuthenticationPrincipal String userId) {
        access.requireMember(workspaceId, userId);
        return new StorageUsageResponse(limits.usedBytes(userId), limits.limitBytes(), "user");
    }
}
