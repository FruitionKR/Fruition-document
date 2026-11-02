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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 하루 토큰 한도를 두 관문(outbox begin·reserve, 동기 register)에서 실제 Postgres로 확인한다.
 * 사용자 ID를 테스트마다 새로 만들어 다른 테스트의 실행·청구와 섞이지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AiUsageLimitIntegrationTest {

    /** CreditService 기본 예상 상한(milli-KRW). 설정이 없는 kind(test_kind)에 쓰인다. */
    private static final long ESTIMATE_KRW_MILLI = 300_000;
    private static final long DAILY_TOKENS = 2_000_000;

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
    @Autowired AiUsageLimiter configured;

    private final String user = "user-" + UUID.randomUUID();

    @Test
    void defaultDailyTokenLimitIsTenMillion() {
        // application-test.properties는 enabled만 끄고 토큰 한도는 application.properties 기본값을 쓴다.
        assertThat(configured.dailyTokens()).isEqualTo(10_000_000L);
    }

    @Test
    void belowDailyTokenLimitPassesAndManyRunsMayStartAtOnce() {
        charge(null, DAILY_TOKENS - 1, Instant.now());
        var writer = writer(limiter(true));
        // 동시 실행·하루 비용 한도는 없앴다. 토큰이 한도 아래면 몇 건이든 시작한다.
        for (int i = 0; i < 5; i++) begin(writer, "run-" + UUID.randomUUID());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE user_id = ? AND status = 'running'",
                Integer.class, user)).isEqualTo(5);
    }

    @Test
    void dailyTokenCapRejectsWith429OnceTodaysTokensReachItAndRollsBackRegistration() throws Exception {
        Instant now = Instant.now();
        charge(null, DAILY_TOKENS / 2, AiUsageLimitPolicy.dayStart(now));
        charge(null, DAILY_TOKENS / 2, now);
        var writer = writer(limiter(true));

        String rejected = "run-" + UUID.randomUUID();
        assertThatThrownBy(() -> begin(writer, rejected))
                .isInstanceOfSatisfying(AiUsageLimitExceededException.class, e -> {
                    assertThat(e.getType()).isEqualTo(AiUsageLimitType.DAILY_TOKENS);
                    assertThat(e.getResetAt()).isEqualTo(AiUsageLimitPolicy.nextReset(Instant.now()));
                });
        assertThat(runCount(rejected)).isZero();

        // HTTP 응답은 공통 에러 형식의 429다.
        var mvc = MockMvcBuilders.standaloneSetup(new StartController(writer, new TransactionTemplate(manager), user))
                .setControllerAdvice(new CoreExceptionHandler()).build();
        mvc.perform(post("/start"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("AI_USAGE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.error.limit_type").value("DAILY_TOKENS"))
                .andExpect(jsonPath("$.error.message").value(AiUsageLimitType.DAILY_TOKENS.message()))
                .andExpect(jsonPath("$.error.reset_at").exists())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void yesterdaysTokensDoNotCountTowardToday() {
        charge(null, DAILY_TOKENS * 10, AiUsageLimitPolicy.dayStart(Instant.now()).minusSeconds(1));
        begin(writer(limiter(true)), "run-" + UUID.randomUUID());
    }

    @Test
    void disabledSwitchPassesEvenWhenTokenLimitIsExceeded() {
        charge(null, DAILY_TOKENS * 10, Instant.now());
        var writer = writer(limiter(false));
        for (int i = 0; i < 5; i++) begin(writer, "run-" + UUID.randomUUID());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE user_id = ?", Integer.class, user))
                .isEqualTo(5);
    }

    @Test
    void sameRunRegisteredTwiceAndFreeRestoreKindsAreNotLimited() {
        var writer = writer(limiter(true));
        String runId = "run-" + UUID.randomUUID();
        begin(writer, runId);
        charge(null, DAILY_TOKENS, Instant.now());
        // begin 뒤 enqueue가 같은 run으로 begin을 다시 부른다. 이미 등록된 실행은 다시 판정하지 않는다.
        begin(writer, runId);
        // 모델을 부르지 않는 복원은 한도와 무관하다.
        new TransactionTemplate(manager).executeWithoutResult(tx ->
                writer.begin("run-" + UUID.randomUUID(), "ws-1", user, "restore_ingest"));
    }

    @Test
    void synchronousRegisterPathIsLimitedAndRollsBack() {
        var runs = new AiTaskCancellationService(jdbc, manager, pipeline, access, mapper, queryRuns, events, storage,
                storageProperties, credits(), new WikiWorkspaceVersionRepository(jdbc), limiter(true));
        String first = "speech_synthesis:" + UUID.randomUUID();
        runs.register(command(first));
        charge(null, DAILY_TOKENS, Instant.now());
        // 같은 run_id 재요청은 이미 등록된 실행이라 다시 판정하지 않는다.
        runs.register(command(first));

        String second = "speech_synthesis:" + UUID.randomUUID();
        assertThatThrownBy(() -> runs.register(command(second)))
                .isInstanceOfSatisfying(AiUsageLimitExceededException.class,
                        e -> assertThat(e.getType()).isEqualTo(AiUsageLimitType.DAILY_TOKENS));
        assertThat(runCount(second)).isZero();
    }

    private com.fasterxml.jackson.databind.JsonNode command(String runId) {
        return mapper.createObjectNode().put("run_id", runId).put("kind", "test_kind")
                .put("workspace_id", "ws-1").put("user_id", user);
    }

    private AiUsageLimiter limiter(boolean enabled) {
        return new AiUsageLimiter(jdbc, enabled, DAILY_TOKENS);
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

    private void charge(String runId, long tokens, Instant startedAt) {
        jdbc.update("INSERT INTO usage_charges (call_id, user_id, run_id, call_status, status, charge_krw_milli, "
                + "cost_usd_micro, input_tokens, output_tokens, started_at) "
                + "VALUES (?, ?, ?, 'succeeded', 'charged', 0, ?, ?, 0, ?)",
                "c-" + UUID.randomUUID(), user, runId, 0, tokens, Timestamp.from(startedAt));
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
