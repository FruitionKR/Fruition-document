package fruition.core.document.service;

import com.zaxxer.hikari.HikariDataSource;
import fruition.TestcontainersConfiguration;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
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
 * 문서 상세 조회는 AI 서비스에 위키 연결 정보를 묻는다. 그 응답을 DB 연결을 쥔 채 기다리면 AI가 느릴 때 연결 풀이
 * 바닥나 AI와 무관한 API까지 멈춘다. AI 응답을 늦춰 상세 조회를 동시에 보내는 동안 문서 목록(DB만 쓰는 API)의
 * 응답 시간과 연결을 기다린 스레드 수를 잰다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DocumentDetailPoolIntegrationTest {

    private static final long AI_DELAY_MS = 3000;
    private static final int DETAIL_REQUESTS = 20;
    private static final int LIST_REQUESTS = 20;
    private static final MockWebServer AI = new MockWebServer();

    static {
        AI.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("{\"pages\":[],\"source_blocks\":[],\"source_content_hash\":null}")
                        .setHeadersDelay(AI_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        });
        try {
            AI.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void aiUrl(DynamicPropertyRegistry registry) {
        registry.add("app.wiki-state.endpoint", () -> AI.url("/wiki").toString());
    }

    @AfterAll
    static void stop() throws IOException {
        AI.shutdown();
    }

    @Autowired DocumentService documentService;
    @Autowired StringRedisTemplate redis;
    @Autowired DataSource dataSource;

    @Test
    void slowAiDoesNotStarveOtherApisOfDbConnections() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String workspaceId = "ws_pool_" + suffix;
        String userId = "user_pool_" + suffix;
        redis.opsForValue().set("authz:role:v2:" + workspaceId + ":" + userId,
                "{\"role\":\"OWNER\",\"permissions\":[]}");
        String documentId = documentService.createMarkdown(workspaceId, userId, "pool-" + suffix,
                new MarkdownDocumentCreateRequest("Pool", "# pool", null)).id();

        var pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger maxWaiting = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        Thread sampler = new Thread(() -> {
            while (running.get()) {
                maxWaiting.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
                maxActive.accumulateAndGet(pool.getActiveConnections(), Math::max);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

        ExecutorService detailPool = Executors.newFixedThreadPool(DETAIL_REQUESTS);
        List<Future<?>> details = new ArrayList<>();
        for (int i = 0; i < DETAIL_REQUESTS; i++) {
            details.add(detailPool.submit(() -> documentService.findById(workspaceId, userId, documentId)));
        }
        // 동시에 시작한 상세 조회의 짧은 DB 구간이 지나간 뒤, AI 응답을 기다리는 구간만 표본으로 잡는다.
        Thread.sleep(300);
        sampler.start();

        List<Long> listMs = new ArrayList<>();
        for (int i = 0; i < LIST_REQUESTS; i++) {
            long start = System.nanoTime();
            documentService.findAll(workspaceId, userId, null);
            listMs.add((System.nanoTime() - start) / 1_000_000);
        }
        for (Future<?> detail : details) detail.get(60, TimeUnit.SECONDS);
        running.set(false);
        sampler.join();
        detailPool.shutdown();

        List<Long> sorted = listMs.stream().sorted().toList();
        long p95 = sorted.get((int) Math.ceil(LIST_REQUESTS * 0.95) - 1);
        System.out.printf("[detail-pool-bench] aiDelayMs=%d details=%d poolMax=%d listFirstMs=%d listP95Ms=%d "
                        + "listMaxMs=%d maxActive=%d maxWaiting=%d%n",
                AI_DELAY_MS, DETAIL_REQUESTS, pool.getTotalConnections(), listMs.getFirst(), p95, sorted.getLast(),
                maxActive.get(), maxWaiting.get());

        // 상세 조회가 AI를 기다리는 동안에도 목록 조회는 연결을 바로 얻는다.
        assertThat(sorted.getLast()).isLessThan(AI_DELAY_MS / 3);
    }
}
