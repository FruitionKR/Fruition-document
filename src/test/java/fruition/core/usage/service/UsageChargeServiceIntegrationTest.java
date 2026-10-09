package fruition.core.usage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fruition.TestcontainersConfiguration;
import fruition.core.aitask.service.AiTaskCancellationService;
import fruition.core.meeting.MeetingNotesClient;
import fruition.core.speech.SpeechTranscriptionClient;
import fruition.shared.http.PipelineClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 원장은 가짜 서버로 대신한다. 환율·정책 행은 테스트 사이에 공유되므로 테스트마다 다른 해를 쓰고,
 * 모델 이름은 테스트마다 새로 만든다. 앱의 수집 worker도 같은 DB를 보지만 AI 주소가 없어 실패만 하므로,
 * 대기열 테스트는 그 worker가 미뤄 둔 행을 다시 당겨 와서 처리한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UsageChargeServiceIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired AiTaskCancellationService runs;
    @Autowired CreditService credits;
    @Autowired ObjectMapper mapper;

    private HttpServer server;
    private UsageChargeService charges;
    private String base;
    private String user;
    private String model;
    private final Map<String, String> callsByQuery = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestURI().getPath() + "?" + query + " " + body
                    + " token=" + exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            String response = switch (exchange.getRequestURI().getPath()) {
                case "/internal/model-usage/calls" -> "{\"calls\":[" + callsByQuery.getOrDefault(
                        query.replaceAll("&finished_to=.*", ""), "") + "]}";
                case "/speech/transcriptions" -> "{\"text\":\"안녕하세요\"}";
                default -> "{\"summary\":[]}";
            };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        charges = new UsageChargeService(jdbc, manager, runs, credits, mapper, new PipelineClientFactory("internal-test"),
                base + "/internal/model-usage/calls");
        user = "user-" + UUID.randomUUID();
        model = "m-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sameCallCollectedManyTimesIsChargedOnceAndKeepsAmountAfterPriceChange() {
        versions("2041-01-01T00:00:00Z", "1400", 3000, 1000);
        price(model, "2041-01-01T00:00:00Z", "1");
        // 입력 1,000 = 일반 500 + 캐시 읽기 400 + 캐시 생성 100, 출력 500(reasoning 300 포함)
        String call = call("c-" + UUID.randomUUID(), "run-a", "ws-1", model, "succeeded", "2041-02-01T00:00:00Z",
                1000, 400, 100, 500, 300);
        callsByQuery.put("run_id=run-a", call);
        callsByQuery.put("finished_from=2041-02-01T00:00:00Z", call);

        charges.collectRun("run-a");
        charges.collectRun("run-a");
        charges.collectFinished(Instant.parse("2041-02-01T00:00:00Z"), Instant.parse("2041-02-02T00:00:00Z"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM usage_charges WHERE user_id = ?", user);
        assertThat(row.get("status")).isEqualTo("charged");
        // 원가는 1,665 micro-USD. 캐시·reasoning 토큰을 일반 입력·출력에 다시 더하지 않는다.
        assertThat(row.get("cost_usd_micro")).isEqualTo(1665L);
        assertThat(row.get("charge_krw_milli")).isEqualTo(3334L);
        assertThat(row.get("reasoning_tokens")).isEqualTo(300L);
        assertThat(requests).allMatch(request -> request.endsWith("token=internal-test"));

        // 호출 전부터 적용되는 비싼 단가 행을 넣고 다시 수집해도 확정된 청구는 다시 계산하지 않는다.
        price(model, "2041-01-15T00:00:00Z", "9");
        charges.collectRun("run-a");
        assertThat(jdbc.queryForObject("SELECT charge_krw_milli FROM usage_charges WHERE user_id = ?", Long.class, user))
                .isEqualTo(3334L);
    }

    @Test
    void unpricedAndUnknownCallsAreMarkedAndUnpricedIsChargedAfterPriceArrives() {
        versions("2042-01-01T00:00:00Z", "1000", 0, 0);
        String unpriced = call("c-" + UUID.randomUUID(), "run-b", "ws-1", model, "succeeded",
                "2042-02-01T00:00:00Z", 1_000_000, 0, 0, 0, 0);
        String unknown = call("c-" + UUID.randomUUID(), "run-b", "ws-1", model, "unknown",
                "2042-02-01T00:00:00Z", null, null, null, null, null);
        String running = call("c-" + UUID.randomUUID(), "run-b", "ws-1", model, "started",
                "2042-02-01T00:00:00Z", null, null, null, null, null);
        callsByQuery.put("run_id=run-b", String.join(",", unpriced, unknown, running));

        charges.collectRun("run-b");

        assertThat(jdbc.queryForList("SELECT status || ':' || coalesce(charge_krw_milli::text, 'null') "
                + "FROM usage_charges WHERE user_id = ? ORDER BY status", String.class, user))
                .containsExactly("needs_review:0", "unpriced:null");

        price(model, "2042-01-01T00:00:00Z", "1");
        charges.collectRun("run-b");

        assertThat(jdbc.queryForList("SELECT status || ':' || charge_krw_milli FROM usage_charges WHERE user_id = ? "
                + "ORDER BY status", String.class, user)).containsExactly("charged:1000000", "needs_review:0");
    }

    @Test
    void userSeesOwnPeriodTotalAcrossWorkspaces() {
        versions("2043-01-01T00:00:00Z", "1000", 0, 0);
        price(model, "2043-01-01T00:00:00Z", "1");
        callsByQuery.put("run_id=run-c", String.join(",",
                call("c-" + UUID.randomUUID(), "run-c", "ws-1", model, "succeeded", "2043-02-01T00:00:00Z", 1000, 0, 0, 0, 0),
                call("c-" + UUID.randomUUID(), "run-c", "ws-2", model, "succeeded", "2043-02-02T00:00:00Z", 2000, 0, 0, 0, 0),
                call("c-" + UUID.randomUUID(), "run-c", "ws-2", model, "succeeded", "2043-03-01T00:00:00Z", 4000, 0, 0, 0, 0)));
        charges.collectRun("run-c");

        var summary = charges.summary(user, Instant.parse("2043-02-01T00:00:00Z"), Instant.parse("2043-03-01T00:00:00Z"));

        // 1,000 + 2,000 tokens × 1 USD/1M × 1,000 KRW = 3 KRW
        assertThat(summary.chargeKrwMilli()).isEqualTo(3000);
        assertThat(summary.models()).singleElement().satisfies(line -> {
            assertThat(line.calls()).isEqualTo(2);
            assertThat(line.inputTokens()).isEqualTo(3000);
        });
        assertThat(charges.summary("someone-else", Instant.parse("2043-02-01T00:00:00Z"),
                Instant.parse("2043-03-01T00:00:00Z")).models()).isEmpty();
    }

    @Test
    void syncCallsSendRunIdAndUserAndQueueCollection() {
        versions("2044-01-01T00:00:00Z", "1000", 0, 0);
        var speech = new SpeechTranscriptionClient(new PipelineClientFactory("internal-test"),
                base + "/speech/transcriptions", 5, charges);
        var notes = new MeetingNotesClient(new PipelineClientFactory("internal-test"), base + "/meeting-notes/preview", 5, charges);

        assertThat(speech.transcribe("ws-1", user, MediaType.parseMediaType("audio/wav"), new byte[] {1})).isEqualTo("안녕하세요");
        notes.preview("ws-1", user, "회의", List.of(Map.of("text", "안건")));

        var runIds = jdbc.queryForList("SELECT id FROM ai_task_runs WHERE user_id = ? AND status = 'completed' ORDER BY kind",
                String.class, user);
        assertThat(runIds).hasSize(2);
        assertThat(runIds.get(0)).startsWith("meeting_notes:");
        assertThat(runIds.get(1)).startsWith("speech_transcription:");
        assertThat(requests).anySatisfy(request -> assertThat(request).startsWith("/speech/transcriptions?")
                .contains("user_id=" + user, "run_id=" + runIds.get(1)));
        assertThat(requests).anySatisfy(request -> assertThat(request).startsWith("/meeting-notes/preview")
                .contains("\"user_id\":\"" + user + "\"", "\"run_id\":\"" + runIds.get(0) + "\""));

        // 대기열의 run을 worker가 꺼내 수집하고 지운다.
        callsByQuery.put("run_id=" + runIds.get(1), call("c-" + UUID.randomUUID(), runIds.get(1),
                "ws-1", "whisper-" + model, "succeeded", "2044-02-01T00:00:00Z", 0, 0, 0, 0, 0));
        for (int i = 0; i < 10 && !jdbc.queryForList("SELECT run_id FROM usage_collect_queue WHERE run_id IN (?, ?)",
                String.class, runIds.get(0), runIds.get(1)).isEmpty(); i++) {
            jdbc.update("UPDATE usage_collect_queue SET available_at = now() - interval '1 day' WHERE run_id IN (?, ?)",
                    runIds.get(0), runIds.get(1));
            charges.collectPending();
        }
        assertThat(jdbc.queryForList("SELECT run_id FROM usage_collect_queue WHERE run_id IN (?, ?)",
                String.class, runIds.get(0), runIds.get(1))).isEmpty();
        assertThat(jdbc.queryForList("SELECT status FROM usage_charges WHERE run_id = ?", String.class, runIds.get(1)))
                .containsExactly("unpriced");
    }

    private void versions(String effectiveFrom, String krwPerUsd, int marginBp, int vatBp) {
        jdbc.update("INSERT INTO fx_rates VALUES (?::timestamptz, ?) ON CONFLICT DO NOTHING", effectiveFrom,
                new BigDecimal(krwPerUsd));
        jdbc.update("INSERT INTO pricing_policies VALUES (?::timestamptz, ?, ?) ON CONFLICT DO NOTHING", effectiveFrom,
                marginBp, vatBp);
    }

    /** 입력·출력 단가가 같고 캐시 읽기 0.1배, 캐시 생성 1.25배, 출력 2배다. */
    private void price(String model, String effectiveFrom, String input) {
        BigDecimal in = new BigDecimal(input);
        jdbc.update("INSERT INTO ai_model_prices (provider, model, effective_from, input_usd_per_mtok, output_usd_per_mtok, "
                        + "cache_read_usd_per_mtok, cache_write_usd_per_mtok) VALUES ('openai', ?, ?::timestamptz, ?, ?, ?, ?)",
                model, effectiveFrom, in, in.multiply(BigDecimal.TWO), in.multiply(new BigDecimal("0.1")),
                in.multiply(new BigDecimal("1.25")));
    }

    private String call(String id, String runId, String workspaceId, String model, String status, String startedAt,
                        Integer input, Integer cached, Integer creation, Integer output, Integer reasoning) {
        return """
                {"id":"%s","run_id":"%s","workspace_id":"%s","user_id":"%s","kind":"agent","provider":"openai",
                 "requested_model":"%s","model":"%s","status":"%s","input_tokens":%s,"cached_input_tokens":%s,
                 "cache_creation_tokens":%s,"output_tokens":%s,"reasoning_tokens":%s,"audio_seconds":null,
                 "tts_characters":null,"started_at":"%s","finished_at":"%s"}"""
                .formatted(id, runId, workspaceId, user, model, model, status, input, cached, creation, output, reasoning,
                        startedAt, startedAt);
    }
}
