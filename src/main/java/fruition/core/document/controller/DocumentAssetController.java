package fruition.core.document.controller;

import fruition.core.document.service.DocumentAssetReadService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/assets")
@Tag(name = "Document Assets", description = "Markdown 문서 이미지 asset 조회 API")
public class DocumentAssetController {

    private static final CacheControl PRIVATE_CACHE =
            CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate();

    private final DocumentAssetReadService readService;

    public DocumentAssetController(DocumentAssetReadService readService) {
        this.readService = readService;
    }

    @Operation(summary = "문서 이미지 조회", description = "워크스페이스 멤버에게 관리 이미지 bytes를 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "이미지 반환"),
        @ApiResponse(responseCode = "304", description = "캐시된 이미지 사용"),
        @ApiResponse(responseCode = "404", description = "asset 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{asset_id}/content")
    public ResponseEntity<InputStreamResource> getContent(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("asset_id") UUID assetId,
            WebRequest webRequest
    ) {
        var metadata = readService.readMetadata(workspaceId, userId, assetId);
        // 직접 비교하면 W/"..." 약한 validator나 콤마로 이어진 목록에서 304를 놓친다.
        if (webRequest.checkNotModified(metadata.etag())) {
            return ResponseEntity.status(304)
                    .cacheControl(PRIVATE_CACHE)
                    .eTag(metadata.etag())
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(metadata.contentType()))
                .contentLength(metadata.contentLength())
                .cacheControl(PRIVATE_CACHE)
                .eTag(metadata.etag())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(readService.openStream(metadata)));
    }
}
