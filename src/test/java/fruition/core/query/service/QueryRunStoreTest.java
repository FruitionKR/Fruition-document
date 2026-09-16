package fruition.core.query.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.query.domain.QueryRun;
import fruition.core.query.domain.QueryRunStatus;
import fruition.core.query.dto.QueryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryRunStoreTest {

    private static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    // Redis를 in-memory Map으로 흉내 내 JSON 직렬화·상태 전이 동작을 검증한다. 만료는 TTL 기록으로 확인한다.
    private final Map<String, String> redisData = new LinkedHashMap<>();
    private final Map<String, Duration> redisTtls = new HashMap<>();

    private final MutableClock clock = new MutableClock(Instant.parse("2026-06-20T10:00:00Z"));
    // Boot 기본 mapper처럼 unknown property를 무시해야 파생 getter(finished)가 든 JSON을 되읽을 수 있다.
    private final QueryRunStore store = new QueryRunStore(
            clock, fakeRedisTemplate(), new ObjectMapper().findAndRegisterModules()
                    .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));

    @SuppressWarnings("unchecked")
    private StringRedisTemplate fakeRedisTemplate() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doAnswer(invocation -> {
            redisData.put(invocation.getArgument(0), invocation.getArgument(1));
            redisTtls.put(invocation.getArgument(0), invocation.getArgument(2));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        when(valueOperations.get(anyString()))
                .thenAnswer(invocation -> redisData.get(invocation.<String>getArgument(0)));
        when(redisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                any(java.util.List.class), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String key = ((java.util.List<String>) invocation.getArgument(1)).getFirst();
            if (!java.util.Objects.equals(redisData.get(key), invocation.getArgument(2))) return 0L;
            redisData.put(key, invocation.getArgument(3));
            redisTtls.put(key, Duration.ofSeconds(Long.parseLong(invocation.getArgument(4))));
            return 1L;
        });
        return redisTemplate;
    }

    @Test
    void cancelledRunRejectsLateResults() {
        QueryRun run = store.create("ws", "session", "질문");
        assertThat(store.markCancelled(run.requestId())).isTrue();
        store.markRunning(run.requestId());
        assertThat(store.markFailed(run.requestId(), "late")).isFalse();
        assertThat(store.markCompleted(run.requestId(), null)).isFalse();
        assertThat(store.find(run.requestId()).orElseThrow().status()).isEqualTo(QueryRunStatus.CANCELLED);
    }

    @Test
    void create_thenFind_returnsPendingRunAfterJsonRoundTrip() {
        QueryRun run = store.create("ws_abc123", "session_abc123", "질문");

        assertThat(store.find(run.requestId())).contains(run);
        assertThat(run.status()).isEqualTo(QueryRunStatus.PENDING);
        assertThat(run.workspaceId()).isEqualTo("ws_abc123");
        assertThat(run.createdAt()).isEqualTo(clock.instant());
    }

    @Test
    void find_unknownRequestId_returnsEmpty() {
        assertThat(store.find("query_unknown")).isEmpty();
    }

    @Test
    void markRunning_thenMarkCompleted_updatesStatusAndResult() {
        QueryRun run = store.create("ws_abc123", "session_abc123", "질문");
        QueryResponse result = new QueryResponse(null, null, null, null, null, null, false, false, 0, null);

        store.markRunning(run.requestId());
        assertThat(store.find(run.requestId()).orElseThrow().status()).isEqualTo(QueryRunStatus.RUNNING);

        assertThat(store.markCompleted(run.requestId(), result)).isTrue();
        QueryRun completed = store.find(run.requestId()).orElseThrow();
        assertThat(completed.status()).isEqualTo(QueryRunStatus.COMPLETED);
        assertThat(completed.result()).isEqualTo(result);
    }

    @Test
    void markFailed_updatesStatusAndErrorMessage() {
        QueryRun run = store.create("ws_abc123", "session_abc123", "질문");

        assertThat(store.markFailed(run.requestId(), "파이프라인 오류")).isTrue();

        QueryRun failed = store.find(run.requestId()).orElseThrow();
        assertThat(failed.status()).isEqualTo(QueryRunStatus.FAILED);
        assertThat(failed.errorMessage()).isEqualTo("파이프라인 오류");
    }

    @Test
    void terminalRunRejectsDuplicateAndContradictoryTransitions() {
        QueryRun run = store.create("ws_abc123", "session_abc123", "질문");
        QueryResponse first = new QueryResponse(null, null, null, null, null, null, false, false, 0, null);

        assertThat(store.markCompleted(run.requestId(), first)).isTrue();
        assertThat(store.markCompleted(run.requestId(), first)).isFalse();
        assertThat(store.markFailed(run.requestId(), "뒤늦은 실패")).isFalse();
        assertThat(store.find(run.requestId()).orElseThrow().status())
                .isEqualTo(QueryRunStatus.COMPLETED);
    }

    // evictExpired가 제거되고 만료는 Redis TTL이 담당하므로, active/finished TTL이 다르게 적용되는지 검증한다.
    @Test
    void write_activeRunGetsLongTtl_finishedRunGetsShortTtl() {
        QueryRun run = store.create("ws_abc123", "session_abc123", "질문");
        String key = "query:run:" + run.requestId();
        assertThat(redisTtls.get(key)).isEqualTo(Duration.ofHours(24));

        store.markCompleted(run.requestId(), new QueryResponse(null, null, null, null, null, null, false, false, 0, null));
        assertThat(redisTtls.get(key)).isEqualTo(Duration.ofMinutes(10));
    }

}
