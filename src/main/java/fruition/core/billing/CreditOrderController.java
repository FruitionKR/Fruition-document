package fruition.core.billing;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CreditOrderController {
    private final CreditOrderService orders;

    public CreditOrderController(CreditOrderService orders) {
        this.orders = orders;
    }

    @Operation(summary = "크레딧 충전 주문 생성",
            description = "상품 코드의 금액·크레딧은 서버 상품표로 정합니다. 결제창에 order_id와 amount_krw를 넘깁니다. "
                    + "결제 기능이 꺼져 있으면 404, 없는 상품이면 400입니다.")
    @PostMapping("/api/users/me/credit-orders")
    public CreditOrderService.Order create(@AuthenticationPrincipal String userId,
                                           @Valid @RequestBody CreateRequest request) {
        return orders.create(userId, request.productCode());
    }

    @Operation(summary = "크레딧 충전 결제 승인",
            description = "결제창이 돌려준 payment_key와 금액으로 서버가 PG 승인 API를 호출하고, 주문·금액이 맞으면 크레딧을 지급합니다. "
                    + "같은 주문을 다시 확인해도 한 번만 지급합니다. 앞선 승인 응답을 받지 못해 PG가 이미 처리한 결제라고 하면 "
                    + "PG 결제 조회로 대조해 지급합니다. 금액이 주문과 다르면 PG를 부르지 않고 400, "
                    + "PG 거절 400, PG 응답을 받지 못하면 502(webhook이 복구)입니다.")
    @PostMapping("/api/users/me/credit-orders/{order_id}/confirm")
    public CreditOrderService.Order confirm(@AuthenticationPrincipal String userId,
                                            @PathVariable("order_id") String orderId,
                                            @Valid @RequestBody ConfirmRequest request) {
        return orders.confirm(userId, orderId, request.paymentKey(), request.amount());
    }

    @Operation(summary = "크레딧 충전 환불",
            description = "주문에서 쓰지 않은 크레딧(가용 잔액 한도)만 환불합니다. 금액은 환불 크레딧 비율이며 PG 취소가 성공해야 "
                    + "크레딧을 회수합니다. PG에 이 주문에 기록하지 않은 취소가 있으면(앞선 환불 응답 유실 등) 새로 취소하지 않고 회수만 합니다. "
                    + "환불할 것이 없거나 환불할 수 없는 주문이면 409입니다.")
    @PostMapping("/api/users/me/credit-orders/{order_id}/refund")
    public CreditOrderService.Order refund(@AuthenticationPrincipal String userId,
                                           @PathVariable("order_id") String orderId) {
        return orders.refund(userId, orderId);
    }

    @Operation(summary = "PG 결제 webhook",
            description = "X-Payment-Signature(본문 HMAC-SHA256 hex)를 검증하고 eventId(없으면 본문 해시)로 한 번만 처리합니다. "
                    + "승인 응답을 받지 못한 결제를 지급하고, PG 쪽 취소(CANCELED·PARTIAL_CANCELED)는 PG 결제 조회 결과대로 크레딧을 회수합니다. "
                    + "서명이 틀리면 401입니다.")
    @PostMapping("/internal/payments/webhook")
    public void webhook(@RequestBody String body,
                        @RequestHeader(value = "X-Payment-Signature", required = false) String signature) {
        orders.webhook(body, signature);
    }

    public record CreateRequest(@JsonProperty("product_code") @NotBlank String productCode) {
    }

    public record ConfirmRequest(@JsonProperty("payment_key") @NotBlank String paymentKey,
                                 @JsonProperty("amount") @NotNull Long amount) {
    }
}
