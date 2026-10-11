package fruition.core.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fruition.TestcontainersConfiguration;
import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.repository.ChatSessionRepository;
import fruition.core.chat.service.ChatSessionService;
import fruition.core.purge.DataPurgeService;
import fruition.shared.security.JwtTokenProvider;
import fruition.shared.util.StorageProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 답변 음성 듣기(#113): 가짜 ai-svc 스트림, 실제 MinIO·PostgreSQL로 중계 순서·즉시 전달·끊김 전파·저장·재생·삭제를 확인한다.
 * 실제 HTTP 서버에 붙어 스트림을 줄 단위로 읽는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.speech.synthesis-read-timeout-seconds=1")
@Import(TestcontainersConfiguration.class)
class SpeechSynthesisIntegrationTest {

    interface Script {
        void run(OutputStream out) throws Exception;
    }

    static final byte[] PCM_A = {1, 2, 3, 4};
    static final byte[] PCM_B = {5, 6, 7, 8};

    static final HttpServer FAKE_AI;
    static volatile Script script;
    static final AtomicInteger aiCalls = new AtomicInteger();
    static final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
    static final AtomicReference<String> lastRequestId = new AtomicReference<>();
    static final AtomicReference<String> lastToken = new AtomicReference<>();
    static volatile int aiStatus = 200;
    static final ObjectMapper JSON = new ObjectMapper();

    static {
        try {
            FAKE_AI = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        FAKE_AI.createContext("/speech/synthesis", SpeechSynthesisIntegrationTest::handle);
        // 기본 실행기는 스레드 하나라 느린 응답(멈춤·시간 초과 시나리오)이 다음 테스트의 연결을 막는다.
        FAKE_AI.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        FAKE_AI.start();
    }

    static void handle(HttpExchange exchange) throws IOException {
        aiCalls.incrementAndGet();
        lastRequest.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
        lastRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
        lastToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        if (aiStatus != 200) {
            byte[] body = "{\"detail\":\"private-provider-detail\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(aiStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
        exchange.sendResponseHeaders(200, 0);  // chunked
        try (OutputStream out = exchange.getResponseBody()) {
            script.run(out);
        } catch (Exception ignored) {
            // 중계가 연결을 닫으면 쓰기가 실패한다.
        }
    }

    static void line(OutputStream out, String json) throws IOException {
        out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    static String audio(byte[] pcm) {
        return "{\"type\":\"audio\",\"delta\":\"" + Base64.getEncoder().encodeToString(pcm) + "\"}";
    }

    @DynamicPropertySource
    static void aiEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.speech.synthesis-endpoint",
                () -> "http://127.0.0.1:" + FAKE_AI.getAddress().getPort() + "/speech/synthesis");
    }

    @AfterAll
    static void stop() {
        FAKE_AI.stop(0);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired ChatSessionRepository sessions;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatSessionService sessionService;
    @Autowired SpeechAudioStore store;
    @Autowired DataPurgeService purge;
    @Autowired MinioClient minio;
    @Autowired StorageProperties storage;
    @Autowired javax.sql.DataSource dataSource;

    final HttpClient http = HttpClient.newHttpClient();
    String userId;
    String workspaceId;
    String sessionId;
    String messageId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_" + suffix;
        workspaceId = "ws_" + suffix;
        redisTemplate.opsForValue().set("authz:role:" + workspaceId + ":" + userId, "MEMBER");
        sessionId = "session_" + suffix;
        sessions.save(new ChatSession(sessionId, workspaceId, userId, "제목"));
        messageId = message("assistant", "completed", "답변입니다[1]. **중요**합니다.");
        aiCalls.set(0);
        aiStatus = 200;
        lastRequest.set(null);
        script = out -> {
            line(out, audio(PCM_A));
            line(out, audio(PCM_B));
            line(out, "{\"type\":\"done\"}");
        };
    }

    String message(String role, String status, String content) {
        String id = "msg_" + UUID.randomUUID().toString().replace("-", "");
        messages.save(new ChatMessage(id, sessions.findById(sessionId).orElseThrow(), "pair_" + id, role, content, status,
                Instant.now(), null));
        return id;
    }

    HttpResponse<InputStream> post(String user, String message) throws Exception {
        return postRaw(user, "{\"message_id\":\"" + message + "\"}");
    }

    HttpResponse<InputStream> postRaw(String user, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/workspaces/" + workspaceId
                        + "/speech/synthesis"))
                .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(user, user + "@example.com"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
    }

    static List<String> readAll(HttpResponse<InputStream> response) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = reader.readLine()) != null) lines.add(l);
        }
        return lines;
    }

    static byte[] pcmOf(List<String> lines) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        for (String l : lines) {
            JsonNode event = JSON.readTree(l);
            if ("audio".equals(event.path("type").asText())) out.writeBytes(Base64.getDecoder().decode(event.path("delta").asText()));
        }
        return out.toByteArray();
    }

    static java.nio.file.Path pcmFile(int... bytes) throws IOException {
        byte[] data = new byte[bytes.length];
        for (int i = 0; i < data.length; i++) data[i] = (byte) bytes[i];
        java.nio.file.Path file = java.nio.file.Files.createTempFile("speech-test-", ".pcm");
        java.nio.file.Files.write(file, data);
        return file;
    }

    static void eventually(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) Thread.sleep(50);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    String runStatus() {
        return jdbc.queryForObject("SELECT status FROM ai_task_runs WHERE user_id = ? AND kind = 'speech_synthesis'",
                String.class, userId);
    }

    boolean objectExists(String key) {
        try {
            minio.statObject(StatObjectArgs.builder().bucket(storage.getBucket()).object(key).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    String keyOf(String message) {
        return "assets/" + workspaceId + "/speech/" + message + ".wav";
    }

    int speechRows(String message) {
        return jdbc.queryForObject("SELECT count(*) FROM chat_message_speech WHERE message_id = ?", Integer.class, message);
    }

    @Test
    void relaysEventsInOrder_andStoresCompletedSynthesis() throws Exception {
        var response = post(userId, messageId);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/x-ndjson");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        List<String> lines = readAll(response);
        assertThat(lines).containsExactly(audio(PCM_A), audio(PCM_B), "{\"type\":\"done\"}");

        // ai에는 정리된 텍스트, 사용자·워크스페이스, 사용량 귀속 헤더가 간다.
        JsonNode request = lastRequest.get();
        assertThat(request.path("answer").asText()).isEqualTo("답변입니다. 중요합니다.");
        assertThat(request.path("action").asText()).isEqualTo("chat_answer");
        assertThat(request.path("workspace_id").asText()).isEqualTo(workspaceId);
        assertThat(request.path("user_id").asText()).isEqualTo(userId);
        assertThat(lastRequestId.get()).startsWith("speech_synthesis:");
        assertThat(lastToken.get()).isEqualTo("test-internal-callback");

        // done을 받은 시점에 WAV가 저장돼 있다.
        assertThat(speechRows(messageId)).isEqualTo(1);
        byte[] wav = minio.getObject(GetObjectArgs.builder().bucket(storage.getBucket()).object(keyOf(messageId)).build())
                .readAllBytes();
        assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(java.util.Arrays.copyOfRange(wav, 44, wav.length)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        eventually(() -> "completed".equals(runStatus()));
    }

    @Test
    void forwardsEachLineAsItArrives() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        script = out -> {
            line(out, audio(PCM_A));
            release.await(10, TimeUnit.SECONDS);
            line(out, "{\"type\":\"done\"}");
        };
        var response = post(userId, messageId);
        var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));

        // ai가 아직 끝나지 않았는데도 첫 오디오 줄이 도착한다.
        assertThat(reader.readLine()).isEqualTo(audio(PCM_A));
        release.countDown();
        assertThat(reader.readLine()).isEqualTo("{\"type\":\"done\"}");
        assertThat(reader.readLine()).isNull();
    }

    @Test
    void streamingDoesNotHoldDbConnection() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        script = out -> {
            line(out, audio(PCM_A));
            release.await(10, TimeUnit.SECONDS);
            line(out, "{\"type\":\"done\"}");
        };
        var pool = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getHikariPoolMXBean();
        var response = post(userId, messageId);
        var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        assertThat(reader.readLine()).isEqualTo(audio(PCM_A));

        // 스트림이 진행 중인 동안 요청이 쥔 연결은 없다(주기 작업이 잠깐 쥐는 연결은 기다려 넘긴다).
        eventually(() -> pool.getActiveConnections() == 0);
        release.countDown();
        assertThat(reader.readLine()).isEqualTo("{\"type\":\"done\"}");
    }

    @Test
    void replaysStoredSynthesisWithoutCallingAiOrBilling() throws Exception {
        readAll(post(userId, messageId));
        assertThat(aiCalls.get()).isEqualTo(1);
        int runs = jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE user_id = ?", Integer.class, userId);

        List<String> replay = readAll(post(userId, messageId));

        assertThat(replay.getLast()).isEqualTo("{\"type\":\"done\"}");
        assertThat(replay.stream().filter(l -> l.contains("\"audio\"")).count()).isEqualTo(1);  // 작아서 한 청크
        assertThat(pcmOf(replay)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(aiCalls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(runs);
    }

    @Test
    void clientDisconnect_closesUpstream_andIsNotStored() throws Exception {
        CountDownLatch upstreamClosed = new CountDownLatch(1);
        script = out -> {
            line(out, audio(PCM_A));
            try {
                for (int i = 0; i < 200; i++) {
                    Thread.sleep(50);
                    line(out, audio(PCM_B));
                }
            } catch (IOException closed) {
                upstreamClosed.countDown();  // document가 ai 연결을 닫았다.
            }
        };
        var response = post(userId, messageId);
        var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        assertThat(reader.readLine()).isEqualTo(audio(PCM_A));
        reader.close();  // 사용자가 멈춘다

        assertThat(upstreamClosed.await(10, TimeUnit.SECONDS)).isTrue();
        eventually(() -> "failed".equals(runStatus()));
        // 끊긴 합성도 사용량 수집이 걸린다(ai가 cancelled로 마감한 호출을 과금).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_collect_queue WHERE run_id LIKE 'speech_synthesis:%' "
                + "AND run_id IN (SELECT id FROM ai_task_runs WHERE user_id = ?)", Integer.class, userId)).isPositive();
        assertThat(speechRows(messageId)).isZero();
        assertThat(objectExists(keyOf(messageId))).isFalse();
    }

    @Test
    void errorEvent_isRelayedAndNotStored() throws Exception {
        script = out -> {
            line(out, audio(PCM_A));
            line(out, "{\"type\":\"error\",\"code\":\"provider_rate_limited\"}");
        };
        List<String> lines = readAll(post(userId, messageId));

        assertThat(lines).containsExactly(audio(PCM_A), "{\"type\":\"error\",\"code\":\"provider_rate_limited\"}");
        eventually(() -> "failed".equals(runStatus()));
        assertThat(speechRows(messageId)).isZero();
    }

    @Test
    void upstreamEndingWithoutTerminalEvent_becomesProviderUnavailable() throws Exception {
        script = out -> line(out, audio(PCM_A));
        List<String> lines = readAll(post(userId, messageId));

        assertThat(lines).containsExactly(audio(PCM_A), "{\"type\":\"error\",\"code\":\"provider_unavailable\"}");
        assertThat(speechRows(messageId)).isZero();
    }

    @Test
    void stalledUpstream_becomesDeadlineExceeded() throws Exception {
        script = out -> {
            line(out, audio(PCM_A));
            Thread.sleep(3000);  // 청크 사이 제한(1초)을 넘긴다
        };
        List<String> lines = readAll(post(userId, messageId));

        assertThat(lines).containsExactly(audio(PCM_A), "{\"type\":\"error\",\"code\":\"deadline_exceeded\"}");
        assertThat(speechRows(messageId)).isZero();
    }

    @Test
    void statusErrorsComeBeforeTheStream() throws Exception {
        aiStatus = 400;
        var tooLong = post(userId, messageId);
        assertThat(tooLong.statusCode()).isEqualTo(400);
        aiStatus = 500;
        var failed = post(userId, messageId);
        assertThat(failed.statusCode()).isEqualTo(503);
        assertThat(new String(failed.body().readAllBytes(), StandardCharsets.UTF_8)).doesNotContain("private-provider-detail");
        // 실패한 호출도 실행은 닫는다.
        eventually(() -> jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE user_id = ? AND status = 'failed'",
                Integer.class, userId) == 2);
    }

    @Test
    void visibilityAndStateAreCheckedBeforeCallingAi() throws Exception {
        String other = "user_" + UUID.randomUUID().toString().substring(0, 8);
        redisTemplate.opsForValue().set("authz:role:" + workspaceId + ":" + other, "MEMBER");
        String outsider = "user_" + UUID.randomUUID().toString().substring(0, 8);
        redisTemplate.opsForValue().set("authz:role:" + workspaceId + ":" + outsider, "NONE");

        assertThat(post(other, messageId).statusCode()).isEqualTo(404);       // 같은 워크스페이스의 다른 사용자 세션
        assertThat(post(outsider, messageId).statusCode()).isEqualTo(404);    // 멤버 아님
        assertThat(post(userId, "msg_missing").statusCode()).isEqualTo(404);
        assertThat(post(userId, message("user", "completed", "질문")).statusCode()).isEqualTo(404);
        assertThat(post(userId, message("assistant", "pending", "")).statusCode()).isEqualTo(409);
        assertThat(post(userId, message("assistant", "failed", "")).statusCode()).isEqualTo(409);
        assertThat(post(userId, message("assistant", "completed", "```sql\nselect 1;\n```")).statusCode()).isEqualTo(422);
        assertThat(postRaw(userId, "{}").statusCode()).isEqualTo(400);
        assertThat(aiCalls.get()).isZero();
    }

    @Test
    void sessionDelete_removesStoredAudio() throws Exception {
        readAll(post(userId, messageId));
        assertThat(objectExists(keyOf(messageId))).isTrue();

        sessionService.delete(workspaceId, userId, sessionId);

        assertThat(objectExists(keyOf(messageId))).isFalse();
        assertThat(speechRows(messageId)).isZero();
    }

    @Test
    void userPurge_removesStoredAudio() throws Exception {
        readAll(post(userId, messageId));

        purge.purgeUser(userId);

        assertThat(objectExists(keyOf(messageId))).isFalse();
        assertThat(speechRows(messageId)).isZero();
    }

    @Test
    void workspacePurge_removesStoredAudio() throws Exception {
        readAll(post(userId, messageId));

        purge.purgeWorkspace(workspaceId);

        assertThat(objectExists(keyOf(messageId))).isFalse();
        assertThat(speechRows(messageId)).isZero();
    }

    @Test
    void cleanup_removesOnlyAudioOlderThan30Days() throws Exception {
        String fresh = message("assistant", "completed", "새 답변");
        store.save(workspaceId, userId, messageId, pcmFile(1, 2));
        store.save(workspaceId, userId, fresh, pcmFile(3, 4));
        jdbc.update("UPDATE chat_message_speech SET created_at = now() - interval '31 days' WHERE message_id = ?", messageId);

        store.cleanupExpired();

        assertThat(speechRows(messageId)).isZero();
        assertThat(objectExists(keyOf(messageId))).isFalse();
        assertThat(speechRows(fresh)).isEqualTo(1);
        assertThat(objectExists(keyOf(fresh))).isTrue();
    }

    @Test
    void cleanup_continuesPastAFailingItem() throws Exception {
        String second = message("assistant", "completed", "두 번째");
        store.save(workspaceId, userId, messageId, pcmFile(1, 2));
        store.save(workspaceId, userId, second, pcmFile(3, 4));
        jdbc.update("UPDATE chat_message_speech SET created_at = now() - interval '31 days' WHERE message_id IN (?, ?)",
                messageId, second);
        // 한 행의 객체 키를 지울 수 없는 값으로 바꿔 삭제를 실패시킨다.
        String broken = messageId.compareTo(second) < 0 ? messageId : second;
        String healthy = broken.equals(messageId) ? second : messageId;
        jdbc.update("UPDATE chat_message_speech SET object_key = '' WHERE message_id = ?", broken);

        store.cleanupExpired();

        assertThat(speechRows(broken)).isEqualTo(1);  // 실패한 행은 다음 실행이 다시 시도한다
        assertThat(speechRows(healthy)).isZero();
        assertThat(objectExists(keyOf(healthy))).isFalse();
    }

    @Test
    void ignoresUnknownEventTypes() throws Exception {
        script = out -> {
            line(out, "{\"type\":\"future_event\",\"x\":1}");
            line(out, audio(PCM_A));
            line(out, "{\"type\":\"done\"}");
        };
        List<String> lines = readAll(post(userId, messageId));

        assertThat(lines).contains(audio(PCM_A)).endsWith("{\"type\":\"done\"}");
        assertThat(lines).noneMatch(l -> l.contains("internal_error"));
        assertThat(speechRows(messageId)).isEqualTo(1);
    }

    @Test
    void sessionDeleteFailsClosed_whenObjectDeletionFails() throws Exception {
        // 객체 삭제가 실패하면 세션을 지우지 않는다(행이 사라지면 객체를 찾을 방법이 없다).
        store.save(workspaceId, userId, messageId, pcmFile(1, 2));
        jdbc.update("UPDATE chat_message_speech SET object_key = ? WHERE message_id = ?", "", messageId);

        assertThatThrownBy(() -> sessionService.delete(workspaceId, userId, sessionId)).isInstanceOf(RuntimeException.class);
        assertThat(sessions.existsById(sessionId)).isTrue();
    }
}
