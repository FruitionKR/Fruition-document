package fruition.core.usage.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.usage.service.ModelUsageService;
import fruition.core.usage.service.UsageSettlementService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/usage")
public class ModelUsageController {
    private final ModelUsageService service;
    private final UsageSettlementService settlements;

    public ModelUsageController(ModelUsageService service, UsageSettlementService settlements) {
        this.service = service;
        this.settlements = settlements;
    }

    @Operation(summary = "본인의 모델별 토큰 사용량 조회", description = "AI 저장소에서 사용량을 조회합니다. 기본 기간은 UTC 이번 달이며 실제 청구액은 포함하지 않습니다.")
    @GetMapping("/models")
    public JsonNode read(@PathVariable("workspace_id") String workspaceId,
                         @AuthenticationPrincipal String userId,
                         @RequestParam(value = "from_at", required = false) Instant from,
                         @RequestParam(value = "to_at", required = false) Instant to) {
        return service.read(workspaceId, userId, from, to);
    }

    @Operation(summary = "AI 사용량 정산 미리보기",
            description = "OWNER만 호출합니다. 기간 [from_at, to_at)에 멤버였던 사용자(탈퇴·제거 포함)별로 모델 단가(USD / 1M tokens)를 곱한 금액을 계산하고 저장하지 않습니다. 단가가 없는 모델은 amount_usd가 null이고 price_missing이 true이며 합계에서 빠집니다. 기간은 366일 이하입니다.")
    @GetMapping("/settlement")
    public UsageSettlementService.Settlement preview(@PathVariable("workspace_id") String workspaceId,
                                                     @AuthenticationPrincipal String userId,
                                                     @RequestParam("from_at") Instant from,
                                                     @RequestParam("to_at") Instant to) {
        return settlements.preview(workspaceId, userId, from, to);
    }

    @Operation(summary = "AI 사용량 정산 마감",
            description = "OWNER만 호출합니다. 계산 결과를 저장해 이후 단가가 바뀌어도 금액이 변하지 않습니다. 같은 기간을 다시 마감하면 저장된 결과를 돌려주고, 마감한 기간과 겹치면 409입니다.")
    @PostMapping("/settlements")
    public UsageSettlementService.Settlement close(@PathVariable("workspace_id") String workspaceId,
                                                   @AuthenticationPrincipal String userId,
                                                   @Valid @RequestBody SettlementRequest request) {
        return settlements.close(workspaceId, userId, request.fromAt(), request.toAt());
    }

    @Operation(summary = "마감한 AI 사용량 정산 목록", description = "OWNER만 호출합니다. 최근 기간부터 반환합니다.")
    @GetMapping("/settlements")
    public List<UsageSettlementService.Settlement> closed(@PathVariable("workspace_id") String workspaceId,
                                                          @AuthenticationPrincipal String userId) {
        return settlements.closed(workspaceId, userId);
    }

    public record SettlementRequest(@JsonProperty("from_at") @NotNull Instant fromAt,
                                    @JsonProperty("to_at") @NotNull Instant toAt) {
    }
}
