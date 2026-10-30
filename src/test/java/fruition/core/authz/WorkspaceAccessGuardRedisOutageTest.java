package fruition.core.authz;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis는 권한 캐시일 뿐 원본은 access 내부 API다. 운영과 같은 명령 timeout(2초)으로 Redis를 실제로 일시정지·정지하고
 * 권한 확인의 성공 비율과 응답 시간을 잰다.
 */
class WorkspaceAccessGuardRedisOutageTest {

    // 회귀 확인은 3회면 충분하다. 응답 없음 단계가 회마다 timeout(2초)을 기다리므로 측정할 때만 늘린다.
    private static final int REQUESTS =
            Integer.parseInt(System.getenv().getOrDefault("REDIS_OUTAGE_BENCH_REQUESTS", "3"));

    @Test
    void redisOutageFallsBackToAccessInsteadOfFailingEveryRequest() throws Exception {
        try (MockWebServer access = new MockWebServer();
             GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                     .withExposedPorts(6379)) {
            access.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse().setHeader("Content-Type", "application/json")
                            .setBody("{\"role\":\"OWNER\",\"permissions\":[]}");
                }
            });
            access.start();
            redisContainer.start();

            var factory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(redisContainer.getHost(), redisContainer.getMappedPort(6379)),
                    LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
            factory.afterPropertiesSet();
            factory.start();
            var guard = new WorkspaceAccessGuard(new StringRedisTemplate(factory),
                    RestClient.builder().baseUrl(access.url("/").toString()).build());

            Result healthy = run(guard, "healthy");
            // 일시정지: 연결은 살아 있고 응답만 없다. 명령마다 timeout(2초)까지 기다린다.
            var docker = redisContainer.getDockerClient();
            docker.pauseContainerCmd(redisContainer.getContainerId()).exec();
            Result hung = run(guard, "hung");
            docker.unpauseContainerCmd(redisContainer.getContainerId()).exec();
            // 정지: 연결이 끊긴다. 클라이언트가 끊김을 알아챈 뒤면 바로 거절, 아니면 timeout까지 기다린다.
            redisContainer.stop();
            Result down = run(guard, "down");
            factory.destroy();

            System.out.printf("[redis-outage-bench] requests=%d healthy={%s} hung={%s} down={%s}%n",
                    REQUESTS, healthy, hung, down);

            assertThat(hung.failures()).isZero();
            assertThat(down.failures()).isZero();
        }
    }

    private static Result run(WorkspaceAccessGuard guard, String phase) {
        List<Long> latencies = new ArrayList<>();
        int failures = 0;
        for (int i = 0; i < REQUESTS; i++) {
            long start = System.nanoTime();
            try {
                guard.requireMember("outage-ws", "outage-user-" + phase + "-" + (i % 5));
            } catch (RuntimeException e) {
                failures++;
            }
            latencies.add((System.nanoTime() - start) / 1_000_000);
        }
        List<Long> sorted = latencies.stream().sorted().toList();
        return new Result(failures, sorted.stream().mapToLong(Long::longValue).sum() / REQUESTS,
                sorted.get((int) Math.ceil(REQUESTS * 0.95) - 1), sorted.getLast());
    }

    record Result(int failures, long avgMs, long p95Ms, long maxMs) {
        @Override
        public String toString() {
            return "failures=" + failures + " avgMs=" + avgMs + " p95Ms=" + p95Ms + " maxMs=" + maxMs;
        }
    }
}
