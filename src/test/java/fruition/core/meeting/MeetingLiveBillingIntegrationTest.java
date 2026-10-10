package fruition.core.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.usage.service.CreditService;
import fruition.core.usage.service.UsageChargeService;
import com.sun.net.httpserver.HttpServer;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** enforce에서 실시간 전사가 구간마다 정산·재예약하고, 잔액이 모자라면 이유를 알리고 연결을 닫는지 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.speech.live-endpoint=ws://localhost:${local.server.port}/internal/test-ai/live",
        "app.billing.enforce=true",
        "app.billing.live-renew-seconds=1"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MeetingLiveIntegrationTest.FakeAiConfig.class})
class MeetingLiveBillingIntegrationTest {

    private static final long ESTIMATE = 1_000_000;  // app.billing.estimate.meeting_live 기본값
    /** AI 원장 호출 조회 대신 빈 목록을 준다. 청구 행은 테스트가 직접 넣는다. */
    private static final HttpServer CALLS = calls();

    @DynamicPropertySource
    static void callsEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.usage-charge.calls-endpoint",
                () -> "http://127.0.0.1:" + CALLS.getAddress().getPort() + "/internal/model-usage/calls");
    }

    private static HttpServer calls() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = "{\"calls\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired CreditService credits;
    @Autowired UsageChargeService usageCharges;
    @LocalServerPort int port;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String userId = "user_" + suffix;
    private final String workspaceId = "ws_" + suffix;

    @Test
    void liveTranscriptionIsChargedPerIntervalAndClosedWhenCreditRunsOut() throws Exception {
        redisTemplate.opsForValue().set("authz:role:" + workspaceId + ":" + userId, "OWNER");
        MeetingLiveIntegrationTest.FakeAi.mode = "normal";
        credits.post(userId, "grant", ESTIMATE + 500_000, "grant:" + UUID.randomUUID(), "테스트");
        String meetingId = createMeeting();

        Browser browser = connect(meetingId);
        assertThat(browser.next().path("type").asText()).isEqualTo("ready");
        String runId = jdbc.queryForObject("SELECT id FROM ai_task_runs WHERE user_id = ? AND kind = 'meeting_live'",
                String.class, userId);

        // 첫 구간 사용량이 들어오면 다음 구간 정산이 차감하고, 남은 잔액이 다음 구간 상한보다 작아 연결을 닫는다.
        jdbc.update("INSERT INTO usage_charges (call_id, user_id, run_id, call_status, status, charge_krw_milli, "
                + "cost_usd_micro, started_at) VALUES (?, ?, ?, 'succeeded', 'charged', 600000, 0, now())",
                "c-" + UUID.randomUUID(), userId, runId);

        JsonNode error = browser.untilType("error");
        assertThat(error.path("code").asText()).isEqualTo("insufficient_credit");
        assertThat(error.path("message").asText()).contains("크레딧");
        assertThat(browser.closed.get(10, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());

        // 연결이 끝나면 종료 정산이 사용량을 차감하고 남은 예약을 푼다. 앱의 수집 worker가 먼저 꺼냈을 수 있어 예약 상태로 기다린다.
        for (int i = 0; i < 50 && credits.credits(userId).reservedKrwMilli() > 0; i++) {
            jdbc.update("UPDATE usage_collect_queue SET available_at = now() - interval '1 day' WHERE run_id = ?", runId);
            usageCharges.collectPending();
            Thread.sleep(100);
        }
        assertThat(credits.credits(userId).reservedKrwMilli()).isZero();
        assertThat(credits.credits(userId).balanceKrwMilli()).isEqualTo(ESTIMATE + 500_000 - 600_000);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task_runs WHERE id = ?", String.class, runId))
                .isEqualTo("completed");
    }

    private String createMeeting() throws Exception {
        String body = mockMvc.perform(post(base()).header("Authorization", bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"display_name\":\"출시 회의\",\"source\":\"live\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("meeting_id").asText();
    }

    private Browser connect(String meetingId) throws Exception {
        String ticket = objectMapper.readTree(mockMvc.perform(post(base() + "/" + meetingId + "/live-tickets")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("ticket").asText();
        Browser browser = new Browser();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://localhost:3000");
        URI uri = URI.create("ws://localhost:" + port + "/api/meetings/" + meetingId + "/live?ticket=" + ticket);
        new StandardWebSocketClient().execute(browser, headers, uri).get(10, TimeUnit.SECONDS);
        return browser;
    }

    private String base() {
        return "/api/workspaces/" + workspaceId + "/meetings";
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com");
    }

    private final class Browser extends AbstractWebSocketHandler {
        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            messages.add(objectMapper.readTree(message.getPayload()));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }

        JsonNode next() throws InterruptedException {
            JsonNode message = messages.poll(10, TimeUnit.SECONDS);
            assertThat(message).as("서버 메시지").isNotNull();
            return message;
        }

        JsonNode untilType(String type) throws InterruptedException {
            while (true) {
                JsonNode message = next();
                if (type.equals(message.path("type").asText())) {
                    return message;
                }
            }
        }
    }
}
