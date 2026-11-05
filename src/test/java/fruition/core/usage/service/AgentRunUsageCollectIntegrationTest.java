package fruition.core.usage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fruition.TestcontainersConfiguration;
import fruition.core.aitask.repository.PipelineTaskCancellationClient;
import fruition.core.aitask.service.AiTaskCancellationService;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.query.service.QueryEventBroker;
import fruition.core.query.service.QueryRunStore;
import fruition.core.usage.limit.AiUsageLimitExceededException;
import fruition.core.usage.limit.AiUsageLimiter;
import fruition.shared.http.PipelineClientFactory;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentRun 사용량 수집(#206). AI 원장은 가짜 서버로 대신한다. 앱의 수집 worker도 같은 대기열을 보지만 AI 주소가 없어 실패만
 * 하므로, 미뤄 둔 행을 다시 당겨 와서 처리한다. 금액은 입력 1,000토큰 = 1,000 milli-KRW가 되는 단가·환율이다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AgentRunUsageCollectIntegrationTest {

    private static final String EFFECTIVE_FROM = "2031-01-01T00:00:00Z";
    private static final String STARTED_AT = "2031-02-01T00:00:00Z";
    private static final String WORKSPACE = "ws-1";

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    @Autowired PipelineTaskCancellationClient pipeline;
    @Autowired WorkspaceAccessGuard access;
    @Autowired QueryRunStore queryRuns;
    @Autowired QueryEventBroker events;
    @Autowired MinioClient storage;
    @Autowired StorageProperties storageProperties;

    private HttpServer server;
    private final String user = "user-" + UUID.randomUUID();
    private final String model = "m-" + UUID.randomUUID();
    private final String runId = UUID.randomUUID().toString();
    private final List<String> ledger = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Runnable onFetch = () -> { };

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/model-usage/calls", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String calls = query.contains("run_id=" + runId) || query.contains("finished_from=") ? String.join(",", ledger) : "";
            onFetch.run();  // 응답에 싣는 호출을 정한 뒤에 일어나는 일이다.
            byte[] body = ("{\"calls\":[" + calls + "]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        jdbc.update("INSERT INTO fx_rates VALUES (?::timestamptz, ?) ON CONFLICT DO NOTHING", EFFECTIVE_FROM, new BigDecimal("1000"));
        jdbc.update("INSERT INTO pricing_policies VALUES (?::timestamptz, ?, ?, ?) ON CONFLICT DO NOTHING", EFFECTIVE_FROM, 0, 0, 0);
        BigDecimal unit = BigDecimal.ONE;
        jdbc.update("INSERT INTO ai_model_prices (provider, model, effective_from, input_usd_per_mtok, output_usd_per_mtok, "
                        + "cache_read_usd_per_mtok, cache_write_usd_per_mtok) VALUES ('openai', ?, ?::timestamptz, ?, ?, ?, ?)",
                model, EFFECTIVE_FROM, unit, unit, unit, unit);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void unenforcedPlanReadyCollectsPlanningCallsWithoutRegisteringOrReserving() {
        var charges = charges(false);
        ledger.add(call("agent_planning", "succeeded", 1000));

        charges.collectAgentPlan(runId);
        drain(charges);

        assertThat(chargedAmounts()).containsExactly(1000L);
        assertThat(runRows()).isEmpty();
        assertThat(ledgerTypes()).containsExactly("charge");
        assertThat(credits(false).credits(user).balanceKrwMilli()).isEqualTo(-1000);
        assertThat(credits(false).credits(user).reservedKrwMilli()).isZero();
    }

    @Test
    void unenforcedTerminalStateCollectsRestOnceEvenWhenPolledAgain() throws Exception {
        var charges = charges(false);
        ledger.add(call("agent_planning", "succeeded", 1000));
        charges.collectAgentPlan(runId);
        drain(charges);

        // 승인해도 enforce가 꺼져 있으면 등록·예약하지 않는다.
        charges.reserveAutonomousRun(runId, WORKSPACE, user);
        assertThat(runRows()).isEmpty();
        ledger.add(call("agent_execution", "succeeded", 2000));

        charges.endAutonomousRunIfTerminal(runId, WORKSPACE, user, status("executing"));
        assertThat(queued()).isFalse();
        assertThat(runRows()).isEmpty();

        charges.endAutonomousRunIfTerminal(runId, WORKSPACE, user, status("completed"));
        assertThat(queued()).isTrue();
        assertThat(runRows()).containsExactly("completed");
        drain(charges);

        // 화면이 끝난 run을 계속 조회해도 다시 넣지 않는다.
        charges.endAutonomousRunIfTerminal(runId, WORKSPACE, user, status("completed"));
        assertThat(queued()).isFalse();

        assertThat(chargedAmounts()).containsExactlyInAnyOrder(1000L, 2000L);
        assertThat(credits(false).credits(user).balanceKrwMilli()).isEqualTo(-3000);
        assertThat(credits(false).credits(user).reservedKrwMilli()).isZero();
        assertLedgerMatchesAccount();
    }

    @Test
    void recollectingAndReconcilingSameCallsKeepOneRowAndOneCharge() {
        var charges = charges(false);
        ledger.add(call("agent_planning", "succeeded", 1000));
        charges.collectAgentPlan(runId);
        drain(charges);
        charges.collectAgentPlan(runId);
        drain(charges);

        // 대사가 같은 호출을 다시 가져와도 call_id UNIQUE로 한 행이고, 누적 합계가 같아 더 차감하지 않는다.
        charges.collectFinished(Instant.parse(STARTED_AT), Instant.parse(STARTED_AT).plusSeconds(1));
        credits(false).settle(runId);

        assertThat(chargedAmounts()).containsExactly(1000L);
        assertThat(ledgerTypes()).containsExactly("charge");
        assertThat(credits(false).credits(user).balanceKrwMilli()).isEqualTo(-1000);
        assertLedgerMatchesAccount();
    }

    /** 승인 없이 끝난 계획(거절·취소·계획 실패)은 예약한 적이 없어도 닫혀 수집된다. enforce와 무관하다. */
    @Test
    void neverApprovedRunIsCollectedWhenItEndsInBothBillingModes() throws Exception {
        for (boolean enforce : List.of(false, true)) {
            for (String terminal : List.of("rejected", "cancelled", "failed")) {
                String other = UUID.randomUUID().toString();
                charges(enforce).endAutonomousRunIfTerminal(other, WORKSPACE, user, status(terminal));
                assertThat(jdbc.queryForObject("SELECT status FROM ai_task_runs WHERE id = ? AND kind = 'agent_run'",
                        String.class, other)).isEqualTo("completed");
                assertThat(jdbc.queryForList("SELECT run_id FROM usage_collect_queue WHERE run_id = ?", String.class, other)).hasSize(1);
                assertThat(jdbc.queryForList("SELECT id FROM credit_entries WHERE run_id = ? AND type = 'reserve'",
                        Long.class, other)).isEmpty();
            }
        }
    }

    /** 끝날 때 닫는 경로는 사용 한도 관문을 지나지 않는다. 이미 쓴 호출을 기록할 뿐 새 실행이 아니다. */
    @Test
    void closingAndCollectingNeverTripTheUsageLimit() throws Exception {
        var charges = charges(false, limiter(1));
        ledger.add(call("agent_planning", "succeeded", 1000));
        jdbc.update("INSERT INTO usage_charges (call_id, user_id, run_id, call_status, status, input_tokens, charge_krw_milli, "
                + "cost_usd_micro, started_at) VALUES (?, ?, 'earlier', 'succeeded', 'charged', 100, 0, 0, now())",
                "c-" + UUID.randomUUID(), user);

        charges.collectAgentPlan(runId);
        drain(charges);
        // enforce가 꺼져 있으면 승인 경로가 한도로 새로 거절되지 않는다.
        charges.reserveAutonomousRun(runId, WORKSPACE, user);
        charges.endAutonomousRunIfTerminal(runId, WORKSPACE, user, status("completed"));
        drain(charges);

        assertThat(chargedAmounts()).containsExactly(1000L);
    }

    /** enforce에서 처음 승인은 한도 관문을 그대로 지난다. plan-ready 수집이 실행을 먼저 등록해 관문을 건너뛰게 하면 안 된다. */
    @Test
    void enforcedFirstApprovalStillChecksUsageLimitAfterPlanCollected() {
        grant(1_000_000);
        jdbc.update("INSERT INTO usage_charges (call_id, user_id, run_id, call_status, status, input_tokens, charge_krw_milli, "
                + "cost_usd_micro, started_at) VALUES (?, ?, 'earlier', 'succeeded', 'charged', 100, 0, 0, now())",
                "c-" + UUID.randomUUID(), user);
        var charges = charges(true, limiter(1));

        charges.collectAgentPlan(runId);
        drain(charges);

        assertThatThrownBy(() -> charges.reserveAutonomousRun(runId, WORKSPACE, user))
                .isInstanceOf(AiUsageLimitExceededException.class);
        assertThat(runRows()).isEmpty();
        assertThat(credits(true).credits(user).reservedKrwMilli()).isZero();
    }

    @Test
    void enforcedReservationAndSettlementAreUnchangedByPlanCollection() throws Exception {
        grant(1_000_000);
        var charges = charges(true);
        ledger.add(call("agent_planning", "succeeded", 1000));

        // 첫 계획 수집은 실행을 등록하지 않고 쓴 만큼만 차감한다.
        charges.collectAgentPlan(runId);
        drain(charges);
        assertThat(runRows()).isEmpty();
        assertThat(credits(true).credits(user).balanceKrwMilli()).isEqualTo(999_000);
        assertThat(credits(true).credits(user).reservedKrwMilli()).isZero();

        // 처음 승인·수정은 지금처럼 등록하고 agent_run 상한을 예약한다.
        charges.reserveAutonomousRun(runId, WORKSPACE, user);
        long estimate = credits(true).estimate("agent_run");
        assertThat(runRows()).containsExactly("running");
        assertThat(credits(true).credits(user).reservedKrwMilli()).isEqualTo(estimate);

        // 수정 뒤 계획이 다시 나오면 진행 중이라 차감만 하고 예약은 그대로 둔다.
        ledger.add(call("agent_planning", "succeeded", 2000));
        charges.collectAgentPlan(runId);
        drain(charges);
        assertThat(credits(true).credits(user).balanceKrwMilli()).isEqualTo(997_000);
        assertThat(credits(true).credits(user).reservedKrwMilli()).isEqualTo(estimate);

        // 끝나면 닫고 남은 호출을 차감한 뒤 예약을 푼다.
        ledger.add(call("agent_execution", "succeeded", 4000));
        charges.endAutonomousRunIfTerminal(runId, WORKSPACE, user, status("completed"));
        drain(charges);
        assertThat(runRows()).containsExactly("completed");
        assertThat(credits(true).credits(user).balanceKrwMilli()).isEqualTo(993_000);
        assertThat(credits(true).credits(user).reservedKrwMilli()).isZero();
        assertLedgerMatchesAccount();
    }

    /** 자동 승인으로 실행이 바로 이어지면 plan-ready 수집이 진행 중 호출을 만나 뒤로 밀린다. 종료가 그 대기를 당긴다. */
    @Test
    void terminalStatePullsForwardCollectionPostponedByStartedCall() throws Exception {
        var charges = charges(false);
        ledger.add(call("agent_planning", "succeeded", 1000));
        ledger.add(call("agent_execution", "started", null));

        charges.collectAgentPlan(runId);
        claimAndCollect(charges);
        // 끝난 호출은 이미 보이고, 진행 중 호출 때문에 대기열에 남아 뒤로 밀린다.
        assertThat(chargedAmounts()).containsExactly(1000L);
        assertThat(queued()).isTrue();
        assertThat(jdbc.queryForObject("SELECT available_at > now() FROM usage_collect_queue WHERE run_id = ?",
                Boolean.class, runId)).isTrue();

        ledger.removeLast();
        ledger.add(call("agent_execution", "succeeded", 2000));
        charges.endAutonomousRunIfTerminal(runId, WORKSPACE, user, status("completed"));

        var row = jdbc.queryForMap("SELECT attempts, available_at <= now() AS due FROM usage_collect_queue WHERE run_id = ?", runId);
        assertThat(row.get("due")).isEqualTo(true);
        assertThat(((Number) row.get("attempts")).intValue()).isZero();
        drain(charges);
        assertThat(chargedAmounts()).containsExactlyInAnyOrder(1000L, 2000L);
    }

    /** 수집 중에 다시 넣은 요청은 끝난 수집이 지우지 않는다. 지우면 그 조회 뒤에 끝난 호출이 대사까지 밀린다. */
    @Test
    void requeueDuringCollectionCollectsCallFinishedAfterTheFetch() {
        var charges = charges(false);
        ledger.add(call("agent_planning", "succeeded", 1000));
        charges.collectAgentPlan(runId);
        onFetch = () -> {
            onFetch = () -> { };
            ledger.add(call("agent_execution", "succeeded", 2000));
            charges.collectAgentPlan(runId);
        };

        claimAndCollect(charges);
        drain(charges);

        assertThat(chargedAmounts()).containsExactlyInAnyOrder(1000L, 2000L);
    }

    /** 수집 중 실패한 뒤에도 다시 넣은 요청의 시각을 뒤로 밀지 않는다. */
    @Test
    void requeueDuringFailedCollectionIsNotPostponed() {
        var charges = charges(false);
        ledger.add(call("agent_execution", "started", null));
        charges.collectAgentPlan(runId);
        onFetch = () -> {
            onFetch = () -> { };
            charges.collectAgentPlan(runId);
        };

        claimAndCollect(charges);

        assertThat(jdbc.queryForObject("SELECT available_at <= now() FROM usage_collect_queue WHERE run_id = ?",
                Boolean.class, runId)).isTrue();
    }

    /** 이 실행을 대기열 맨 앞으로 당겨 한 번 수집한다. */
    private void claimAndCollect(UsageChargeService charges) {
        jdbc.update("UPDATE usage_collect_queue SET available_at = now() - interval '1 day' WHERE run_id = ?", runId);
        charges.collectPending();
    }

    private void drain(UsageChargeService charges) {
        for (int i = 0; i < 20 && queued(); i++) {
            jdbc.update("UPDATE usage_collect_queue SET available_at = now() - interval '1 day' WHERE run_id = ?", runId);
            charges.collectPending();
        }
        assertThat(queued()).isFalse();
    }

    private boolean queued() {
        return !jdbc.queryForList("SELECT run_id FROM usage_collect_queue WHERE run_id = ?", String.class, runId).isEmpty();
    }

    private List<String> runRows() {
        return jdbc.queryForList("SELECT status FROM ai_task_runs WHERE id = ?", String.class, runId);
    }

    private List<Long> chargedAmounts() {
        return jdbc.queryForList("SELECT charge_krw_milli FROM usage_charges WHERE run_id = ?", Long.class, runId);
    }

    private List<String> ledgerTypes() {
        return jdbc.queryForList("SELECT type FROM credit_entries WHERE run_id = ? ORDER BY id", String.class, runId);
    }

    private JsonNode status(String status) throws Exception {
        return mapper.readTree("{\"status\":\"" + status + "\"}");
    }

    private String call(String kind, String status, Integer input) {
        return """
                {"id":"c-%s","run_id":"%s","workspace_id":"%s","user_id":"%s","kind":"%s","provider":"openai",
                 "requested_model":"%s","model":"%s","status":"%s","input_tokens":%s,"cached_input_tokens":0,
                 "cache_creation_tokens":0,"output_tokens":0,"reasoning_tokens":0,"audio_seconds":null,
                 "input_characters":null,"started_at":"%s","finished_at":"%s"}"""
                .formatted(UUID.randomUUID(), runId, WORKSPACE, user, kind, model, model, status, input, STARTED_AT, STARTED_AT);
    }

    private UsageChargeService charges(boolean enforce) {
        return charges(enforce, limiter(Long.MAX_VALUE));
    }

    private UsageChargeService charges(boolean enforce, AiUsageLimiter limiter) {
        var credits = credits(enforce);
        var runs = new AiTaskCancellationService(jdbc, manager, pipeline, access, mapper, queryRuns, events, storage,
                storageProperties, credits, new fruition.core.wiki.repository.WikiWorkspaceVersionRepository(jdbc), limiter);
        return new UsageChargeService(jdbc, manager, runs, credits, mapper, new PipelineClientFactory("internal-test"),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/model-usage/calls", 900);
    }

    private AiUsageLimiter limiter(long dailyTokens) {
        return new AiUsageLimiter(jdbc, true, dailyTokens);
    }

    private CreditService credits(boolean enforce) {
        return new CreditService(jdbc, manager, environment, enforce, 300_000, 48);
    }

    private void grant(long amount) {
        jdbc.update("INSERT INTO credit_accounts (user_id) VALUES (?) ON CONFLICT DO NOTHING", user);
        jdbc.update("INSERT INTO credit_entries (user_id, type, amount, idempotency_key, reason) VALUES (?, 'grant', ?, ?, '테스트')",
                user, amount, "grant:" + UUID.randomUUID());
        jdbc.update("UPDATE credit_accounts SET balance = balance + ? WHERE user_id = ?", amount, user);
    }

    private void assertLedgerMatchesAccount() {
        var row = jdbc.queryForMap("""
                SELECT a.balance, a.reserved,
                       coalesce(sum(e.amount) FILTER (WHERE e.type NOT IN ('reserve', 'release')), 0) AS ledger_balance,
                       coalesce(sum(e.amount) FILTER (WHERE e.type IN ('reserve', 'release')), 0) AS ledger_reserved
                FROM credit_accounts a LEFT JOIN credit_entries e ON e.user_id = a.user_id
                WHERE a.user_id = ? GROUP BY a.balance, a.reserved
                """, user);
        assertThat(((Number) row.get("balance")).longValue()).isEqualTo(((Number) row.get("ledger_balance")).longValue());
        assertThat(((Number) row.get("reserved")).longValue()).isEqualTo(((Number) row.get("ledger_reserved")).longValue());
    }
}
