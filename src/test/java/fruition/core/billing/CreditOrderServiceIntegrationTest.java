package fruition.core.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import fruition.TestcontainersConfiguration;
import fruition.core.usage.service.CreditService;
import fruition.shared.http.PipelineClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PG는 JDK 내장 HTTP 서버로 대신한다. 사용자·주문은 테스트마다 새로 만든다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CreditOrderServiceIntegrationTest {

    private static final String SECRET = "webhook-secret";

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired CreditService credits;
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;

    private HttpServer server;
    private PaymentGatewayClient gateway;
    private final List<String> confirms = new CopyOnWriteArrayList<>();
    private final List<String> cancels = new CopyOnWriteArrayList<>();
    /** 0보다 크면 PG가 이 금액으로 승인했다고 답한다. */
    private final AtomicLong pgAmountOverride = new AtomicLong();
    /** PG가 승인·취소한 결제(paymentKey별). 조회 API가 이 상태를 돌려준다. */
    private final Map<String, ObjectNode> payments = new ConcurrentHashMap<>();
    private final Map<String, String> cancelsByKey = new ConcurrentHashMap<>();
    /** true면 PG가 다음 승인·취소를 처리하고 응답만 잃는다(500). */
    private final AtomicBoolean loseNextResponse = new AtomicBoolean();
    private final String user = "user-" + UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/payments", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String path = exchange.getRequestURI().getPath();
            String paymentKey = path.split("/")[3];
            int status = 200;
            String response;
            if (path.endsWith("/confirm")) {
                confirms.add(body + " " + auth);
                JsonNode request = mapper.readTree(body);
                if (payments.containsKey(request.path("paymentKey").asText())) {
                    status = 400;
                    response = "{\"code\":\"ALREADY_PROCESSED_PAYMENT\",\"message\":\"이미 처리된 결제 입니다\"}";
                } else {
                    long amount = pgAmountOverride.get() > 0 ? pgAmountOverride.get() : request.path("amount").asLong();
                    ObjectNode payment = mapper.createObjectNode().put("paymentKey", request.path("paymentKey").asText())
                            .put("orderId", request.path("orderId").asText()).put("status", "DONE")
                            .put("totalAmount", amount).put("balanceAmount", amount);
                    payments.put(payment.path("paymentKey").asText(), payment);
                    response = payment.toString();
                }
            } else if (path.endsWith("/cancel")) {
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                cancels.add(path + " " + body + " key=" + key);
                // 같은 멱등 키는 한 번만 취소한다.
                response = cancelsByKey.computeIfAbsent(key, k -> cancel(paymentKey, body));
            } else {
                ObjectNode payment = payments.get(paymentKey);
                status = payment == null ? 404 : 200;
                response = payment == null ? "{\"code\":\"NOT_FOUND_PAYMENT\"}" : payment.toString();
            }
            if (exchange.getRequestMethod().equals("POST") && loseNextResponse.compareAndSet(true, false)) {
                status = 500;
                response = "{}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/payments";
        gateway = new PaymentGatewayClient(new PipelineClientFactory("internal-test"), base + "/confirm",
                base + "/{paymentKey}", base + "/{paymentKey}/cancel", "test_sk");
    }

    /** PG 결제의 남은 금액을 줄이고 결제를 돌려준다. */
    private String cancel(String paymentKey, String body) {
        try {
            ObjectNode payment = payments.get(paymentKey);
            long balance = payment.path("balanceAmount").asLong() - mapper.readTree(body).path("cancelAmount").asLong();
            payment.put("balanceAmount", balance).put("status", balance == 0 ? "CANCELED" : "PARTIAL_CANCELED");
            return payment.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void confirmRetriesAndDuplicateWebhooksGrantCreditOnce() {
        var orders = orders(true);
        var order = orders.create(user, "CREDIT_10000");
        assertThat(order.amountKrw()).isEqualTo(10_000);

        orders.confirm(user, order.orderId(), "pay-1", 10_000);
        var again = orders.confirm(user, order.orderId(), "pay-1", 10_000);
        String webhook = doneEvent("evt-" + UUID.randomUUID(), order.orderId(), 10_000);
        orders.webhook(webhook, sign(webhook));
        orders.webhook(webhook, sign(webhook));
        String other = doneEvent("evt-" + UUID.randomUUID(), order.orderId(), 10_000);
        orders.webhook(other, sign(other));

        assertThat(again.status()).isEqualTo("paid");
        assertThat(confirms).hasSize(1);
        // 비밀키는 Basic 인증 사용자 이름으로 보낸다.
        assertThat(confirms.getFirst()).endsWith("Basic dGVzdF9zazo=");
        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(10_000_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries WHERE user_id = ? AND type = 'purchase'",
                Long.class, user)).isEqualTo(1);
    }

    @Test
    void webhookRecoversConfirmThatNeverReturned() {
        var orders = orders(true);
        var order = orders.create(user, "CREDIT_30000");
        String webhook = doneEvent("evt-" + UUID.randomUUID(), order.orderId(), 30_000);

        orders.webhook(webhook, sign(webhook));

        assertThat(orders.confirm(user, order.orderId(), "pay-2", 30_000).status()).isEqualTo("paid");
        assertThat(confirms).isEmpty();
        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(30_000_000);
    }

    @Test
    void tamperedAmountsNeverGrantCredit() {
        var orders = orders(true);
        var order = orders.create(user, "CREDIT_10000");

        // 브라우저가 보낸 금액이 주문과 다르면 PG를 부르지 않는다.
        assertThatThrownBy(() -> orders.confirm(user, order.orderId(), "pay-3", 100))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThat(confirms).isEmpty();

        // PG가 다른 금액으로 승인했다고 답해도 지급하지 않는다.
        pgAmountOverride.set(100);
        assertThatThrownBy(() -> orders.confirm(user, order.orderId(), "pay-3", 10_000))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
        String webhook = doneEvent("evt-" + UUID.randomUUID(), order.orderId(), 100);
        orders.webhook(webhook, sign(webhook));

        assertThat(credits.credits(user).balanceKrwMilli()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM credit_orders WHERE order_id = ?", String.class, order.orderId()))
                .isEqualTo("created");
    }

    @Test
    void webhookWithWrongSignatureIsRejected() {
        var orders = orders(true);
        var order = orders.create(user, "CREDIT_10000");
        String webhook = doneEvent("evt-" + UUID.randomUUID(), order.orderId(), 10_000);

        assertThatThrownBy(() -> orders.webhook(webhook, sign(webhook.replace("10000", "10001"))))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        assertThatThrownBy(() -> orders.webhook(webhook, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        assertThat(credits.credits(user).balanceKrwMilli()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_events WHERE order_id = ?", Long.class, order.orderId()))
                .isZero();
    }

    @Test
    void refundCancelsPaymentAndTakesBackOnlyUnusedCredit() {
        var orders = orders(true);
        var order = orders.create(user, "CREDIT_10000");
        orders.confirm(user, order.orderId(), "pay-4", 10_000);
        // 3,000원어치를 이미 썼다.
        credits.post(user, "charge", -3_000_000, "test-charge:" + UUID.randomUUID(), null);

        var refunded = orders.refund(user, order.orderId());

        assertThat(refunded.status()).isEqualTo("partially_refunded");
        assertThat(refunded.refundedKrw()).isEqualTo(7_000);
        assertThat(cancels).singleElement().satisfies(cancel -> assertThat(cancel)
                .startsWith("/v1/payments/pay-4/cancel").contains("\"cancelAmount\":7000")
                .endsWith("key=refund:" + order.orderId() + ":0"));
        var result = credits.credits(user);
        assertThat(result.balanceKrwMilli()).isZero();
        assertThat(result.entries().getFirst().type()).isEqualTo("refund");
        assertThat(result.entries().getFirst().amountKrwMilli()).isEqualTo(-7_000_000);

        // 남은 미사용 크레딧이 없으면 PG를 부르지 않고 거절한다.
        assertThatThrownBy(() -> orders.refund(user, order.orderId()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(cancels).hasSize(1);
    }

    @Test
    void confirmWhoseResponseWasLostIsPaidOnceAfterPgSaysAlreadyProcessed() {
        var orders = orders(true);
        var order = orders.create(user, "CREDIT_10000");
        loseNextResponse.set(true);

        // PG는 승인했지만 응답을 받지 못했다. 주문은 created로 남는다.
        assertThatThrownBy(() -> orders.confirm(user, order.orderId(), "pay-lost", 10_000))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
        assertThat(credits.credits(user).balanceKrwMilli()).isZero();

        // 다시 확인하면 PG가 이미 처리했다고 거절하고, 결제 조회로 주문·금액을 대조해 지급한다.
        assertThat(orders.confirm(user, order.orderId(), "pay-lost", 10_000).status()).isEqualTo("paid");
        assertThat(orders.confirm(user, order.orderId(), "pay-lost", 10_000).status()).isEqualTo("paid");
        assertThat(confirms).hasSize(2);
        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(10_000_000);
    }

    @Test
    void alreadyProcessedPaymentOfAnotherOrderIsNotGranted() {
        var orders = orders(true);
        var paid = orders.create(user, "CREDIT_10000");
        orders.confirm(user, paid.orderId(), "pay-other", 10_000);
        var order = orders.create(user, "CREDIT_10000");

        // 다른 주문의 결제 키로 확인하면 조회한 결제의 주문이 달라 지급하지 않는다.
        assertThatThrownBy(() -> orders.confirm(user, order.orderId(), "pay-other", 10_000))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(10_000_000);
        assertThat(jdbc.queryForObject("SELECT status FROM credit_orders WHERE order_id = ?", String.class, order.orderId()))
                .isEqualTo("created");
    }

    @Test
    void refundWhoseCancelResponseWasLostOnlyTakesBackCreditOnRetry() {
        var orders = orders(true);
        var order = orders.create(user, "CREDIT_10000");
        orders.confirm(user, order.orderId(), "pay-5", 10_000);
        loseNextResponse.set(true);

        // PG는 취소했지만 응답을 받지 못했다. 크레딧은 아직 그대로다.
        assertThatThrownBy(() -> orders.refund(user, order.orderId()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(10_000_000);

        // 다시 요청하면 PG 조회로 취소를 확인하고 새로 취소하지 않은 채 회수만 한다.
        var refunded = orders.refund(user, order.orderId());

        assertThat(refunded.status()).isEqualTo("refunded");
        assertThat(refunded.refundedKrw()).isEqualTo(10_000);
        assertThat(cancels).hasSize(1);
        assertThat(credits.credits(user).balanceKrwMilli()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries WHERE user_id = ? AND type = 'refund'",
                Long.class, user)).isEqualTo(1);
    }

    @Test
    void cancelMadeOnPgSideIsReflectedByWebhookOnce() {
        var orders = orders(true);
        var order = orders.create(user, "CREDIT_10000");
        orders.confirm(user, order.orderId(), "pay-6", 10_000);
        // PG 관리자 콘솔에서 4,000원을 부분 취소했다.
        payments.get("pay-6").put("balanceAmount", 6_000).put("status", "PARTIAL_CANCELED");

        String webhook = statusEvent("evt-" + UUID.randomUUID(), "pay-6", order.orderId(), "PARTIAL_CANCELED");
        orders.webhook(webhook, sign(webhook));
        String again = statusEvent("evt-" + UUID.randomUUID(), "pay-6", order.orderId(), "PARTIAL_CANCELED");
        orders.webhook(again, sign(again));

        var partial = jdbc.queryForMap("SELECT status, refunded_krw, refunded_credit_milli FROM credit_orders WHERE order_id = ?",
                order.orderId());
        assertThat(partial.get("status")).isEqualTo("partially_refunded");
        assertThat(partial.get("refunded_krw")).isEqualTo(4_000L);
        assertThat(partial.get("refunded_credit_milli")).isEqualTo(4_000_000L);
        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(6_000_000);

        // 나머지도 콘솔에서 취소하면 남은 크레딧 전부를 회수한다.
        payments.get("pay-6").put("balanceAmount", 0).put("status", "CANCELED");
        String full = statusEvent("evt-" + UUID.randomUUID(), "pay-6", order.orderId(), "CANCELED");
        orders.webhook(full, sign(full));

        assertThat(jdbc.queryForObject("SELECT status FROM credit_orders WHERE order_id = ?", String.class, order.orderId()))
                .isEqualTo("refunded");
        assertThat(credits.credits(user).balanceKrwMilli()).isZero();
        assertThat(cancels).isEmpty();
    }

    @Test
    void disabledPaymentsAreHidden() {
        var orders = orders(false);

        assertThatThrownBy(() -> orders.create(user, "CREDIT_10000"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        String webhook = doneEvent("evt-" + UUID.randomUUID(), "order_x", 10_000);
        assertThatThrownBy(() -> orders.webhook(webhook, sign(webhook)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> orders(true).create(user, "FREE_MONEY"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    private CreditOrderService orders(boolean enabled) {
        return new CreditOrderService(jdbc, manager, credits, gateway, environment, mapper, enabled, SECRET);
    }

    private String doneEvent(String eventId, String orderId, long amount) {
        return """
                {"eventId":"%s","eventType":"PAYMENT_STATUS_CHANGED","data":{"paymentKey":"pay-%s","orderId":"%s","status":"DONE","totalAmount":%d}}"""
                .formatted(eventId, eventId, orderId, amount);
    }

    private String statusEvent(String eventId, String paymentKey, String orderId, String status) {
        return """
                {"eventId":"%s","eventType":"PAYMENT_STATUS_CHANGED","data":{"paymentKey":"%s","orderId":"%s","status":"%s"}}"""
                .formatted(eventId, paymentKey, orderId, status);
    }

    private static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
