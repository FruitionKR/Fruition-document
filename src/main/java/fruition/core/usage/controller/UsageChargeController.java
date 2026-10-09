package fruition.core.usage.controller;

import fruition.core.usage.service.UsageChargeService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
public class UsageChargeController {
    private final UsageChargeService charges;

    public UsageChargeController(UsageChargeService charges) {
        this.charges = charges;
    }

    @Operation(summary = "본인의 AI 사용 금액 조회",
            description = "호출 시작 시각이 [from_at, to_at)인 본인 호출의 금액을 워크스페이스를 가로질러 합쳐 모델별로 반환합니다. "
                    + "금액은 부가세 포함 milli-KRW 정수입니다. 단가가 없는 호출(unpriced_calls)은 합계에 들어가지 않고, "
                    + "토큰을 모르는 호출(needs_review_calls)은 0원입니다. 기간은 366일 이하입니다.")
    @GetMapping("/api/users/me/usage/charges")
    public UsageChargeService.ChargeSummary read(@AuthenticationPrincipal String userId,
                                                 @RequestParam("from_at") Instant from,
                                                 @RequestParam("to_at") Instant to) {
        return charges.summary(userId, from, to);
    }
}
