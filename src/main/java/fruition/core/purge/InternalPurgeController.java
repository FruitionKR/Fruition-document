package fruition.core.purge;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.shared.util.ErrorResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * access(인증 서비스)가 회원 탈퇴와 워크스페이스 영구 삭제 때 호출하는 데이터 파기 API.
 * 실패하면 5xx를 돌려 access가 다시 호출하게 한다. 같은 요청을 다시 보내도 결과가 같다.
 */
@RestController
public class InternalPurgeController {

    private final DataPurgeService purgeService;
    private final String internalToken;

    public InternalPurgeController(DataPurgeService purgeService,
                                   @Value("${app.internal.callback-token}") String internalToken) {
        this.purgeService = purgeService;
        this.internalToken = internalToken;
    }

    @PostMapping("/internal/purge/workspaces")
    public ResponseEntity<?> purgeWorkspaces(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Validated @RequestBody WorkspacePurgeRequest request) {
        if (!tokenMatches(token)) {
            return unauthorized();
        }
        Map<String, Integer> rows = new LinkedHashMap<>();
        int objects = 0;
        for (String workspaceId : request.workspaceIds()) {
            DataPurgeService.PurgeResult result = purgeService.purgeWorkspace(workspaceId);
            result.deletedRows().forEach((table, count) -> rows.merge(table, count, Integer::sum));
            objects += result.deletedObjects();
        }
        return ResponseEntity.ok(new PurgeResponse(rows, objects));
    }

    @PostMapping("/internal/purge/users")
    public ResponseEntity<?> purgeUser(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Validated @RequestBody UserPurgeRequest request) {
        if (!tokenMatches(token)) {
            return unauthorized();
        }
        DataPurgeService.PurgeResult result = purgeService.purgeUser(request.userId());
        return ResponseEntity.ok(new PurgeResponse(result.deletedRows(), result.deletedObjects()));
    }

    @ExceptionHandler(DataPurgeService.PurgeStorageException.class)
    ResponseEntity<ErrorResponse> storageFailed() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of("PURGE_STORAGE_FAILED", "저장소 객체를 지우지 못했습니다. 다시 시도해 주세요."));
    }

    /** 길이가 달라도 시간차가 새지 않도록 상수 시간 비교를 쓴다. */
    private boolean tokenMatches(String token) {
        return token != null && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<ErrorResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("INVALID_INTERNAL_TOKEN", "내부 토큰이 올바르지 않습니다."));
    }

    record WorkspacePurgeRequest(
            @NotNull @Size(min = 1, max = 100) @JsonProperty("workspace_ids") List<@NotBlank String> workspaceIds) {}

    record UserPurgeRequest(@NotBlank @JsonProperty("user_id") String userId) {}

    record PurgeResponse(@JsonProperty("deleted_rows") Map<String, Integer> deletedRows,
                         @JsonProperty("deleted_objects") int deletedObjects) {}
}
