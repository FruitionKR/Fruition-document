package fruition.core.document.service;

import com.zaxxer.hikari.HikariDataSource;
import fruition.TestcontainersConfiguration;
import fruition.core.document.dto.FolderCreateRequest;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 멱등 선점(REQUIRES_NEW)이 바깥 트랜잭션의 연결을 쥔 채 연결을 하나 더 잡으면, 서로 다른 사용자가 풀 크기만큼
 * 동시에 요청할 때 모두가 두 번째 연결을 기다리며 connectionTimeout(30초)까지 멈춘다. 풀의 두 배를 동시에 보내
 * 그보다 한참 짧은 시간 안에 모두 끝나는지 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotencyPoolIntegrationTest {

    private static final long DONE_WITHIN_SECONDS = 20;

    @Autowired DocumentService documentService;
    @Autowired FolderService folderService;
    @Autowired StringRedisTemplate redis;
    @Autowired DataSource dataSource;

    @Test
    void concurrentMarkdownCreatesByDifferentUsersDoNotExhaustPool() throws Exception {
        String workspaceId = "ws_idem_pool_" + UUID.randomUUID();
        runConcurrently(workspaceId, index -> {
            String userId = member(workspaceId, index);
            return () -> documentService.createMarkdown(workspaceId, userId, "key-" + index,
                    new MarkdownDocumentCreateRequest("노트 " + index, "# " + index, null)).id();
        });
    }

    @Test
    void concurrentFolderCreatesByDifferentUsersDoNotExhaustPool() throws Exception {
        String workspaceId = "ws_idem_pool_" + UUID.randomUUID();
        runConcurrently(workspaceId, index -> {
            String userId = member(workspaceId, index);
            return () -> folderService.create(workspaceId, userId, "key-" + index,
                    new FolderCreateRequest("폴더 " + index, null)).id();
        });
    }

    private String member(String workspaceId, int index) {
        String userId = "user_idem_pool_" + index + "_" + UUID.randomUUID();
        redis.opsForValue().set("authz:role:v2:" + workspaceId + ":" + userId,
                "{\"role\":\"OWNER\",\"permissions\":[]}");
        return userId;
    }

    private void runConcurrently(String workspaceId, IntFunction<Callable<Object>> request) throws Exception {
        int requests = ((HikariDataSource) dataSource).getMaximumPoolSize() * 2;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int index = 0; index < requests; index++) {
                Callable<Object> call = request.apply(index);
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            long begin = System.nanoTime();
            start.countDown();
            for (Future<Object> future : futures) {
                // 교착하면 connectionTimeout(30초)까지 연결을 못 얻어 여기서 시간이 초과된다.
                assertThat(future.get(DONE_WITHIN_SECONDS, TimeUnit.SECONDS)).isNotNull();
            }
            System.out.printf("[idempotency-pool] workspaceId=%s requests=%d elapsedMs=%d%n",
                    workspaceId, requests, (System.nanoTime() - begin) / 1_000_000);
        } finally {
            executor.shutdownNow();
        }
    }
}
