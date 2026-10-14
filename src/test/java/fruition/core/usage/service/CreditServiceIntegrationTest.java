package fruition.core.usage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fruition.TestcontainersConfiguration;
import fruition.core.aitask.repository.PipelineTaskCancellationClient;
import fruition.core.aitask.service.AiTaskCancellationService;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.query.service.QueryEventBroker;
import fruition.core.query.service.QueryRunStore;
import fruition.core.speech.SpeechTranscriptionClient;
import fruition.shared.http.PipelineClientFactory;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * enforce·예상 상한이 다른 서비스를 테스트마다 직접 만든다. 사용자 ID를 테스트마다 새로 만들어 원장이 섞이지 않는다.
 * 앱의 예약 정리 작업은 48시간 지난 예약만 보므로 이 테스트의 예약과 겹치지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CreditServiceIntegrationTest {

    private static final long ESTIMATE = 300_000;

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
    @Autowired fruition.core.document.repository.AiCommandOutboxRepository outboxRepository;
    @Autowired jakarta.persistence.EntityManager entityManager;

    private final String user = "user-" + UUID.randomUUID();

    @Test
    void concurrentReservationsNeverExceedAvailableBalance() throws Exception {
        CreditService credits = credits(true);
        grant(1_000_000);

        var pool = Executors.newFixedThreadPool(10);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String runId = "run-" + UUID.randomUUID();
            Callable<Boolean> reserve = () -> {
                try {
                    credits.reserve(runId, user, "test_kind");
                    return true;
                } catch (CreditService.InsufficientCreditException e) {
                    return false;
                }
            };
            results.add(pool.submit(reserve));
        }
        int reserved = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) reserved++;
        }
        pool.shutdown();

        // 가용 1,000,000에 300,000씩 3건을 예약하면 남은 100,000은 최저 비용(30,000) 이상이라 4번째는 그만큼만 예약하고,
        // 가용 0이 된 뒤의 요청은 모두 거절된다. 동시에 몰려도 예약 합계는 잔액을 넘지 않는다.
        assertThat(reserved).isEqualTo(4);
        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(1_000_000);
        assertThat(credits.credits(user).availableKrwMilli()).isZero();
        assertLedgerMatchesAccount();
    }

    @Test
    void settlingSameRunTwiceChargesOnceAndLateCallsAddOnlyTheDifference() {
        CreditService credits = credits(true);
        grant(1_000_000);
        String runId = "run-" + UUID.randomUUID();
        credits.reserve(runId, user, "test_kind");
        credits.reserve(runId, user, "test_kind");
        charge(runId, 400_000);

        credits.settle(runId);
        credits.settle(runId);

        var result = credits.credits(user);
        assertThat(result.balanceKrwMilli()).isEqualTo(600_000);
        assertThat(result.reservedKrwMilli()).isZero();
        assertThat(result.entries()).extracting(CreditService.Entry::type)
                .containsExactly("release", "charge", "reserve", "grant");

        // 대사가 늦게 채운 호출은 늘어난 만큼만 더 차감한다.
        charge(runId, 100_000);
        credits.settle(runId);
        credits.settle(runId);
        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(500_000);
        assertLedgerMatchesAccount();
    }

    @Test
    void negativeBalanceAfterOverrunBlocksNextRequest() {
        CreditService credits = credits(true);
        grant(400_000);
        String runId = "run-" + UUID.randomUUID();
        credits.reserve(runId, user, "test_kind");
        // 실제 사용이 예약보다 크면 그대로 차감해 음수가 된다.
        charge(runId, 900_000);
        credits.settle(runId);

        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(-500_000);
        assertThatThrownBy(() -> credits.reserve("run-" + UUID.randomUUID(), user, "test_kind"))
                .isInstanceOf(CreditService.InsufficientCreditException.class);
        assertLedgerMatchesAccount();
    }

    @Test
    void enforcedShortageRejectsBeforeAiCallAndUnenforcedOnlyRecords() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/speech/transcriptions", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"text\":\"안녕\"}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/speech/transcriptions";

            assertThatThrownBy(() -> speech(credits(true), endpoint)
                    .transcribe("ws-1", user, MediaType.parseMediaType("audio/wav"), new byte[] {1}))
                    .isInstanceOf(CreditService.InsufficientCreditException.class);
            assertThat(requests.get()).isZero();
            assertThat(jdbc.queryForList("SELECT id FROM ai_task_runs WHERE user_id = ?", String.class, user)).isEmpty();

            assertThat(speech(credits(false), endpoint)
                    .transcribe("ws-1", user, MediaType.parseMediaType("audio/wav"), new byte[] {1})).isEqualTo("안녕");
            assertThat(requests.get()).isEqualTo(1);
            assertThat(credits(false).credits(user).entries()).extracting(CreditService.Entry::type)
                    .containsExactly("reserve");
            assertLedgerMatchesAccount();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void runInProgressIsChargedButKeepsReservationUntilItEnds() {
        CreditService credits = credits(true);
        grant(1_000_000);
        String runId = "run-" + UUID.randomUUID();
        run(runId, "running");
        credits.reserve(runId, user, "test_kind");
        // 오래된 예약이어도 진행 중인 실행은 정리 작업이 풀지 않는다.
        jdbc.update("UPDATE credit_entries SET created_at = now() - interval '49 hours' WHERE run_id = ?", runId);
        charge(runId, 100_000);

        credits.settle(runId);
        credits.releaseStale();

        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(900_000);
        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(ESTIMATE);

        jdbc.update("UPDATE ai_task_runs SET status = 'completed' WHERE id = ?", runId);
        credits.releaseStale();
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();
        assertLedgerMatchesAccount();
    }

    @Test
    void runningRunWithoutSignalForTooLongIsTreatedAsEnded() {
        CreditService credits = credits(true);
        grant(1_000_000);
        String runId = "run-" + UUID.randomUUID();
        run(runId, "running");
        credits.reserve(runId, user, "test_kind");
        // 프로세스가 죽어 running으로 남은 실행은 기준 시간이 지나면 끝난 것으로 보고 푼다.
        jdbc.update("UPDATE ai_task_runs SET updated_at = now() - interval '49 hours' WHERE id = ?", runId);

        credits.settle(runId);

        assertThat(credits.credits(user).reservedKrwMilli()).isZero();
        assertLedgerMatchesAccount();
    }

    @Test
    void renewChargesUsageAndReservesNextIntervalOrRejectsWhenShort() {
        CreditService credits = credits(true);
        grant(1_000_000);
        String runId = "run-" + UUID.randomUUID();
        run(runId, "running");
        credits.reserve(runId, user, "test_kind");
        charge(runId, 300_000);

        credits.renew(runId, user, "test_kind", 1);
        credits.renew(runId, user, "test_kind", 1);

        var result = credits.credits(user);
        assertThat(result.balanceKrwMilli()).isEqualTo(700_000);
        assertThat(result.reservedKrwMilli()).isEqualTo(ESTIMATE);

        // 차감 뒤 가용 잔액이 최저 비용(30,000)보다 작으면 거절하고 아무것도 반영하지 않는다.
        charge(runId, 680_000);
        assertThatThrownBy(() -> credits.renew(runId, user, "test_kind", 2))
                .isInstanceOf(CreditService.InsufficientCreditException.class);
        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(700_000);

        // 끝난 실행은 다시 예약하지 않고, 종료 정산이 남은 사용량을 차감하고 예약을 푼다.
        jdbc.update("UPDATE ai_task_runs SET status = 'completed' WHERE id = ?", runId);
        credits.renew(runId, user, "test_kind", 2);
        credits.settle(runId);
        result = credits.credits(user);
        assertThat(result.balanceKrwMilli()).isEqualTo(20_000);
        assertThat(result.reservedKrwMilli()).isZero();
        assertLedgerMatchesAccount();
    }

    @Test
    void reserveRejectsBelowMinimumAndReservesOnlyAvailableWhenBetweenMinimumAndEstimate() {
        CreditService credits = credits(true);
        // 최저 비용은 설정이 없으면 예상 상한의 10%(30,000)다.
        grant(29_999);
        assertThatThrownBy(() -> credits.reserve("run-" + UUID.randomUUID(), user, "test_kind"))
                .isInstanceOf(CreditService.InsufficientCreditException.class)
                .hasMessageContaining("작업을 시작하지 않았습니다");
        assertThat(credits.credits(user).entries()).extracting(CreditService.Entry::type).containsExactly("grant");

        // 최저 이상 상한 미만이면 통과하고 가용 잔액만큼만 예약한다. 같은 사용자의 다음 요청은 가용이 0이라 거절된다.
        grant(1);
        credits.reserve("run-" + UUID.randomUUID(), user, "test_kind");
        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(30_000);
        assertThatThrownBy(() -> credits.reserve("run-" + UUID.randomUUID(), user, "test_kind"))
                .isInstanceOf(CreditService.InsufficientCreditException.class);
        assertLedgerMatchesAccount();
    }

    @Test
    void reserveOfZeroBalanceAndOfNegativeBalanceIsRejected() {
        CreditService credits = credits(true);
        assertThatThrownBy(() -> credits.reserve("run-" + UUID.randomUUID(), user, "test_kind"))
                .isInstanceOf(CreditService.InsufficientCreditException.class);
        grant(500_000);
        String runId = "run-" + UUID.randomUUID();
        credits.reserve(runId, user, "test_kind");
        charge(runId, 800_000);
        credits.settle(runId);
        assertThat(credits.credits(user).balanceKrwMilli()).isEqualTo(-300_000);
        assertThatThrownBy(() -> credits.reserve("run-" + UUID.randomUUID(), user, "test_kind"))
                .isInstanceOf(CreditService.InsufficientCreditException.class);
    }

    @Test
    void configuredMinimumOverridesTenPercentOfEstimate() {
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("app.billing.estimate.test_kind", "300000")
                .withProperty("app.billing.minimum.test_kind", "200000");
        CreditService credits = new CreditService(jdbc, manager, env, true, ESTIMATE, 48);
        grant(150_000);
        assertThatThrownBy(() -> credits.reserve("run-" + UUID.randomUUID(), user, "test_kind"))
                .isInstanceOf(CreditService.InsufficientCreditException.class);
        grant(50_000);
        credits.reserve("run-" + UUID.randomUUID(), user, "test_kind");
        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(200_000);
    }

    @Test
    void unenforcedReservesFullEstimateAndNeverRejects() {
        CreditService credits = credits(false);
        credits.reserve("run-" + UUID.randomUUID(), user, "test_kind");
        grant(1_000);
        credits.reserve("run-" + UUID.randomUUID(), user, "test_kind");
        credits.reserve("", "", "test_kind");

        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(2 * ESTIMATE);
        assertLedgerMatchesAccount();
    }

    @Test
    void enforcedRequestWithoutUserIsRejected() {
        assertThatThrownBy(() -> credits(true).reserve("run-" + UUID.randomUUID(), " ", "test_kind"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> credits(true).reserve("run-" + UUID.randomUUID(), null, "test_kind"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restoreStartsWithoutReservationEvenWhenEnforcedWithZeroBalance() {
        CreditService credits = credits(true);
        var writer = new fruition.core.document.repository.AiCommandOutboxWriter(outboxRepository, mapper, jdbc,
                entityManager, credits);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        for (String kind : List.of("restore_ingest", "restore_lint")) {
            String runId = "run-" + UUID.randomUUID();
            transaction.executeWithoutResult(tx -> writer.begin(runId, "ws-1", user, kind));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE id = ?", Integer.class, runId)).isEqualTo(1);
        }
        credits.checkAdmission(user, "restore_ingest");
        assertThat(credits.credits(user).entries()).isEmpty();
    }

    @Test
    void workEntryPointsRejectBeforeRegisteringAnyRun() {
        CreditService credits = credits(true);
        // 모든 outbox 작업(query·agent·ingest·convert·lint)은 begin·reserve를, skill 작업은 register를 거친다.
        var writer = new fruition.core.document.repository.AiCommandOutboxWriter(outboxRepository, mapper, jdbc,
                entityManager, credits);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        for (String kind : List.of("query", "agent", "document", "convert", "lint")) {
            assertThatThrownBy(() -> transaction.executeWithoutResult(tx ->
                    writer.begin("run-" + UUID.randomUUID(), "ws-1", user, kind)))
                    .isInstanceOf(CreditService.InsufficientCreditException.class);
        }
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx ->
                writer.reserve("run-" + UUID.randomUUID(), "ws-1", user, "query")))
                .isInstanceOf(CreditService.InsufficientCreditException.class);

        var runs = new AiTaskCancellationService(jdbc, manager, pipeline, access, mapper, queryRuns, events, storage,
                storageProperties, credits);
        for (String kind : List.of("skill_author", "skill_publish", "skill_update")) {
            assertThatThrownBy(() -> runs.register(mapper.createObjectNode().put("run_id", "run-" + UUID.randomUUID())
                    .put("kind", kind).put("workspace_id", "ws-1").put("user_id", user)))
                    .isInstanceOf(CreditService.InsufficientCreditException.class);
        }

        // 거절된 요청은 작업 행도 예약도 남기지 않는다.
        assertThat(jdbc.queryForList("SELECT id FROM ai_task_runs WHERE user_id = ?", String.class, user)).isEmpty();
        assertThat(credits.credits(user).entries()).isEmpty();

        grant(1_000_000);
        transaction.executeWithoutResult(tx -> writer.begin("run-" + UUID.randomUUID(), "ws-1", user, "query"));
        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(300_000);
    }

    @Test
    void renewKeepsOtherRunsReservationsOutOfAvailableAndShrinksPartialReservation() {
        CreditService credits = credits(true);
        grant(500_000);
        String a = "run-a-" + UUID.randomUUID();
        String b = "run-b-" + UUID.randomUUID();
        run(a, "running");
        run(b, "running");
        credits.reserve(a, user, "test_kind");  // 300,000
        credits.reserve(b, user, "test_kind");  // 가용 200,000만 예약
        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(500_000);
        charge(a, 50_000);

        // a는 50,000을 차감해 잔액 450,000이고 b의 200,000을 뺀 가용 250,000만 다시 예약한다.
        credits.renew(a, user, "test_kind", 1);
        var result = credits.credits(user);
        assertThat(result.balanceKrwMilli()).isEqualTo(450_000);
        assertThat(result.reservedKrwMilli()).isEqualTo(450_000);

        // b는 a의 250,000을 뺀 가용 200,000을 그대로 다시 예약한다.
        credits.renew(b, user, "test_kind", 1);
        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(450_000);

        // a를 한 번 더 갱신해도 b의 예약만 제외하고 계산해 같은 금액이다.
        credits.renew(a, user, "test_kind", 2);
        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(450_000);
        assertLedgerMatchesAccount();
    }

    @Test
    void autonomousRunIsRegisteredReservedRenewedAndClosedOnTerminalState() throws Exception {
        var charges = charges(credits(true), "http://127.0.0.1:1/unused");
        grant(1_000_000);
        String runId = UUID.randomUUID().toString();

        // 처음 승인·수정: 실행을 등록하고 agent_run 상한(500,000)을 예약한다.
        charges.reserveAutonomousRun(runId, "ws-1", user);
        assertThat(credits(true).credits(user).reservedKrwMilli()).isEqualTo(500_000);
        assertThat(jdbc.queryForList("SELECT id FROM ai_task_runs WHERE id = ? AND kind = 'agent_run'", String.class, runId))
                .hasSize(1);

        // 같은 AgentRun의 다음 수정·승인은 구간 갱신과 같은 규칙으로 예약을 바꾼다(키가 겹치지 않는다).
        charges.reserveAutonomousRun(runId, "ws-1", user);
        charges.reserveAutonomousRun(runId, "ws-1", user);
        assertThat(credits(true).credits(user).reservedKrwMilli()).isEqualTo(500_000);

        // 진행 중에는 닫지 않고, 끝난 상태가 오면 닫아 정산 대기열에 넣는다.
        charges.endAutonomousRunIfTerminal(runId, mapper.readTree("{\"status\":\"executing\"}"));
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task_runs WHERE id = ?", String.class, runId)).isEqualTo("running");
        charges.endAutonomousRunIfTerminal(runId, mapper.readTree("{\"status\":\"completed\"}"));
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task_runs WHERE id = ?", String.class, runId)).isEqualTo("completed");
        charge(runId, 120_000);
        credits(true).settle(runId);
        var result = credits(true).credits(user);
        assertThat(result.reservedKrwMilli()).isZero();
        assertThat(result.balanceKrwMilli()).isEqualTo(880_000);
        assertLedgerMatchesAccount();
    }

    @Test
    void autonomousRunRejectedWhenShortAndSkippedWhenNotEnforced() {
        String runId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> charges(credits(true), "http://127.0.0.1:1/unused")
                .reserveAutonomousRun(runId, "ws-1", user))
                .isInstanceOf(CreditService.InsufficientCreditException.class)
                .hasMessageContaining("중단했습니다");
        assertThat(jdbc.queryForList("SELECT id FROM ai_task_runs WHERE id = ?", String.class, runId)).isEmpty();

        // enforce가 꺼져 있으면 등록도 예약도 하지 않는다.
        charges(credits(false), "http://127.0.0.1:1/unused").reserveAutonomousRun(runId, "ws-1", user);
        assertThat(jdbc.queryForList("SELECT id FROM ai_task_runs WHERE id = ?", String.class, runId)).isEmpty();

        // 닫힌(종료 정산한) 실행을 다시 갱신해도 새로 예약하지 않는다.
        grant(1_000_000);
        var charges = charges(credits(true), "http://127.0.0.1:1/unused");
        charges.reserveAutonomousRun(runId, "ws-1", user);
        charges.endAutonomousRun(runId);
        credits(true).settle(runId);
        charges.reserveAutonomousRun(runId, "ws-1", user);
        assertThat(credits(true).credits(user).reservedKrwMilli()).isZero();
        assertLedgerMatchesAccount();
    }

    @Test
    void failedForwardKeepsRunOpenSoNextAttemptChecksBalanceAgain() {
        var credits = credits(true);
        var charges = charges(credits, "http://127.0.0.1:1/unused");
        grant(600_000);
        String runId = UUID.randomUUID().toString();

        charges.reserveAutonomousRun(runId, "ws-1", user);  // 500,000 예약
        charges.abortAutonomousAttempt(runId, user);        // ai-svc가 거절 -> 이번 시도의 예약만 푼다
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task_runs WHERE id = ?", String.class, runId)).isEqualTo("running");

        // 그 사이 잔액이 바닥나면(다른 사용) 다시 승인할 때 402다.
        jdbc.update("INSERT INTO credit_entries (user_id, type, amount, idempotency_key, reason) VALUES (?, 'adjust', -590000, ?, '테스트')",
                user, "adj:" + UUID.randomUUID());
        jdbc.update("UPDATE credit_accounts SET balance = balance - 590000 WHERE user_id = ?", user);
        assertThatThrownBy(() -> charges.reserveAutonomousRun(runId, "ws-1", user))
                .isInstanceOf(CreditService.InsufficientCreditException.class).hasMessageContaining("중단했습니다");
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();

        // 잔액이 있으면 다시 통과하고, 진짜 종료 정산이 해제한다(종료 해제 키는 아직 안 쓰였다).
        grant(600_000);
        charges.reserveAutonomousRun(runId, "ws-1", user);
        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(500_000);
        charges.endAutonomousRun(runId);
        credits.settle(runId);
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();
        assertLedgerMatchesAccount();
    }

    @Test
    void approvalPendingBeyondStaleWindowIsKeptInProgressAndStillReleasable() {
        var credits = credits(true);
        var charges = charges(credits, "http://127.0.0.1:1/unused");
        grant(2_000_000);
        String runId = UUID.randomUUID().toString();
        charges.reserveAutonomousRun(runId, "ws-1", user);
        // 승인 대기가 오래돼 정리 작업이 예약을 풀었다.
        jdbc.update("UPDATE ai_task_runs SET updated_at = now() - interval '49 hours' WHERE id = ?", runId);
        jdbc.update("UPDATE credit_entries SET created_at = now() - interval '49 hours' WHERE run_id = ?", runId);
        credits.releaseStale();
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();

        // 이어서 승인하면 실행이 다시 진행 중으로 갱신되고 새로 예약한다. 그 예약도 종료 정산이 푼다.
        charges.reserveAutonomousRun(runId, "ws-1", user);
        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(500_000);
        charges.endAutonomousRun(runId);
        credits.settle(runId);
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();
        assertLedgerMatchesAccount();
    }

    @Test
    void postIngestFinishedBeforeIngestResultDoesNotHoldTheIngest() {
        var credits = credits(true);
        var charges = charges(credits, "http://127.0.0.1:1/unused");
        String ingest = "ingest-" + UUID.randomUUID();
        // post_ingest 종료 이벤트가 먼저 와서 종료를 기록한다(대기열에는 아직 ingest가 없다).
        charges.releasePostIngestHold("post-" + UUID.randomUUID(), ingest, "ws-1", user);
        // 뒤늦게 온 ingest 결과는 보류하지 않는다.
        charges.enqueueAfterPostIngest(ingest);
        assertThat(queueWaiting(ingest)).isFalse();
    }

    @Test
    void postIngestHoldIsSkippedWhenNotEnforced() {
        var charges = charges(credits(false), "http://127.0.0.1:1/unused");
        String ingest = "ingest-" + UUID.randomUUID();
        charges.enqueueAfterPostIngest(ingest);
        assertThat(queueWaiting(ingest)).isFalse();
    }

    @Test
    void convertReservationMustBeSettledBeforeAutoIngestAdmit() {
        // 운영 기본값과 같게 convert 상한 500,000(default-estimate)으로 맞춘다.
        CreditService credits = new CreditService(jdbc, manager, environment, true, 500_000, 48);
        var writer = new fruition.core.document.repository.AiCommandOutboxWriter(outboxRepository, mapper, jdbc,
                entityManager, credits);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        grant(550_000);
        String convert = "convert:" + UUID.randomUUID();
        transaction.executeWithoutResult(tx -> writer.begin(convert, "ws-1", user, "convert"));  // 500,000 예약, 가용 50,000

        // 변환 예약이 남은 채로는 ingest(최저 100,000)가 거절된다.
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx ->
                writer.begin("run-" + UUID.randomUUID(), "ws-1", user, "document")))
                .isInstanceOf(CreditService.InsufficientCreditException.class);

        // 변환을 닫으면서 바로 정산하면 가용 550,000이 되어 ingest가 통과하고 그만큼 예약한다.
        transaction.executeWithoutResult(tx -> writer.completeAndSettle(convert));
        assertThat(credits.credits(user).reservedKrwMilli()).isZero();
        transaction.executeWithoutResult(tx -> writer.begin("run-" + UUID.randomUUID(), "ws-1", user, "document"));
        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(550_000);
        assertLedgerMatchesAccount();
    }

    @Test
    void ingestSettlementWaitsForPostIngestAndPostIngestUsageIsCharged() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"calls\":[]}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            CreditService credits = credits(true);
            var runs = new AiTaskCancellationService(jdbc, manager, pipeline, access, mapper, queryRuns, events, storage,
                    storageProperties, credits);
            var charges = new UsageChargeService(jdbc, manager, runs, credits, mapper,
                    new PipelineClientFactory("internal-test"),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/calls", 900);
            grant(2_000_000);
            String ingest = "ingest-" + UUID.randomUUID();
            String post = "post-" + UUID.randomUUID();
            jdbc.update("INSERT INTO ai_task_runs (id, workspace_id, user_id, kind, status) VALUES (?, 'ws-1', ?, 'document', 'completed')",
                    ingest, user);
            credits.reserve(ingest, user, "document");
            charge(ingest, 100_000);

            // ingest 결과가 오면 정산을 미룬다. 예약은 post_ingest가 끝날 때까지 남는다.
            charges.enqueueAfterPostIngest(ingest);
            assertThat(queueWaiting(ingest)).isTrue();

            // 다른 ingest를 가리키거나 ingest_run_id가 없는 이벤트, 다른 사용자의 이벤트는 앞당기지 않는다.
            charges.releasePostIngestHold("post-x-" + UUID.randomUUID(), "other-" + UUID.randomUUID(), "ws-1", user);
            charges.releasePostIngestHold("post-y-" + UUID.randomUUID(), null, "ws-1", user);
            charges.releasePostIngestHold("post-z-" + UUID.randomUUID(), ingest, "ws-1", "intruder");
            assertThat(queueWaiting(ingest)).isTrue();
            assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(1_000_000);  // document 상한

            // 해당 post_ingest가 끝났다는 이벤트가 오면 앞당겨 정산하고, post_ingest의 사용량도 차감한다.
            charge(post, 40_000);
            charges.releasePostIngestHold(post, ingest, "ws-1", user);
            assertThat(queueWaiting(ingest)).isFalse();
            charges.collectRun(ingest);
            charges.collectRun(post);

            var result = credits.credits(user);
            assertThat(result.reservedKrwMilli()).isZero();
            assertThat(result.balanceKrwMilli()).isEqualTo(2_000_000 - 100_000 - 40_000);
            assertLedgerMatchesAccount();
        } finally {
            server.stop(0);
        }
    }

    private boolean queueWaiting(String runId) {
        return jdbc.queryForList("SELECT run_id FROM usage_collect_queue WHERE run_id = ? AND available_at > now()",
                String.class, runId).size() == 1;
    }

    private void run(String runId, String status) {
        jdbc.update("INSERT INTO ai_task_runs (id, workspace_id, user_id, kind, status) VALUES (?, 'ws-1', ?, 'test_kind', ?)",
                runId, user, status);
    }

    private UsageChargeService charges(CreditService credits, String callsEndpoint) {
        var runs = new AiTaskCancellationService(jdbc, manager, pipeline, access, mapper, queryRuns, events, storage,
                storageProperties, credits);
        return new UsageChargeService(jdbc, manager, runs, credits, mapper, new PipelineClientFactory("internal-test"),
                callsEndpoint, 900);
    }

    private CreditService credits(boolean enforce) {
        return new CreditService(jdbc, manager, environment, enforce, ESTIMATE, 48);
    }

    private SpeechTranscriptionClient speech(CreditService credits, String endpoint) {
        var runs = new AiTaskCancellationService(jdbc, manager, pipeline, access, mapper, queryRuns, events, storage,
                storageProperties, credits);
        var charges = new UsageChargeService(jdbc, manager, runs, credits, mapper, new PipelineClientFactory("internal-test"),
                "http://127.0.0.1:1/unused", 900);
        return new SpeechTranscriptionClient(new PipelineClientFactory("internal-test"), endpoint, 5, charges);
    }

    @Test
    void ledgerRejectsWrongSignByNamedConstraint() {
        grant(1_000);
        for (String type : List.of("charge", "release", "refund")) {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO credit_entries (user_id, type, amount, idempotency_key) "
                    + "VALUES (?, ?, 1, ?)", user, type, type + ":" + UUID.randomUUID()))
                    .hasMessageContaining("credit_entries_amount_sign");
        }
        assertThatThrownBy(() -> jdbc.update("INSERT INTO credit_entries (user_id, type, amount, idempotency_key) "
                + "VALUES (?, 'purchase', -1, ?)", user, "purchase:" + UUID.randomUUID()))
                .hasMessageContaining("credit_entries_amount_sign");
        jdbc.update("INSERT INTO credit_entries (user_id, type, amount, idempotency_key) VALUES (?, 'refund', -1, ?)",
                user, "refund:" + UUID.randomUUID());
    }

    /** 운영 지급 SQL과 같은 방식으로 원장과 계정을 함께 쓴다. */
    private void grant(long amount) {
        jdbc.update("INSERT INTO credit_accounts (user_id) VALUES (?) ON CONFLICT DO NOTHING", user);
        jdbc.update("INSERT INTO credit_entries (user_id, type, amount, idempotency_key, reason) VALUES (?, 'grant', ?, ?, '테스트')",
                user, amount, "grant:" + UUID.randomUUID());
        jdbc.update("UPDATE credit_accounts SET balance = balance + ? WHERE user_id = ?", amount, user);
    }

    private void charge(String runId, long krwMilli) {
        jdbc.update("INSERT INTO usage_charges (call_id, user_id, run_id, call_status, status, charge_krw_milli, "
                + "cost_usd_micro, started_at) VALUES (?, ?, ?, 'succeeded', 'charged', ?, 0, now())",
                "c-" + UUID.randomUUID(), user, runId, krwMilli);
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
