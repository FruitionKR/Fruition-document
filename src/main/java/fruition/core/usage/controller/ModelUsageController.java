package fruition.core.usage.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.usage.service.ModelUsageService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/usage")
public class ModelUsageController {
    private final ModelUsageService service;
    public ModelUsageController(ModelUsageService service) { this.service = service; }

    @Operation(summary = "본인의 모델별 토큰 사용량 조회", description = "AI 저장소에서 사용량을 조회합니다. 기본 기간은 UTC 이번 달이며 실제 청구액은 포함하지 않습니다.")
    @GetMapping("/models")
    public JsonNode read(@PathVariable("workspace_id") String workspaceId,
                         @AuthenticationPrincipal String userId,
                         @RequestParam(value = "from_at", required = false) Instant from,
                         @RequestParam(value = "to_at", required = false) Instant to) {
        return service.read(workspaceId, userId, from, to);
    }
}
