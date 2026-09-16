package fruition.core.query.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * query run 진행 이벤트 중계자.
 * 이벤트는 Redis list에 보존(재접속 replay용)하고 Redis pub/sub으로 전 인스턴스에 방송한다.
 * 콜백을 받은 인스턴스와 SSE 구독자가 붙은 인스턴스가 달라도 이벤트가 전달된다.
 * 구독자별 마지막 전달 sequence로 replay/방송 중복을 제거한다.
 */
@Component
public class QueryEventBroker implements MessageListener {

    public static final String CHANNEL = "query-events";

    private static final Logger log = LoggerFactory.getLogger(QueryEventBroker.class);
    private static final String EVENTS_KEY_PREFIX = "query:events:";
    private static final String SEQUENCE_KEY_PREFIX = "query:events-seq:";
    private static final String SOURCE_EVENT_KEY_PREFIX = "query:source-event:";
    private static final Duration EVENT_TTL = Duration.ofMinutes(30);
    private static final String EVENT_COMPLETED = "query.completed";
    private static final String EVENT_FAILED = "query.failed";
    private static final String EVENT_CANCELLED = "query.cancelled";

    private final Clock clock;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, CopyOnWriteArrayList<Subscriber>> subscribers = new ConcurrentHashMap<>();

    public QueryEventBroker(Clock clock, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.clock = clock;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public SseEmitter subscribe(String requestId) {
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = new Subscriber(emitter);
        CopyOnWriteArrayList<Subscriber> list = subscribers.computeIfAbsent(requestId, id -> new CopyOnWriteArrayList<>());
        list.add(subscriber);
        Runnable detach = () -> {
            list.remove(subscriber);
            if (list.isEmpty()) {
                subscribers.remove(requestId, list);
            }
        };
        emitter.onCompletion(detach);
        emitter.onTimeout(detach);
        emitter.onError(e -> detach.run());

        // pub/sub 수신 등록 후 저장분을 replay한다. 사이에 낀 이벤트는 중복 도착하지만 sequence로 걸러진다.
        List<String> stored = redisTemplate.opsForList().range(EVENTS_KEY_PREFIX + requestId, 0, -1);
        int replayed = 0;
        if (stored != null) {
            for (String json : stored) {
                subscriber.deliver(parse(json));
                replayed++;
            }
        }
        log.info("[질의 SSE emitter 연결] requestId={} replayedEvents={}", requestId, replayed);
        return emitter;
    }

    public void publish(String requestId,
                        String sourceEventId,
                        String stage,
                        String message,
                        Map<String, Object> data) {
        if (!claimSourceEvent(sourceEventId)) {
            log.info("[질의 SSE 중복 이벤트 생략] requestId={} sourceEventId={}", requestId, sourceEventId);
            return;
        }
        long sequence = nextSequence(requestId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request_id", requestId);
        payload.put("sequence", sequence);
        payload.put("received_at", clock.instant().toString());
        payload.put("stage", stage);
        payload.put("message", message);
        payload.put("data", data == null ? Map.of() : data);
        storeAndBroadcast(new StoredEvent(requestId, sequence, "query.log", payload));
        log.info("[질의 SSE 이벤트 발행] requestId={} sequence={} event=query.log stage={} dataKeys={}",
                requestId, sequence, stage, data != null ? data.keySet() : List.of());
    }

    private boolean claimSourceEvent(String sourceEventId) {
        if (sourceEventId == null || sourceEventId.isBlank()) {
            throw new IllegalArgumentException("질의 진행 이벤트 ID는 필수입니다.");
        }
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent(
                SOURCE_EVENT_KEY_PREFIX + sourceEventId,
                "1",
                EVENT_TTL);
        return Boolean.TRUE.equals(claimed);
    }

    public void complete(String requestId) {
        long sequence = nextSequence(requestId);
        storeAndBroadcast(new StoredEvent(requestId, sequence, EVENT_COMPLETED,
                Map.of("request_id", requestId, "status", "completed")));
        log.info("[질의 SSE 완료 이벤트 발행] requestId={}", requestId);
    }

    public void fail(String requestId, String errorMessage) {
        long sequence = nextSequence(requestId);
        storeAndBroadcast(new StoredEvent(requestId, sequence, EVENT_FAILED,
                Map.of("request_id", requestId, "status", "failed", "error", errorMessage)));
        log.warn("[질의 SSE 실패 이벤트 발행] requestId={} error={}", requestId, errorMessage);
    }

    public void cancel(String requestId) {
        storeAndBroadcast(new StoredEvent(requestId, nextSequence(requestId), EVENT_CANCELLED,
                Map.of("request_id", requestId, "status", "cancelled")));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        StoredEvent event = parse(new String(message.getBody(), StandardCharsets.UTF_8));
        List<Subscriber> list = subscribers.get(event.requestId());
        log.debug("[질의 Redis pub/sub 수신] requestId={} sequence={} event={} localSubscribers={}",
                event.requestId(), event.sequence(), event.name(), list != null ? list.size() : 0);
        if (list == null) {
            return;
        }
        for (Subscriber subscriber : list) {
            subscriber.deliver(event);
        }
    }

    /** idle SSE 연결이 중간 네트워크 장비에 끊기지 않도록 주기적으로 :ping comment를 보낸다. */
    @Scheduled(fixedDelay = 15_000)
    public void sendHeartbeat() {
        subscribers.values().forEach(list -> list.forEach(Subscriber::heartbeat));
    }

    private long nextSequence(String requestId) {
        String key = SEQUENCE_KEY_PREFIX + requestId;
        Long sequence = redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, EVENT_TTL);
        return sequence == null ? 0 : sequence;
    }

    private void storeAndBroadcast(StoredEvent event) {
        String json = serialize(event);
        String key = EVENTS_KEY_PREFIX + event.requestId();
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "if ARGV[4] == 'true' then redis.call('SET', KEYS[2], '1', 'EX', ARGV[3]); redis.call('DEL', KEYS[1]) "
                + "elseif redis.call('EXISTS', KEYS[2]) == 1 then return 0 end "
                + "redis.call('RPUSH', KEYS[1], ARGV[1]); redis.call('LTRIM', KEYS[1], -200, -1); "
                + "redis.call('EXPIRE', KEYS[1], ARGV[3]); redis.call('PUBLISH', ARGV[2], ARGV[1]); return 1", Long.class);
        redisTemplate.execute(script, List.of(key, "query:cancelled:" + event.requestId()), json, CHANNEL,
                Long.toString(EVENT_TTL.toSeconds()), Boolean.toString(EVENT_CANCELLED.equals(event.name())));
        log.debug("[질의 Redis 이벤트 저장·발행 완료] requestId={} sequence={} event={}",
                event.requestId(), event.sequence(), event.name());
    }

    private String serialize(StoredEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("query 이벤트 직렬화 실패: " + event.requestId(), e);
        }
    }

    private StoredEvent parse(String json) {
        try {
            return objectMapper.readValue(json, StoredEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("query 이벤트 역직렬화 실패", e);
        }
    }

    record StoredEvent(String requestId, long sequence, String name, Map<String, Object> payload) {

        // 파생 값이라 Redis 저장 JSON에 필드로 직렬화되지 않게 한다.
        @com.fasterxml.jackson.annotation.JsonIgnore
        boolean isTerminal() {
            return EVENT_COMPLETED.equals(name) || EVENT_FAILED.equals(name) || EVENT_CANCELLED.equals(name);
        }
    }

    /**
     * SSE emitter 하나에 대한 전달 상태. sequence가 뒤로 가지 않게 걸러 중복 전달을 막고,
     * 종결 이벤트 이후에는 어떤 전송도 하지 않는다(종결 뒤에 저장된 이벤트가 replay될 수 있다).
     */
    private static final class Subscriber {

        private final SseEmitter emitter;
        private final AtomicLong lastDeliveredSequence = new AtomicLong(0);
        private boolean closed = false;

        private Subscriber(SseEmitter emitter) {
            this.emitter = emitter;
        }

        synchronized void deliver(StoredEvent event) {
            if (closed || event.sequence() <= lastDeliveredSequence.get()) {
                return;
            }
            lastDeliveredSequence.set(event.sequence());
            try {
                emitter.send(SseEmitter.event().name(event.name()).data(event.payload()));
            } catch (IOException | IllegalStateException e) {
                closed = true;
                completeQuietly(e);
                return;
            }
            if (event.isTerminal()) {
                closed = true;
                emitter.complete();
            }
        }

        synchronized void heartbeat() {
            if (closed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                closed = true;
                completeQuietly(e);
            }
        }

        /** 이미 완료된 emitter에 completeWithError를 부르면 또 던지므로 조용히 삼킨다. */
        private void completeQuietly(Exception cause) {
            try {
                emitter.completeWithError(cause);
            } catch (RuntimeException ignored) {
                // emitter가 이미 종료된 경우
            }
        }
    }
}
