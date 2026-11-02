package fruition.core.usage.limit;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.CoreExceptionHandler;
import fruition.core.aitask.repository.PipelineTaskCancellationClient;
import fruition.core.aitask.service.AiTaskCancellationService;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.repository.AiCommandOutboxRepository;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.query.service.QueryEventBroker;
import fruition.core.query.service.QueryRunStore;
import fruition.core.usage.service.CreditService;
import fruition.core.wiki.repository.WikiWorkspaceVersionRepository;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 사용 한도를 두 관문(outbox begin·reserve, 동기 register)에서 실제 Postgres로 확인한다.
 * 사용자 ID를 테스트마다 새로 만들어 다른 테스트의 실행·청구와 섞이지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AiUsageLimitIntegrationTest {

    /** CreditService 기본 예상 상한(milli-KRW). 설정이 없는 kind(test_kind)에 쓰인다. */
    private static final long ESTIMATE_KRW_MILLI = 300_000;
    private static final int STALE_MINUTES = 60;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    @Autowired AiCommandOutboxRepository outboxRepository;
    @Autowired EntityManager entityManager;
    @Autowired PipelineTaskCancellationClient pipeline;
    @Autowired WorkspaceAccessGuard access;
    @Autowired QueryRunStore queryRuns;
    @Autowired QueryEventBroker events;
    @Autowired MinioClient storage;
    @Autowired StorageProperties storageProperties;

    private final String user = "user-" + UUID.randomUUID();

    @Test
    void concurrencyLimitRejectsFourthRunWith429AndRollsBackItsRegistration() throws Exception {
        var limiter = limiter(true, new BigDecimal("1000"), 1_000_000_000L, 3);
        var writer = writer(limiter);
        for (int i = 0; i < 3; i++) begin(writer, "run-" + UUID.randomUUID());

        String rejected = "run-" + UUID.randomUUID();
        assertThatThrownBy(() -> begin(writer, rejected))
                .isInstanceOfSatisfying(AiUsageLimitExceededException.class, e -> {
                    assertThat(e.getType()).isEqualTo(AiUsageLimitType.CONCURRENT);
                    assertThat(e.getResetAt()).isNull();
                });
        assertThat(runCount(rejected)).isZero();

        // HTTP 응답은 공통 에러 형식의 429다.
        var mvc = MockMvcBuilders.standaloneSetup(new StartController(writer, new TransactionTemplate(manager), user))
                .setControllerAdvice(new CoreExceptionHandler()).build();
        mvc.perform(post("/start"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("AI_USAGE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.error.limit_type").value("CONCURRENT"))
                .andExpect(jsonPath("$.error.message").value(AiUsageLimitType.CONCURRENT.message(3)))
                .andExpect(jsonPath("$.error.reset_at").doesNotExist())
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    void terminalAndStaleRunsDoNotCountTowardConcurrency() {
        var limiter = limiter(true, new BigDecimal("1000"), 1_000_000_000L, 3);
        var writer = writer(limiter);
        List<String> runs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String runId = "run-" + UUID.randomUUID();
            begin(writer, runId);
            runs.add(runId);
        }
        jdbc.update("UPDATE ai_task_runs SET status = 'completed' WHERE id = ?", runs.get(0));
        jdbc.update("UPDATE ai_task_runs SET status = 'failed' WHERE id = ?", runs.get(1));
        begin(writer, "run-" + UUID.randomUUID());
        jdbc.update("UPDATE ai_task_runs SET status = 'cancelled' WHERE id = ?", runs.get(2));
        begin(writer, "run-" + UUID.randomUUID());
        begin(writer, "run-" + UUID.randomUUID());

        // 종료 신호 없이 멈춘 실행(프로세스 종료 등)은 stale 시간이 지나면 자리를 막지 않는다.
        assertThatThrownBy(() -> begin(writer, "run-" + UUID.randomUUID()))
                .isInstanceOf(AiUsageLimitExceededException.class);
        jdbc.update("UPDATE ai_task_runs SET updated_at = now() - make_interval(mins => ?) WHERE user_id = ? "
                + "AND status = 'running'", STALE_MINUTES + 1, user);
        begin(writer, "run-" + UUID.randomUUID());
    }

    @Test
    void simultaneousRequestsAtTheBoundaryLetExactlyOnePass() throws Exception {
        var limiter = limiter(true, new BigDecimal("1000"), 1_000_000_000L, 3);
        var writer = writer(limiter);
        begin(writer, "run-" + UUID.randomUUID());
        begin(writer, "run-" + UUID.randomUUID());

        int threads = 10;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String runId = "run-" + UUID.randomUUID();
            results.add(pool.submit(() -> {
                start.await();
                try {
                    begin(writer, runId);
                    return true;
                } catch (AiUsageLimitExceededException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int passed = 0;
        for (Future<Boolean> result : results) if (result.get()) passed++;
        pool.shutdown();

        assertThat(passed).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE user_id = ? AND status = 'running'",
                Integer.class, user)).isEqualTo(3);
    }

    @Test
    void dailyCostCountsTodaysChargesAndInFlightEstimates() {
        var probe = limiter(true, new BigDecimal("1000"), 1_000_000_000L, 10);
        long estimate = probe.estimateUsdMicro("test_kind");
        assertThat(estimate).isPositive();
        Instant now = Instant.now();
        charge(null, 1_000_000, 0, AiUsageLimitPolicy.dayStart(now));
        // 어제(KST) 청구는 오늘 한도에 들어가지 않는다.
        charge(null, 50_000_000, 0, AiUsageLimitPolicy.dayStart(now).minusSeconds(1));

        // 오늘 1.0 USD + 진행 중 1건 예상 + 이번 예상 = 한도 정확히 → 통과
        long cap = 1_000_000 + 2 * estimate;
        var limiter = limiter(true, usd(cap), 1_000_000_000L, 10);
        var writer = writer(limiter);
        begin(writer, "run-" + UUID.randomUUID());
        begin(writer, "run-" + UUID.randomUUID());

        // 진행 중 2건 예상이 더해져 다음 실행은 한도를 넘는다.
        assertThatThrownBy(() -> begin(writer, "run-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(AiUsageLimitExceededException.class, e -> {
                    assertThat(e.getType()).isEqualTo(AiUsageLimitType.DAILY_COST);
                    assertThat(e.getResetAt()).isEqualTo(AiUsageLimitPolicy.nextReset(Instant.now()));
                });
    }

    @Test
    void inFlightRunAlreadyChargedIsNotCountedTwice() {
        var probe = limiter(true, new BigDecimal("1000"), 1_000_000_000L, 10);
        long estimate = probe.estimateUsdMicro("test_kind");
        long cap = 2 * estimate;
        var writer = writer(limiter(true, usd(cap), 1_000_000_000L, 10));
        String running = "run-" + UUID.randomUUID();
        begin(writer, running);
        // 진행 중 실행이 이미 예상만큼 청구됐으면 남은 예상은 0이고, 오늘 청구로만 센다.
        charge(running, estimate, 0, Instant.now());

        begin(writer, "run-" + UUID.randomUUID());
    }

    @Test
    void dailyTokenCapRejectsOnceTodaysTokensReachIt() {
        charge(null, 0, 2_000_000, Instant.now());
        var writer = writer(limiter(true, new BigDecimal("1000"), 2_000_000L, 10));

        assertThatThrownBy(() -> begin(writer, "run-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(AiUsageLimitExceededException.class,
                        e -> assertThat(e.getType()).isEqualTo(AiUsageLimitType.DAILY_TOKENS));
    }

    @Test
    void disabledSwitchPassesEvenWhenEveryLimitIsExceeded() {
        charge(null, 100_000_000, 100_000_000, Instant.now());
        var writer = writer(limiter(false, new BigDecimal("0.01"), 1L, 1));
        for (int i = 0; i < 5; i++) begin(writer, "run-" + UUID.randomUUID());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE user_id = ?", Integer.class, user))
                .isEqualTo(5);
    }

    @Test
    void sameRunRegisteredTwiceAndFreeRestoreKindsAreNotLimited() {
        var writer = writer(limiter(true, new BigDecimal("1000"), 1_000_000_000L, 1));
        String runId = "run-" + UUID.randomUUID();
        // begin 뒤 enqueue가 같은 run으로 begin을 다시 부른다. 이미 등록된 실행은 다시 판정하지 않는다.
        begin(writer, runId);
        begin(writer, runId);
        // 모델을 부르지 않는 복원은 한도와 무관하다.
        new TransactionTemplate(manager).executeWithoutResult(tx ->
                writer.begin("run-" + UUID.randomUUID(), "ws-1", user, "restore_ingest"));
    }

    @Test
    void synchronousRegisterPathIsLimitedAndRollsBack() {
        var limiter = limiter(true, new BigDecimal("1000"), 1_000_000_000L, 1);
        var runs = new AiTaskCancellationService(jdbc, manager, pipeline, access, mapper, queryRuns, events, storage,
                storageProperties, credits(), new WikiWorkspaceVersionRepository(jdbc), limiter);
        String first = "speech_synthesis:" + UUID.randomUUID();
        runs.register(command(first));
        // 같은 run_id 재요청은 이미 등록된 실행이라 다시 판정하지 않는다.
        runs.register(command(first));

        String second = "speech_synthesis:" + UUID.randomUUID();
        assertThatThrownBy(() -> runs.register(command(second)))
                .isInstanceOfSatisfying(AiUsageLimitExceededException.class,
                        e -> assertThat(e.getType()).isEqualTo(AiUsageLimitType.CONCURRENT));
        assertThat(runCount(second)).isZero();
    }

    private com.fasterxml.jackson.databind.JsonNode command(String runId) {
        return mapper.createObjectNode().put("run_id", runId).put("kind", "test_kind")
                .put("workspace_id", "ws-1").put("user_id", user);
    }

    private static BigDecimal usd(long micro) {
        return BigDecimal.valueOf(micro).movePointLeft(6);
    }

    private AiUsageLimiter limiter(boolean enabled, BigDecimal dailyCostUsd, long dailyTokens, int concurrent) {
        return new AiUsageLimiter(jdbc, credits(), enabled, dailyCostUsd, dailyTokens, concurrent, STALE_MINUTES);
    }

    private CreditService credits() {
        return new CreditService(jdbc, manager, environment, false, ESTIMATE_KRW_MILLI, 48);
    }

    private AiCommandOutboxWriter writer(AiUsageLimiter limiter) {
        return new AiCommandOutboxWriter(outboxRepository, mapper, jdbc, entityManager, credits(), limiter);
    }

    private void begin(AiCommandOutboxWriter writer, String runId) {
        new TransactionTemplate(manager).executeWithoutResult(tx -> writer.begin(runId, "ws-1", user, "test_kind"));
    }

    private int runCount(String runId) {
        return jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE id = ?", Integer.class, runId);
    }

    private void charge(String runId, long costUsdMicro, long tokens, Instant startedAt) {
        jdbc.update("INSERT INTO usage_charges (call_id, user_id, run_id, call_status, status, charge_krw_milli, "
                + "cost_usd_micro, input_tokens, output_tokens, started_at) "
                + "VALUES (?, ?, ?, 'succeeded', 'charged', 0, ?, ?, 0, ?)",
                "c-" + UUID.randomUUID(), user, runId, costUsdMicro, tokens, Timestamp.from(startedAt));
    }

    @RestController
    static class StartController {
        private final AiCommandOutboxWriter writer;
        private final TransactionTemplate transaction;
        private final String user;

        StartController(AiCommandOutboxWriter writer, TransactionTemplate transaction, String user) {
            this.writer = writer;
            this.transaction = transaction;
            this.user = user;
        }

        @PostMapping("/start")
        void start() {
            transaction.executeWithoutResult(tx -> writer.begin("run-" + UUID.randomUUID(), "ws-1", user, "test_kind"));
        }
    }
}
