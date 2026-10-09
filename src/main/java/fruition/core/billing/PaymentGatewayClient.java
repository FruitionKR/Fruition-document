package fruition.core.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;

/**
 * PG 승인·조회·취소 호출(#80). 요청·응답 형식은 토스페이먼츠 결제 승인·조회·취소 API를 기준으로 했다.
 * PG를 정하면 이 클래스만 맞춘다(ADR-0026). 비밀키는 Basic 인증 사용자 이름으로 보낸다.
 */
@Component
public class PaymentGatewayClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;
    private final String confirmEndpoint;
    private final String paymentEndpoint;
    private final String cancelEndpoint;

    public PaymentGatewayClient(PipelineClientFactory factory,
                                @Value("${app.billing.pg.confirm-endpoint}") String confirmEndpoint,
                                @Value("${app.billing.pg.payment-endpoint}") String paymentEndpoint,
                                @Value("${app.billing.pg.cancel-endpoint}") String cancelEndpoint,
                                @Value("${app.billing.pg.secret-key:}") String secretKey) {
        // 내부 인증 헤더가 붙는 restClient()는 쓰지 않는다. timeout 설정만 가져온다.
        this.client = RestClient.builder().requestFactory(factory.requestFactory(30))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
                        .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8)))
                .build();
        this.confirmEndpoint = confirmEndpoint;
        this.paymentEndpoint = paymentEndpoint;
        this.cancelEndpoint = cancelEndpoint;
    }

    /**
     * PG의 결제. status가 {@code DONE}이고 주문·금액이 맞아야 지급한다. balanceAmount는 취소하고 남은 금액이며
     * 응답에 없으면 -1이다.
     */
    public record Payment(String paymentKey, String orderId, String status, long totalAmount, long balanceAmount) {
        static Payment from(JsonNode body) {
            return new Payment(body.path("paymentKey").asText(null), body.path("orderId").asText(null),
                    body.path("status").asText(null), body.path("totalAmount").asLong(-1),
                    body.path("balanceAmount").asLong(-1));
        }
    }

    /** PG가 거절한 요청. code는 PG 오류 코드다. */
    public static class PaymentRejectedException extends RuntimeException {
        private final String code;

        PaymentRejectedException(String code) {
            super("PG가 요청을 거절했습니다: " + code);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    public Payment confirm(String paymentKey, String orderId, long amount) {
        return Payment.from(post(confirmEndpoint, null,
                Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount)));
    }

    /** 승인 응답을 받지 못했거나 취소 결과를 모를 때 PG에 남은 결제 상태를 확인한다. */
    public Payment find(String paymentKey) {
        return Payment.from(send(() -> client.get().uri(paymentEndpoint, paymentKey).retrieve().body(JsonNode.class)));
    }

    /** 부분 취소를 포함한다. 같은 멱등 키로 다시 보내면 PG는 한 번만 취소한다. */
    public void cancel(String paymentKey, long cancelAmount, String reason, String idempotencyKey) {
        post(cancelEndpoint.replace("{paymentKey}", paymentKey), idempotencyKey,
                Map.of("cancelReason", reason, "cancelAmount", cancelAmount));
    }

    /** PG 오류 응답 {@code {"code", "message"}}의 code. 형식이 다르면 UNKNOWN이다. */
    private static String errorCode(String body) {
        try {
            return MAPPER.readTree(body).path("code").asText("UNKNOWN");
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private JsonNode post(String uri, String idempotencyKey, Map<String, Object> body) {
        var request = client.post().uri(uri).contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        return send(() -> request.body(body).retrieve().body(JsonNode.class));
    }

    private static JsonNode send(Supplier<JsonNode> call) {
        try {
            JsonNode response = call.get();
            if (response == null) throw new IllegalStateException("PG 응답이 비어 있습니다.");
            return response;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new PaymentRejectedException(errorCode(e.getResponseBodyAsString()));
            }
            throw e;
        }
    }
}
