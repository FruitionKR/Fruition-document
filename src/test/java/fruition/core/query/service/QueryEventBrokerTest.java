package fruition.core.query.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryEventBrokerTest {

    // SseEmitter only flushes through its package-private Handler once a real servlet request
    // initializes it. Before that, sends are queued in ResponseBodyEmitter's private
    // "earlySendAttempts" field, so tests read that field via reflection to verify payloads
    // without needing a running MVC container.
    @SuppressWarnings("unchecked")
    private static List<Object> capturedData(SseEmitter emitter) throws Exception {
        Field field = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
        field.setAccessible(true);
        Set<ResponseBodyEmitter.DataWithMediaType> attempts =
                (Set<ResponseBodyEmitter.DataWithMediaType>) field.get(emitter);
        List<Object> data = new ArrayList<>();
        for (ResponseBodyEmitter.DataWithMediaType attempt : attempts) {
            data.add(attempt.getData());
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> capturedPayloads(SseEmitter emitter) throws Exception {
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (Object item : capturedData(emitter)) {
            if (item instanceof Map<?, ?> map) {
                payloads.add((Map<String, Object>) map);
            }
        }
        return payloads;
    }

    private static long sequenceOf(Map<String, Object> payload) {
        return ((Number) payload.get("sequence")).longValue();
    }

    // Redis list(이벤트 보존)와 pub/sub(방송)을 in-memory로 흉내 낸다.
    // convertAndSend는 실제 Redis처럼 발행 인스턴스의 onMessage로 되돌아온다(loop-back).
    private final java.util.Set<String> cancelled = new java.util.HashSet<>();
    private final Map<String, List<String>> redisLists = new HashMap<>();
    private final Map<String, AtomicLong> redisCounters = new HashMap<>();
    private final Set<String> redisValues = new java.util.HashSet<>();
    private final List<String> broadcastMessages = new ArrayList<>();

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-20T10:00:00Z"), ZoneOffset.UTC);
    // Boot 기본 mapper처럼 unknown property를 무시해야 파생 getter(terminal)가 든 JSON을 되읽을 수 있다.
    private final QueryEventBroker broker = new QueryEventBroker(
            clock, fakeRedisTemplate(), new ObjectMapper().findAndRegisterModules()
                    .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));

    @SuppressWarnings("unchecked")
    private StringRedisTemplate fakeRedisTemplate() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            List<String> keys = invocation.getArgument(1);
            boolean cancelling = "true".equals(invocation.getArgument(5));
            if (cancelling) {
                cancelled.add(keys.get(1));
                redisLists.remove(keys.get(0));
            } else if (cancelled.contains(keys.get(1))) return 0L;
            listOperations.rightPush(keys.get(0), invocation.<String>getArgument(2));
            listOperations.trim(keys.get(0), -200, -1);
            redisTemplate.convertAndSend(invocation.<String>getArgument(3), invocation.<String>getArgument(2));
            return 1L;
        });
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(valueOperations.increment(anyString())).thenAnswer(invocation ->
                redisCounters.computeIfAbsent(invocation.getArgument(0), key -> new AtomicLong())
                        .incrementAndGet());
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> redisValues.add(invocation.getArgument(0)));
        when(listOperations.rightPush(anyString(), anyString())).thenAnswer(invocation -> {
            List<String> list = redisLists.computeIfAbsent(invocation.getArgument(0), key -> new ArrayList<>());
            list.add(invocation.getArgument(1));
            return (long) list.size();
        });
        doAnswer(invocation -> null).when(listOperations).trim(anyString(), anyLong(), anyLong());
        when(listOperations.range(anyString(), anyLong(), anyLong())).thenAnswer(invocation ->
                new ArrayList<>(redisLists.getOrDefault(invocation.<String>getArgument(0), List.of())));
        doAnswer(invocation -> {
            String json = invocation.getArgument(1);
            broadcastMessages.add(json);
            broker.onMessage(new DefaultMessage(
                    invocation.<String>getArgument(0).getBytes(StandardCharsets.UTF_8),
                    json.getBytes(StandardCharsets.UTF_8)), null);
            return null;
        }).when(redisTemplate).convertAndSend(anyString(), any());
        return redisTemplate;
    }

    @Test
    void cancellationReplacesReplayAndBlocksLateCompletion() throws Exception {
        broker.publish("run", "progress", "stage", "진행", Map.of());
        broker.cancel("run");
        broker.complete("run");
        broker.fail("run", "늦은 오류");
        assertThat(redisLists.get("query:events:run")).hasSize(1);
        assertThat(redisLists.get("query:events:run").getFirst()).contains("query.cancelled");
    }

    @Test
    void publish_afterSubscribe_deliversPayloadWithSequenceAndTimestampViaPubSub() throws Exception {
        SseEmitter emitter = broker.subscribe("query_abc123");

        broker.publish("query_abc123", "event-1", "query_started", "질의 처리를 시작했습니다.", Map.of("question", "테스트"));

        List<Map<String, Object>> payloads = capturedPayloads(emitter);
        assertThat(payloads).hasSize(1);
        Map<String, Object> payload = payloads.get(0);
        assertThat(payload.get("request_id")).isEqualTo("query_abc123");
        assertThat(sequenceOf(payload)).isEqualTo(1L);
        assertThat(payload.get("received_at")).isEqualTo("2026-06-20T10:00:00Z");
        assertThat(payload.get("stage")).isEqualTo("query_started");
        assertThat(payload.get("message")).isEqualTo("질의 처리를 시작했습니다.");
    }

    @Test
    void publish_multipleEvents_incrementsSequencePerRun() throws Exception {
        SseEmitter emitter = broker.subscribe("query_abc123");

        broker.publish("query_abc123", "event-1", "query_started", "시작", null);
        broker.publish("query_abc123", "event-2", "retrieval_scored", "점수 계산", null);

        List<Map<String, Object>> payloads = capturedPayloads(emitter);
        assertThat(payloads).hasSize(2);
        assertThat(payloads).extracting(QueryEventBrokerTest::sequenceOf).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void publish_beforeSubscribe_isStoredInRedisAndReplayedOnLateSubscribe() throws Exception {
        broker.publish("query_abc123", "event-1", "query_started", "시작", null);

        SseEmitter lateEmitter = broker.subscribe("query_abc123");

        assertThat(capturedPayloads(lateEmitter)).hasSize(1);
    }

    @Test
    void publish_storesEventJsonInRedisListAndBroadcasts() {
        broker.publish("query_abc123", "event-1", "query_started", "시작", null);

        List<String> stored = redisLists.get("query:events:query_abc123");
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0)).contains("\"query_abc123\"").contains("query.log");
        assertThat(broadcastMessages).containsExactlyElementsOf(stored);
    }

    @Test
    void deliver_duplicateEventFromReplayAndBroadcast_isSuppressedBySequence() throws Exception {
        SseEmitter emitter = broker.subscribe("query_abc123");
        broker.publish("query_abc123", "event-1", "query_started", "시작", null);

        // pub/sub 방송이 중복 도착해도 sequence가 뒤로 가지 않으면 다시 전달하지 않는다.
        String storedJson = redisLists.get("query:events:query_abc123").get(0);
        broker.onMessage(new DefaultMessage("query-events".getBytes(StandardCharsets.UTF_8),
                storedJson.getBytes(StandardCharsets.UTF_8)), null);

        assertThat(capturedPayloads(emitter)).hasSize(1);
    }

    @Test
    void publish_duplicateSourceEvent_isSuppressedBeforeStorage() throws Exception {
        SseEmitter emitter = broker.subscribe("query_abc123");

        broker.publish("query_abc123", "event-1", "query_started", "시작", null);
        broker.publish("query_abc123", "event-1", "query_started", "시작", null);

        assertThat(capturedPayloads(emitter)).hasSize(1);
        assertThat(redisLists.get("query:events:query_abc123")).hasSize(1);
    }

    @Test
    void complete_sendsCompletedEventToSubscriber() throws Exception {
        SseEmitter emitter = broker.subscribe("query_abc123");

        broker.complete("query_abc123");

        List<Map<String, Object>> payloads = capturedPayloads(emitter);
        assertThat(payloads).hasSize(1);
        assertThat(payloads.get(0).get("status")).isEqualTo("completed");
    }

    @Test
    void fail_sendsFailedEventWithErrorMessage() throws Exception {
        SseEmitter emitter = broker.subscribe("query_abc123");

        broker.fail("query_abc123", "쿼리 파이프라인을 사용할 수 없습니다.");

        List<Map<String, Object>> payloads = capturedPayloads(emitter);
        assertThat(payloads).hasSize(1);
        assertThat(payloads.get(0).get("status")).isEqualTo("failed");
        assertThat(payloads.get(0).get("error")).isEqualTo("쿼리 파이프라인을 사용할 수 없습니다.");
    }

    @Test
    void subscribe_afterComplete_immediatelyReceivesStoredFinalEvent() throws Exception {
        broker.subscribe("query_abc123");
        broker.complete("query_abc123");

        SseEmitter lateEmitter = broker.subscribe("query_abc123");

        assertThat(capturedPayloads(lateEmitter)).hasSize(1);
        assertThat(capturedPayloads(lateEmitter).get(0).get("status")).isEqualTo("completed");
    }
}
