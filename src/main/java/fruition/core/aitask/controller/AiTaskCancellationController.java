package fruition.core.aitask.controller;

import fruition.core.aitask.service.AiTaskCancellationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

@RestController
public class AiTaskCancellationController {
    private final AiTaskCancellationService service;

    public AiTaskCancellationController(AiTaskCancellationService service) { this.service = service; }

    @PostMapping("/api/workspaces/{workspaceId}/ai/tasks/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String workspaceId, @PathVariable String id,
                                     @AuthenticationPrincipal String userId) {
        return service.cancel(id, workspaceId, userId);
    }

    @GetMapping("/api/workspaces/{workspaceId}/ai/tasks/{id}")
    public Map<String, Object> status(@PathVariable String workspaceId, @PathVariable String id,
                                     @AuthenticationPrincipal String userId) {
        return service.status(id, workspaceId, userId);
    }

    @PostMapping("/internal/agent/tools/rollback/{id}/changes")
    public List<Long> changes(@PathVariable String id, @Valid @RequestBody Actor actor) {
        return service.changes(id, actor.workspaceId(), actor.userId());
    }

    @PostMapping("/internal/agent/tools/rollback/{id}/changes/{changeId}")
    public void undo(@PathVariable String id, @PathVariable long changeId, @Valid @RequestBody Actor actor) {
        try {
            service.undo(id, actor.workspaceId(), actor.userId(), changeId);
        } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "변경 이후 값 또는 참조가 달라 복구할 수 없습니다.");
        }
    }

    @PostMapping("/internal/agent/tools/rollback/{id}/finalize-edits")
    public List<Map<String, Object>> finalizeEdits(@PathVariable String id, @Valid @RequestBody Actor actor) {
        try {
            return service.finalizeEdits(id, actor.workspaceId(), actor.userId());
        } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "복구한 본문이 다시 변경되었습니다.");
        }
    }

    public record Actor(@NotBlank @JsonProperty("workspace_id") String workspaceId,
                        @NotBlank @JsonProperty("user_id") String userId) {}
}
