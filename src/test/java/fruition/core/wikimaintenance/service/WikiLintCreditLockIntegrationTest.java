package fruition.core.wikimaintenance.service;

import fruition.TestcontainersConfiguration;
import fruition.core.wikimaintenance.dto.WikiLintRequest;
import fruition.shared.ai.AiModelCatalog;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 크레딧 예약은 사용자 계정 행을 FOR UPDATE로 잠근다. 그 잠금을 쥔 채 access-svc에 모델 설정을 물으면
 * access가 느릴 때 같은 사용자의 AI 요청이 한 줄로 선다. access 응답을 늦춰 같은 사용자의 lint를 동시에 보내고
 * 총 소요 시간과 잠금 대기 세션 수를 잰다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WikiLintCreditLockIntegrationTest {

    private static final long ACCESS_DELAY_MS = 1000;
    private static final int CONCURRENT_REQUESTS = 5;
    private static final long SAMPLE_INTERVAL_MS = 20;
    private static final MockWebServer ACCESS = new MockWebServer();

    static {
        ACCESS.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"ingest_lint\":{\"provider\":\"" + AiModelCatalog.WORKSPACE_DEFAULT_PROVIDER
                                + "\",\"model\":\"" + AiModelCatalog.WORKSPACE_DEFAULT_MODEL + "\"}}")
                        .setHeadersDelay(ACCESS_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        });
        try {
            ACCESS.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void accessUrl(DynamicPropertyRegistry registry) {
        registry.add("app.internal.access-base-url", () -> ACCESS.url("/").toString());
    }

    @AfterAll
    static void stop() throws IOException {
        ACCESS.shutdown();
    }

    @Autowired WikiMaintenanceService service;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sameUserLintsDoNotQueueBehindCreditLockWhileAccessIsSlow() throws Exception {
        String userId = "lock-user-" + UUID.randomUUID();
        List<String> workspaces = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            String workspaceId = "lock-ws-" + UUID.randomUUID();
            workspaces.add(workspaceId);
            // 권한 판정은 캐시로 끝내 access 호출을 모델 설정 조회 하나로 좁힌다.
            redis.opsForValue().set("authz:role:v2:" + workspaceId + ":" + userId,
                    "{\"role\":\"OWNER\",\"permissions\":[]}");
        }

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger maxLockWaiters = new AtomicInteger();
        // 잠금 대기가 보인 표본 수. 표본 간격을 곱하면 누군가 잠금을 기다린 시간의 근사값이다.
        AtomicInteger lockWaitSamples = new AtomicInteger();
        Thread sampler = new Thread(() -> {
            while (running.get()) {
                Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                        + "WHERE wait_event_type = 'Lock' AND datname = current_database()", Integer.class);
                int count = waiting == null ? 0 : waiting;
                maxLockWaiters.accumulateAndGet(count, Math::max);
                if (count > 0) lockWaitSamples.incrementAndGet();
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.start();

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        long start = System.nanoTime();
        List<Future<Long>> futures = new ArrayList<>();
        for (String workspaceId : workspaces) {
            futures.add(pool.submit(() -> {
                long requestStart = System.nanoTime();
                service.lint(workspaceId, userId, new WikiLintRequest(null, null));
                return (System.nanoTime() - requestStart) / 1_000_000;
            }));
        }
        List<Long> perRequestMs = new ArrayList<>();
        for (Future<Long> future : futures) perRequestMs.add(future.get(60, TimeUnit.SECONDS));
        long totalMs = (System.nanoTime() - start) / 1_000_000;
        running.set(false);
        sampler.join();
        pool.shutdown();

        long lockWaitObservedMs = lockWaitSamples.get() * SAMPLE_INTERVAL_MS;
        System.out.printf("[credit-lock-bench] accessDelayMs=%d requests=%d totalMs=%d perRequestMs=%s "
                        + "maxLockWaiters=%d lockWaitObservedMs=%d%n",
                ACCESS_DELAY_MS, CONCURRENT_REQUESTS, totalMs, perRequestMs.stream().sorted().toList(),
                maxLockWaiters.get(), lockWaitObservedMs);

        // 크레딧 잠금은 예약 순간에만 겹치고, access 응답을 기다리는 동안에는 쥐지 않는다.
        assertThat(totalMs).isLessThan(ACCESS_DELAY_MS * 2);
        assertThat(lockWaitObservedMs).isLessThan(ACCESS_DELAY_MS / 2);
    }
}
