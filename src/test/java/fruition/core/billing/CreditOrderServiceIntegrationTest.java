package fruition.core.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final String user = "user-" + UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/payments", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String response;
            if (exchange.getRequestURI().getPath().endsWith("/confirm")) {
                confirms.add(body + " " + auth);
                JsonNode request = mapper.readTree(body);
                long amount = pgAmountOverride.get() > 0 ? pgAmountOverride.get() : request.path("amount").asLong();
                response = mapper.createObjectNode().put("paymentKey", request.path("paymentKey").asText())
                        .put("orderId", request.path("orderId").asText()).put("status", "DONE")
                        .put("totalAmount", amount).toString();
            } else {
                cancels.add(exchange.getRequestURI().getPath() + " " + body + " key="
                        + exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                response = "{\"status\":\"PARTIAL_CANCELED\"}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/payments";
        gateway = new PaymentGatewayClient(new PipelineClientFactory("internal-test"), base + "/confirm",
                base + "/{paymentKey}/cancel", "test_sk");
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
