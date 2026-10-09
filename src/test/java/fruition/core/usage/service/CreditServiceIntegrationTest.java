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

        // 가용 1,000,000에 300,000씩이면 3건만 예약된다.
        assertThat(reserved).isEqualTo(3);
        assertThat(credits.credits(user).reservedKrwMilli()).isEqualTo(900_000);
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

    private CreditService credits(boolean enforce) {
        return new CreditService(jdbc, manager, environment, enforce, ESTIMATE, 48);
    }

    private SpeechTranscriptionClient speech(CreditService credits, String endpoint) {
        var runs = new AiTaskCancellationService(jdbc, manager, pipeline, access, mapper, queryRuns, events, storage,
                storageProperties, credits);
        var charges = new UsageChargeService(jdbc, manager, runs, credits, mapper, new PipelineClientFactory("internal-test"),
                "http://127.0.0.1:1/unused");
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
