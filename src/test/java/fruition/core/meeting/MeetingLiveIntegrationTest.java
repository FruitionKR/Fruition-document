package fruition.core.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 브라우저 ↔ document ↔ 가짜 ai-svc 실시간 전사 중계와 저장을 실제 WebSocket으로 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.speech.live-endpoint=ws://localhost:${local.server.port}/internal/test-ai/live")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MeetingLiveIntegrationTest.FakeAiConfig.class})
class MeetingLiveIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @LocalServerPort int port;

    private String userId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_" + suffix;
        workspaceId = "ws_" + suffix;
        redisTemplate.opsForValue().set("authz:role:" + workspaceId + ":" + userId, "OWNER");
        FakeAi.mode = "normal";
    }

    @Test
    void outOfOrderCompletionAndReconnect_keepSpeechOrder() throws Exception {
        String meetingId = createMeeting();
        FakeAi.mode = "reverse";
        Browser first = connect(meetingId);
        assertThat(first.next().path("stream_order").asInt()).isEqualTo(1);
        first.audio();
        first.command("commit");
        first.audio();
        first.command("commit");
        first.command("finish");
        List<JsonNode> events = first.untilClosed();
        assertThat(events).extracting(e -> e.path("type").asText())
                .containsSubsequence("committed", "committed", "completed", "completed", "finished");
        assertThat(first.closeStatus()).isEqualTo(CloseStatus.NORMAL);

        FakeAi.mode = "normal";
        Browser second = connect(meetingId);
        assertThat(second.next().path("stream_order").asInt()).isEqualTo(2);
        second.audio();
        second.command("commit");
        second.untilType("completed");
        second.session.close();  // finish 없이 끊긴다

        JsonNode meeting = awaitLiveReleased(meetingId);
        assertThat(meeting.path("segments")).extracting(s -> s.path("id").asText() + "@" + s.path("position").asInt())
                .containsExactly("s1_item_1@1", "s1_item_2@2", "s2_item_1@3");
        assertThat(meeting.path("segments").get(0).path("text").asText()).isEqualTo("item_1 전사");
        assertThat(meeting.path("streams")).extracting(s -> s.path("end_reason").asText())
                .containsExactly("finished", "interrupted");
        assertThat(meeting.path("transcript_complete").asBoolean()).isFalse();
    }

    @Test
    void aiHandshakeRejection_closesBrowserAndReleasesLock() throws Exception {
        String meetingId = createMeeting();
        FakeAi.mode = "reject";
        Browser browser = connect(meetingId);

        assertThat(browser.untilType("error").path("code").asText()).isEqualTo("transcription_failed");
        browser.untilClosed();
        assertThat(browser.closeStatus().getCode()).isEqualTo(CloseStatus.SERVER_ERROR.getCode());
        assertThat(awaitLiveReleased(meetingId).path("streams").get(0).path("end_reason").asText()).isEqualTo("failed");
    }

    @Test
    void conflictingCompletion_isRejectedWithoutOverwriting() throws Exception {
        String meetingId = createMeeting();
        FakeAi.mode = "conflict";
        Browser browser = connect(meetingId);
        browser.next();
        browser.audio();
        browser.command("commit");
        JsonNode error = browser.untilType("error");

        assertThat(error.path("code").asText()).isEqualTo("segment_conflict");
        browser.untilClosed();
        assertThat(browser.closeStatus().getCode()).isEqualTo(CloseStatus.SERVER_ERROR.getCode());
        JsonNode meeting = awaitLiveReleased(meetingId);
        assertThat(meeting.path("segments").get(0).path("text").asText()).isEqualTo("item_1 전사");
        assertThat(meeting.path("streams").get(0).path("end_reason").asText()).isEqualTo("failed");
    }

    @Test
    void finishedCountMismatch_isNotTreatedAsNormalEnd() throws Exception {
        String meetingId = createMeeting();
        FakeAi.mode = "wrong-count";
        Browser browser = connect(meetingId);
        browser.next();
        browser.audio();
        browser.command("commit");
        browser.untilType("completed");
        browser.command("finish");

        assertThat(browser.untilType("error").path("code").asText()).isEqualTo("transcript_incomplete");
        assertThat(awaitLiveReleased(meetingId).path("streams").get(0).path("end_reason").asText()).isEqualTo("failed");
    }

    @Test
    void secondConnectionAndReusedTicketAndForeignOrigin_areRejected() throws Exception {
        String meetingId = createMeeting();
        String ticket = issueTicket(meetingId);
        Browser first = connect(meetingId, ticket, "http://localhost:3000");
        first.next();

        mockMvc.perform(post(base() + "/" + meetingId + "/live-tickets").header("Authorization", bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_LIVE_IN_USE"));
        // 같은 ticket은 한 번만 쓴다.
        assertThatThrownBy(() -> connect(meetingId, ticket, "http://localhost:3000"))
                .isInstanceOf(ExecutionException.class).rootCause().hasMessageContaining("[401]");
        // 허용되지 않은 사이트는 유효한 ticket이 있어도 거절한다.
        String valid = issueTicketAfterRelease(meetingId, first);
        assertThatThrownBy(() -> connect(meetingId, valid, "https://evil.example"))
                .isInstanceOf(ExecutionException.class).rootCause().hasMessageContaining("[403]");
    }

    private String issueTicketAfterRelease(String meetingId, Browser connected) throws Exception {
        connected.session.close();
        awaitLiveReleased(meetingId);
        return issueTicket(meetingId);
    }

    @Test
    void otherUserCannotSeeMeetingAndDocumentMustBeOwned() throws Exception {
        String meetingId = createMeeting();
        String other = "user_" + UUID.randomUUID().toString().substring(0, 8);
        redisTemplate.opsForValue().set("authz:role:" + workspaceId + ":" + other, "OWNER");

        mockMvc.perform(get(base() + "/" + meetingId)
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(other, other + "@example.com")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MEETING_NOT_FOUND"));
        mockMvc.perform(post(base()).header("Authorization", bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"live\",\"document_id\":\"doc_missing\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createIsIdempotent() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = createMeeting(key);
        assertThat(createMeeting(key)).isEqualTo(first);
    }

    // ---------- helpers

    private String createMeeting() throws Exception {
        return createMeeting(UUID.randomUUID().toString());
    }

    private String createMeeting(String key) throws Exception {
        String body = mockMvc.perform(post(base()).header("Authorization", bearer())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"display_name\":\"출시 회의\",\"source\":\"live\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("open"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("meeting_id").asText();
    }

    private String issueTicket(String meetingId) throws Exception {
        String body = mockMvc.perform(post(base() + "/" + meetingId + "/live-tickets").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("ticket").asText();
    }

    private Browser connect(String meetingId) throws Exception {
        return connect(meetingId, issueTicket(meetingId), "http://localhost:3000");
    }

    private Browser connect(String meetingId, String ticket, String origin) throws Exception {
        Browser browser = new Browser();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        URI uri = URI.create("ws://localhost:" + port + "/api/meetings/" + meetingId + "/live?ticket=" + ticket);
        browser.session = new StandardWebSocketClient().execute(browser, headers, uri).get(10, TimeUnit.SECONDS);
        return browser;
    }

    /** 연결 종료 처리(잠금 해제·종료 사유 기록)가 끝날 때까지 조회한다. */
    private JsonNode awaitLiveReleased(String meetingId) throws Exception {
        for (int i = 0; i < 50; i++) {
            JsonNode meeting = objectMapper.readTree(mockMvc.perform(get(base() + "/" + meetingId)
                            .header("Authorization", bearer()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            if (!meeting.path("live_connected").asBoolean()) {
                return meeting;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("실시간 연결 잠금이 풀리지 않았습니다.");
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
        WebSocketSession session;

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

        List<JsonNode> untilClosed() throws Exception {
            closeStatus();
            List<JsonNode> all = new ArrayList<>();
            messages.drainTo(all);
            return all;
        }

        CloseStatus closeStatus() throws Exception {
            return closed.get(10, TimeUnit.SECONDS);
        }

        /** ai-svc 최대 frame(1초, 48,000 bytes). Tomcat 기본 버퍼 8 KiB를 넘는지 함께 확인한다. */
        void audio() throws Exception {
            session.sendMessage(new BinaryMessage(ByteBuffer.wrap(new byte[48_000])));
        }

        void command(String type) throws Exception {
            session.sendMessage(new TextMessage("{\"type\":\"" + type + "\"}"));
        }
    }

    /** ai-svc 실시간 전사 계약을 흉내 낸다. mode로 완료 순서·중복·충돌·개수 불일치를 만든다. */
    static final class FakeAi extends AbstractWebSocketHandler {
        static volatile String mode = "normal";

        /** 연결마다 ai-svc 구간 번호가 1부터 다시 시작한다. */
        private static final class State {
            int commits;
            final List<String> held = new ArrayList<>();
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            assertThat(session.getHandshakeHeaders().getFirst("X-Internal-Token")).isEqualTo("test-internal-callback");
            session.getAttributes().put("state", new State());
            session.setBinaryMessageSizeLimit(64 * 1024);  // 실제 ai-svc도 1초 frame(48,000 bytes)을 받는다
            send(session, "{\"type\":\"ready\",\"sample_rate\":24000}");
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            State state = (State) session.getAttributes().get("state");
            List<String> held = state.held;
            String type = new ObjectMapper().readTree(message.getPayload()).path("type").asText();
            if ("finish".equals(type)) {
                int count = "wrong-count".equals(mode) ? state.commits + 1 : state.commits;
                send(session, "{\"type\":\"finished\",\"segment_count\":" + count + "}");
                return;
            }
            int commits = ++state.commits;
            String item = "item_" + commits;
            String previous = commits == 1 ? "null" : "\"item_" + (commits - 1) + "\"";
            send(session, "{\"type\":\"committed\",\"segment_id\":\"" + item + "\",\"previous_segment_id\":" + previous + "}");
            send(session, "{\"type\":\"delta\",\"segment_id\":\"" + item + "\",\"text\":\"item\"}");
            switch (mode) {
                case "reverse" -> {
                    held.add(item);
                    if (held.size() == 2) {
                        completed(session, held.get(1), held.get(1) + " 전사");
                        completed(session, held.get(0), held.get(0) + " 전사");
                        completed(session, held.get(0), held.get(0) + " 전사");  // 중복 도착은 무시된다
                    }
                }
                case "conflict" -> {
                    completed(session, item, item + " 전사");
                    completed(session, item, "다른 문장");
                }
                default -> completed(session, item, item + " 전사");
            }
        }

        private void completed(WebSocketSession session, String item, String text) throws Exception {
            send(session, "{\"type\":\"completed\",\"segment_id\":\"" + item + "\",\"text\":\"" + text + "\"}");
        }

        private static void send(WebSocketSession session, String json) throws Exception {
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeAiConfig implements WebSocketConfigurer {
        @Override
        public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(new FakeAi(), "/internal/test-ai/live")
                    .addInterceptors(new org.springframework.web.socket.server.HandshakeInterceptor() {
                        @Override
                        public boolean beforeHandshake(org.springframework.http.server.ServerHttpRequest request,
                                                       org.springframework.http.server.ServerHttpResponse response,
                                                       org.springframework.web.socket.WebSocketHandler handler,
                                                       java.util.Map<String, Object> attributes) {
                            if ("reject".equals(FakeAi.mode)) {  // ai-svc가 토큰·권한으로 handshake를 거절하는 경우
                                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                                return false;
                            }
                            return true;
                        }

                        @Override
                        public void afterHandshake(org.springframework.http.server.ServerHttpRequest request,
                                                   org.springframework.http.server.ServerHttpResponse response,
                                                   org.springframework.web.socket.WebSocketHandler handler,
                                                   Exception exception) {}
                    });
        }
    }
}
