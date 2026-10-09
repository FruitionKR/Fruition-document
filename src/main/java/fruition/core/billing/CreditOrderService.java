package fruition.core.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import fruition.core.usage.service.CreditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * PG 결제로 크레딧을 충전·환불한다(#80, ADR-0026). 특정 PG에 묶이지 않는 표준 흐름이다.
 *
 * <ul>
 *   <li>주문: 금액과 지급 크레딧은 서버 상품표({@code app.billing.product.<code>.*})로만 정한다.</li>
 *   <li>승인: 브라우저가 보낸 금액이 주문과 다르면 PG를 부르지 않는다. PG 승인 응답의 주문·금액·상태를 다시 확인한 뒤
 *       주문을 {@code paid}로 바꾸고 같은 트랜잭션에서 크레딧을 지급한다. 조건부 갱신과 원장 키로 한 번만 지급한다.</li>
 *   <li>webhook: HMAC 서명을 검증하고 event_id로 한 번만 처리한다. 승인 응답을 받지 못한 결제를 복구한다.</li>
 *   <li>환불: 주문에서 아직 쓰지 않은 크레딧(가용 잔액 한도)만 환불한다. PG 취소가 성공한 뒤 크레딧을 회수한다.</li>
 * </ul>
 */
@Service
public class CreditOrderService {

    private static final Logger log = LoggerFactory.getLogger(CreditOrderService.class);
    private static final Set<String> PAID = Set.of("paid", "partially_refunded", "refunded");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final CreditService credits;
    private final PaymentGatewayClient gateway;
    private final Environment environment;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String webhookSecret;

    public CreditOrderService(JdbcTemplate jdbc, PlatformTransactionManager manager, CreditService credits,
                              PaymentGatewayClient gateway, Environment environment, ObjectMapper mapper,
                              @Value("${app.billing.payments-enabled:false}") boolean enabled,
                              @Value("${app.billing.pg.webhook-secret:}") String webhookSecret) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.credits = credits;
        this.gateway = gateway;
        this.environment = environment;
        this.mapper = mapper;
        this.enabled = enabled;
        this.webhookSecret = webhookSecret;
    }

    /** 상품표의 금액·크레딧으로 주문을 만든다. 결제창에는 order_id와 amount_krw를 넘긴다. */
    public Order create(String userId, String productCode) {
        requireEnabled();
        String code = productCode == null ? "" : productCode;
        Long amount = code.matches("[A-Z0-9_]{1,40}")
                ? environment.getProperty("app.billing.product." + code + ".amount-krw", Long.class) : null;
        Long credit = amount == null ? null
                : environment.getProperty("app.billing.product." + code + ".credit-krw-milli", Long.class);
        if (amount == null || credit == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "판매하지 않는 상품입니다.");
        }
        String orderId = "order_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO credit_orders (order_id, user_id, product_code, amount_krw, credit_milli) "
                + "VALUES (?, ?, ?, ?, ?)", orderId, userId, code, amount, credit);
        return find(orderId, userId);
    }

    /**
     * PG 승인 API로 결제를 확정하고 크레딧을 지급한다. 이미 지급한 주문은 PG를 다시 부르지 않고 그대로 돌려준다.
     * PG 응답을 받지 못하면 주문은 {@code created}로 남고 webhook이 복구한다.
     */
    public Order confirm(String userId, String orderId, String paymentKey, long amount) {
        requireEnabled();
        Order order = find(orderId, userId);
        if (PAID.contains(order.status())) return order;
        if (amount != order.amountKrw()) {
            log.warn("[결제 금액 불일치] orderId={} requested={} expected={}", orderId, amount, order.amountKrw());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "결제 금액이 주문과 다릅니다.");
        }
        if (paymentKey == null || paymentKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "결제 키가 필요합니다.");
        }
        PaymentGatewayClient.Payment payment;
        try {
            payment = gateway.confirm(paymentKey, orderId, order.amountKrw());
        } catch (PaymentGatewayClient.PaymentRejectedException e) {
            jdbc.update("UPDATE credit_orders SET status = 'failed', failure_code = ? WHERE order_id = ? AND status = 'created'",
                    e.getCode(), orderId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "결제를 승인하지 못했습니다.");
        } catch (RestClientException | IllegalStateException e) {
            log.warn("[결제 승인 결과 미확인] orderId={} error={}", orderId, e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "결제 승인 결과를 확인하지 못했습니다. 잠시 뒤 다시 확인해 주세요.");
        }
        if (!Boolean.TRUE.equals(transaction.execute(tx -> markPaid(order, payment)))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "결제 승인 결과가 주문과 다릅니다. 고객센터로 문의해 주세요.");
        }
        return find(orderId, userId);
    }

    /**
     * PG webhook. 서명이 맞지 않으면 401이다. 같은 event_id는 한 번만 처리하고, 처리 중 오류가 나면 기록도 롤백돼
     * PG 재전송 때 다시 처리한다.
     */
    public void webhook(String body, String signature) {
        requireEnabled();
        if (webhookSecret.isBlank() || signature == null
                || !MessageDigest.isEqual(hmac(body).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "webhook 서명이 올바르지 않습니다.");
        }
        JsonNode event;
        try {
            event = mapper.readTree(body);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "webhook 본문이 JSON이 아닙니다.");
        }
        String hash = HexFormat.of().formatHex(sha256(body));
        String eventId = event.path("eventId").isTextual() ? event.path("eventId").asText() : hash;
        JsonNode data = event.path("data");
        String orderId = data.path("orderId").asText(null);
        transaction.executeWithoutResult(tx -> {
            if (jdbc.update("INSERT INTO payment_events (event_id, body_sha256, event_type, order_id) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT (event_id) DO NOTHING", eventId, hash, event.path("eventType").asText(null), orderId) == 0) {
                return;
            }
            if (orderId == null || !"DONE".equals(data.path("status").asText())) return;
            var orders = jdbc.query("SELECT * FROM credit_orders WHERE order_id = ?", (rs, i) -> order(rs), orderId);
            if (orders.isEmpty()) {
                log.warn("[결제 webhook 주문 없음] eventId={} orderId={}", eventId, orderId);
                return;
            }
            if (!PAID.contains(orders.getFirst().status())) {
                markPaid(orders.getFirst(), PaymentGatewayClient.Payment.from(data));
            }
        });
    }

    /**
     * 주문에서 쓰지 않은 크레딧을 환불한다. 환불 크레딧은 남은 주문 크레딧과 가용 잔액 중 작은 값이고, 금액은 그 비율이다.
     * PG 취소가 성공해야 크레딧을 회수한다.
     */
    public Order refund(String userId, String orderId) {
        requireEnabled();
        transaction.executeWithoutResult(tx -> {
            var orders = jdbc.query("SELECT * FROM credit_orders WHERE order_id = ? AND user_id = ? FOR UPDATE",
                    (rs, i) -> order(rs), orderId, userId);
            if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다.");
            Order order = orders.getFirst();
            if (!Set.of("paid", "partially_refunded").contains(order.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "환불할 수 있는 주문이 아닙니다.");
            }
            // ponytail: PG 취소 동안 주문·계정 행을 잠가 그사이 크레딧이 쓰이지 않게 한다. 이 사용자의 AI 예약은 그동안 기다린다.
            // 환불이 잦아지면 환불 대기 상태로 먼저 회수하고 PG 결과로 확정·복원하는 두 단계로 나눈다.
            long remaining = order.creditKrwMilli() - order.refundedCreditKrwMilli();
            long refundCredit = Math.min(remaining, credits.lockAvailable(userId));
            long refundKrw = refundCredit == remaining ? order.amountKrw() - order.refundedKrw()
                    : refundCredit * order.amountKrw() / order.creditKrwMilli();
            if (refundCredit <= 0 || refundKrw <= 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "환불할 수 있는 미사용 크레딧이 없습니다.");
            }
            String key = "refund:" + orderId + ":" + order.refundedCreditKrwMilli();
            try {
                gateway.cancel(order.paymentKey(), refundKrw, "크레딧 미사용분 환불", key);
            } catch (PaymentGatewayClient.PaymentRejectedException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "결제사가 환불을 거절했습니다.");
            } catch (RestClientException | IllegalStateException e) {
                log.warn("[결제 취소 결과 미확인] orderId={} error={}", orderId, e.toString());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "환불 결과를 확인하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
            }
            credits.post(userId, "refund", -refundCredit, key, "결제 환불 " + orderId);
            jdbc.update("UPDATE credit_orders SET refunded_krw = refunded_krw + ?, refunded_credit_milli = refunded_credit_milli + ?, "
                    + "status = CASE WHEN refunded_credit_milli + ? = credit_milli THEN 'refunded' ELSE 'partially_refunded' END, "
                    + "refunded_at = now() WHERE order_id = ?", refundKrw, refundCredit, refundCredit, orderId);
        });
        return find(orderId, userId);
    }

    /** PG 결과를 주문과 대조해 지급한다. 조건부 갱신이라 동시에 들어온 승인·webhook 중 하나만 지급한다. */
    private boolean markPaid(Order order, PaymentGatewayClient.Payment payment) {
        if (!"DONE".equals(payment.status()) || !order.orderId().equals(payment.orderId())
                || payment.totalAmount() != order.amountKrw()) {
            log.error("[결제 승인 불일치] orderId={} status={} pgOrderId={} amount={} expected={}", order.orderId(),
                    payment.status(), payment.orderId(), payment.totalAmount(), order.amountKrw());
            return false;
        }
        if (jdbc.update("UPDATE credit_orders SET status = 'paid', pg_payment_key = ?, paid_at = now(), failure_code = NULL "
                + "WHERE order_id = ? AND status IN ('created', 'failed')", payment.paymentKey(), order.orderId()) == 1) {
            credits.post(order.userId(), "purchase", order.creditKrwMilli(), "purchase:" + order.orderId(), order.productCode());
        }
        return true;
    }

    private Order find(String orderId, String userId) {
        List<Order> orders = jdbc.query("SELECT * FROM credit_orders WHERE order_id = ? AND user_id = ?",
                (rs, i) -> order(rs), orderId, userId);
        if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다.");
        return orders.getFirst();
    }

    private static Order order(ResultSet rs) throws SQLException {
        return new Order(rs.getString("order_id"), rs.getString("user_id"), rs.getString("product_code"),
                rs.getLong("amount_krw"), rs.getLong("credit_milli"), rs.getString("status"),
                rs.getString("pg_payment_key"), rs.getLong("refunded_krw"), rs.getLong("refunded_credit_milli"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toInstant());
    }

    private void requireEnabled() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "결제 기능을 사용할 수 없습니다.");
    }

    private String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("webhook 서명을 계산할 수 없습니다.", e);
        }
    }

    private static byte[] sha256(String body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 금액은 원, 크레딧은 milli-KRW다. 결제 키는 응답에 넣지 않는다. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Order(String orderId, @com.fasterxml.jackson.annotation.JsonIgnore String userId, String productCode,
                        long amountKrw, long creditKrwMilli, String status,
                        @com.fasterxml.jackson.annotation.JsonIgnore String paymentKey, long refundedKrw,
                        long refundedCreditKrwMilli, Instant createdAt, Instant paidAt) {
    }
}
