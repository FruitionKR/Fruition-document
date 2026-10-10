package fruition.core.usage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fruition.TestcontainersConfiguration;
import fruition.core.aitask.service.AiTaskCancellationService;
import fruition.core.document.repository.AiCommandOutboxWriter;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @Autowired AiCommandOutboxWriter outboxWriter;

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
                    + " token=" + exchange.getRequestHeaders().getFirst("X-Internal-Token")
                    + " request_id=" + exchange.getRequestHeaders().getFirst("X-Request-Id"));
            String response = switch (exchange.getRequestURI().getPath()) {
                case "/internal/model-usage/calls" -> "{\"calls\":[" + callsByQuery.getOrDefault(
                        query.replaceAll(".*(finished_from=[^&]*).*", "$1"), "") + "]}";
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
        assertThat(requests).allMatch(request -> request.contains(" token=internal-test "));

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
        notes.preview("ws-1", user, "회의", List.of(Map.of("text", "안건")),
                new fruition.core.authz.WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"));

        var runIds = jdbc.queryForList("SELECT id FROM ai_task_runs WHERE user_id = ? AND status = 'completed' ORDER BY kind",
                String.class, user);
        assertThat(runIds).hasSize(2);
        assertThat(runIds.get(0)).startsWith("meeting_notes:");
        assertThat(runIds.get(1)).startsWith("speech_transcription:");
        // ai-svc 계약: run_id는 X-Request-Id 헤더로만 보낸다. 본문·쿼리에 넣으면 버려지거나 422로 거부된다(#87).
        assertThat(requests).anySatisfy(request -> assertThat(request).startsWith("/speech/transcriptions?")
                .contains("user_id=" + user, "request_id=" + runIds.get(1)).doesNotContain("run_id"));
        assertThat(requests).anySatisfy(request -> assertThat(request).startsWith("/meeting-notes/preview")
                .contains("\"user_id\":\"" + user + "\"", "request_id=" + runIds.get(0)).doesNotContain("run_id"));

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

    @Test
    void reconcileSkipsMalformedCallAndSettlesAfterCommit() {
        versions("2045-01-01T00:00:00Z", "1000", 0, 0);
        price(model, "2045-01-01T00:00:00Z", "1");
        String runId = "run-" + UUID.randomUUID();
        credits.reserve(runId, user, "test_kind");
        Instant from = Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
        callsByQuery.put("finished_from=" + from, String.join(",",
                call("c-" + UUID.randomUUID(), runId, "ws-1", model, "succeeded", "not-a-time", 1000, 0, 0, 0, 0),
                call("c-" + UUID.randomUUID(), runId, "ws-1", model, "succeeded", "2045-02-01T00:00:00Z", 1000, 0, 0, 0, 0)));

        jdbc.update("UPDATE usage_reconcile_cursor SET reconciled_to = ?", Timestamp.from(from));
        // 앱의 대사 작업이 커서를 잠깐 잡을 수 있어 커서가 넘어갈 때까지 다시 부른다.
        for (int i = 0; i < 10 && !jdbc.queryForObject("SELECT reconciled_to > ? FROM usage_reconcile_cursor",
                Boolean.class, Timestamp.from(from)); i++) {
            charges.reconcile();
        }

        // 잘못된 호출 1건은 건너뛰고 나머지는 기록하며 커서를 넘긴다.
        assertThat(jdbc.queryForObject("SELECT reconciled_to > ? FROM usage_reconcile_cursor", Boolean.class,
                Timestamp.from(from))).isTrue();
        assertThat(jdbc.queryForList("SELECT charge_krw_milli FROM usage_charges WHERE run_id = ?", Long.class, runId))
                .containsExactly(1000L);
        // 커밋 뒤 정산이 차감하고, 실행 행이 없는 run은 끝난 것으로 보고 예약을 푼다.
        var result = credits.credits(user);
        assertThat(result.balanceKrwMilli()).isEqualTo(-1000);
        assertThat(result.reservedKrwMilli()).isZero();
    }

    @Test
    void callWithoutUserIsChargedToUserWhoReservedTheRun() {
        versions("2046-01-01T00:00:00Z", "1000", 0, 0);
        price(model, "2046-01-01T00:00:00Z", "1");
        String runId = charges.startRun("test_kind", "ws-1", user);
        charges.endRun(runId);
        callsByQuery.put("run_id=" + runId, call("c-" + UUID.randomUUID(), runId, "ws-1", model, "succeeded",
                "2046-02-01T00:00:00Z", 1000, 0, 0, 0, 0).replace("\"user_id\":\"" + user + "\"", "\"user_id\":\"unattributed\""));

        charges.collectRun(runId);

        assertThat(jdbc.queryForObject("SELECT user_id FROM usage_charges WHERE run_id = ?", String.class, runId)).isEqualTo(user);
        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(-1000);
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();
    }

    @Test
    void failedSyncCallClosesRunAndQueuesCollection() {
        assertThatThrownBy(() -> charges.track("test_kind", "ws-1", user, runId -> {
            throw new IllegalStateException("AI 실패");
        })).hasMessage("AI 실패");

        String runId = jdbc.queryForObject("SELECT id FROM ai_task_runs WHERE user_id = ?", String.class, user);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task_runs WHERE id = ?", String.class, runId)).isEqualTo("failed");
        // 앱의 수집 worker가 이미 꺼냈을 수 있어 대기열 대신 예약이 풀렸는지까지 기다린다.
        for (int i = 0; i < 10 && credits.credits(user).reservedKrwMilli() > 0; i++) {
            jdbc.update("UPDATE usage_collect_queue SET available_at = now() - interval '1 day' WHERE run_id = ?", runId);
            charges.collectPending();
        }
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();
    }

    @Test
    void completedConvertRunIsQueuedForCollection() {
        String runId = "convert:" + UUID.randomUUID();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            outboxWriter.begin(runId, "ws-1", user, "convert");
            outboxWriter.complete(runId);
        });

        assertThat(jdbc.queryForObject("SELECT status FROM ai_task_runs WHERE id = ?", String.class, runId)).isEqualTo("completed");
        for (int i = 0; i < 10 && credits.credits(user).reservedKrwMilli() > 0; i++) {
            jdbc.update("UPDATE usage_collect_queue SET available_at = now() - interval '1 day' WHERE run_id = ?", runId);
            charges.collectPending();
        }
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();
    }

    @Test
    void succeededCallWithoutAnyUsageNeedsReview() {
        versions("2047-01-01T00:00:00Z", "1000", 0, 0);
        price(model, "2047-01-01T00:00:00Z", "1");
        String runId = "run-" + UUID.randomUUID();
        callsByQuery.put("run_id=" + runId, call("c-" + UUID.randomUUID(), runId, "ws-1", model, "succeeded",
                "2047-02-01T00:00:00Z", null, null, null, null, null));

        charges.collectRun(runId);

        // 공급사가 usage를 주지 않은 성공 호출을 0원 charged로 확정하지 않는다(#98).
        assertThat(jdbc.queryForMap("SELECT status, charge_krw_milli FROM usage_charges WHERE run_id = ?", runId))
                .containsEntry("status", "needs_review").containsEntry("charge_krw_milli", 0L);
    }

    @Test
    void snapshotResponseIsPricedByRequestedModel() {
        versions("2048-01-01T00:00:00Z", "1000", 0, 0);
        price(model, "2048-01-01T00:00:00Z", "1");
        String runId = "run-" + UUID.randomUUID();
        String routedRun = "run-" + UUID.randomUUID();
        callsByQuery.put("run_id=" + runId, call("c-" + UUID.randomUUID(), runId, "ws-1", model, "succeeded",
                "2048-02-01T00:00:00Z", 1000, 0, 0, 0, 0)
                .replace("\"model\":\"" + model + "\"", "\"model\":\"" + model + "-2025-08-07\""));
        callsByQuery.put("run_id=" + routedRun, call("c-" + UUID.randomUUID(), routedRun, "ws-1", model, "succeeded",
                "2048-02-01T00:00:00Z", 1000, 0, 0, 0, 0)
                .replace("\"model\":\"" + model + "\"", "\"model\":\"other-" + model + "\""));

        charges.collectRun(runId);
        charges.collectRun(routedRun);

        // 응답 모델 단가가 없으면 요청 모델 단가로 계산하고, 어느 이름으로 찾았는지와 전환 분류를 남긴다(#99).
        assertThat(jdbc.queryForMap("SELECT status, charge_krw_milli, model, requested_model, price_model, model_routing "
                + "FROM usage_charges WHERE run_id = ?", runId))
                .containsEntry("status", "charged").containsEntry("charge_krw_milli", 1000L)
                .containsEntry("model", model + "-2025-08-07").containsEntry("requested_model", model)
                .containsEntry("price_model", model).containsEntry("model_routing", "snapshot");
        assertThat(jdbc.queryForMap("SELECT status, price_model, model_routing FROM usage_charges WHERE run_id = ?", routedRun))
                .containsEntry("status", "charged").containsEntry("price_model", model)
                .containsEntry("model_routing", "routed");
    }

    @Test
    void recomputeChargesUnpricedCallAfterPriceArrivesAndSettlesCredit() {
        versions("2049-01-01T00:00:00Z", "1000", 0, 0);
        String runId = "run-" + UUID.randomUUID();
        credits.reserve(runId, user, "test_kind");
        callsByQuery.put("run_id=" + runId, call("c-" + UUID.randomUUID(), runId, "ws-1", model, "succeeded",
                "2049-02-01T00:00:00Z", 1000, 0, 0, 0, 0));
        charges.collectRun(runId);
        assertThat(jdbc.queryForObject("SELECT status FROM usage_charges WHERE run_id = ?", String.class, runId))
                .isEqualTo("unpriced");
        assertThat(credits.credits(user).balanceKrwMilli()).isZero();

        // AI 원장을 다시 조회하지 않고 저장된 사용량으로 다시 계산한다.
        callsByQuery.clear();
        price(model, "2049-01-01T00:00:00Z", "1");
        // 앱의 재계산 작업이 같은 행을 잠깐 잠글 수 있어 바뀔 때까지 다시 부른다.
        for (int i = 0; i < 10 && !"charged".equals(jdbc.queryForObject(
                "SELECT status FROM usage_charges WHERE run_id = ?", String.class, runId)); i++) {
            charges.recompute();
        }

        assertThat(jdbc.queryForMap("SELECT status, charge_krw_milli, price_model FROM usage_charges WHERE run_id = ?", runId))
                .containsEntry("status", "charged").containsEntry("charge_krw_milli", 1000L)
                .containsEntry("price_model", model);
        // 수집과 같은 정산 경로로 차감된다.
        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(-1000);
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();

        // 확정된 행은 이후 단가가 바뀌어도 다시 계산하지 않는다.
        price(model, "2049-01-15T00:00:00Z", "9");
        charges.recompute();
        assertThat(jdbc.queryForObject("SELECT charge_krw_milli FROM usage_charges WHERE run_id = ?", Long.class, runId))
                .isEqualTo(1000L);
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
                 "input_characters":null,"started_at":"%s","finished_at":"%s"}"""
                .formatted(id, runId, workspaceId, user, model, model, status, input, cached, creation, output, reasoning,
                        startedAt, startedAt);
    }
}
