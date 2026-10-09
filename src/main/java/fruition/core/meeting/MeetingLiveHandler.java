package fruition.core.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fruition.core.usage.service.CreditService;
import fruition.core.usage.service.UsageChargeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 브라우저와 ai-svc 실시간 전사 WebSocket을 연결마다 1:1로 잇고, 확정 전사를 저장한 뒤에만 브라우저에 전달한다.
 *
 * <p>녹음 종료·일시정지는 이 연결의 finish 메시지로만 받는다. ai-svc 연결을 가진 Pod만 finish를 보낼 수 있기 때문이다.
 * 오디오는 ai-svc 전송이 끝난 뒤 다음 frame을 읽어 서버 메모리에 쌓이지 않게 한다.
 */
@Component
public class MeetingLiveHandler extends AbstractWebSocketHandler {
    static final CloseStatus IN_USE = new CloseStatus(4409, "meeting live in use");
    /**
     * 회의 하나에 저장할 구간 수 상한. 회의록 AI에는 확정 구간을 약 1,000자로 묶어 보내므로(ADR-0023 결정 7)
     * 구간 수는 더 이상 AI 한도가 아니고 저장 보호 장치다. 실제 내용 한도는 {@link #MAX_CHARS}가 맡는다.
     */
    static final int MAX_SEGMENTS = 10_000;
    static final long MAX_CHARS = 100_000;
    private static final String LIMIT_MESSAGE = "회의 전사 한도에 닿았습니다. 지금까지의 전사로 회의록을 만들거나 새 회의를 시작해 주세요.";
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern SEGMENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Set<String> COMMANDS = Set.of("commit", "finish");
    private static final Logger log = LoggerFactory.getLogger(MeetingLiveHandler.class);

    private final MeetingRepository repository;
    private final MeetingLiveLock liveLock;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final String internalToken;
    private final UsageChargeService usageCharges;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService lockRenewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "meeting-live-lock");
        thread.setDaemon(true);
        return thread;
    });
    /**
     * 구간 정산은 AI 호출을 기다리므로 잠금 갱신과 다른 스레드에서 한다.
     * ponytail: 연결 전체가 스레드 하나를 나눠 쓴다. 동시 회의가 많아 구간 정산이 밀리면 가상 스레드로 나눈다.
     */
    private final ScheduledExecutorService billingRenewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "meeting-live-billing");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Live> sessions = new ConcurrentHashMap<>();

    /** 종료 시 잠금 갱신·구간 정산을 멈춘다. 열린 연결은 TCP가 끊기고 Redis 잠금은 TTL(90초)로 풀린다. */
    @PreDestroy
    void shutdownLockRenewer() {
        lockRenewer.shutdownNow();
        billingRenewer.shutdownNow();
    }

    public MeetingLiveHandler(MeetingRepository repository, MeetingLiveLock liveLock, ObjectMapper objectMapper,
                              Environment environment,
                              @Value("${app.internal.callback-token}") String internalToken,
                              UsageChargeService usageCharges) {
        this.usageCharges = usageCharges;
        this.repository = repository;
        this.liveLock = liveLock;
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.internalToken = internalToken;
    }

    /** 연결 하나의 상태. ai-svc 이벤트는 JDK WebSocket이 한 번에 하나씩 전달하므로 저장 쓰기도 한 스레드다. */
    private final class Live {
        final MeetingService.Ticket ticket;
        final String lockToken = UUID.randomUUID().toString();
        final WebSocketSession browser;
        volatile MeetingRepository.Stream stream;
        volatile WebSocket upstream;
        /** 이 연결의 사용량 실행 ID. 시작 시 크레딧을 예약하고 구간마다 정산·재예약하며 연결이 끝나면 정산한다. */
        volatile String runId;
        int billingSeq;
        ScheduledFuture<?> billing;
        volatile boolean ready;
        /** 종료 처리는 한 번만 한다. 전송 시간 초과(Tomcat 스레드)와 ai-svc 오류(listener 스레드)가 겹칠 수 있다. */
        final AtomicBoolean closing = new AtomicBoolean();
        ScheduledFuture<?> renewal;

        Live(MeetingService.Ticket ticket, WebSocketSession browser) {
            this.ticket = ticket;
            this.browser = new ConcurrentWebSocketSessionDecorator(browser, (int) SEND_TIMEOUT.toMillis(), 512 * 1024);
        }

        String segmentId(JsonNode aiId) {
            if (aiId == null || aiId.isNull()) {
                return null;
            }
            String id = "s" + stream.order() + "_" + aiId.asText();
            if (!SEGMENT_ID.matcher(id).matches()) {
                throw new IllegalStateException("전사 구간 ID 형식이 올바르지 않습니다.");
            }
            return id;
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // ai-svc 계약상 오디오 frame은 최대 48,000 bytes(1초)다. Tomcat 기본 버퍼(8 KiB)로는 받을 수 없다.
        // 컨테이너 전역 설정 bean은 MockMvc 테스트(서블릿 컨테이너 없음)에서 실패하므로 연결마다 지정한다.
        session.setBinaryMessageSizeLimit(64 * 1024);
        MeetingService.Ticket ticket = (MeetingService.Ticket) session.getAttributes().get(MeetingLiveConfig.TICKET_ATTRIBUTE);
        Live live = new Live(ticket, session);
        if (!liveLock.tryAcquire(ticket.meetingId(), live.lockToken)) {
            session.close(IN_USE);
            return;
        }
        sessions.put(session.getId(), live);
        live.renewal = lockRenewer.scheduleAtFixedRate(
                () -> liveLock.renew(ticket.meetingId(), live.lockToken), 30, 30, TimeUnit.SECONDS);
        boolean open = repository.findById(ticket.meetingId())
                .filter(meeting -> "live".equals(meeting.source()) && "open".equals(meeting.status()))
                .isPresent();
        if (!open) {
            fail(live, "meeting_not_open", "실시간 받아쓰기를 시작할 수 없는 회의입니다.", CloseStatus.POLICY_VIOLATION);
            return;
        }
        long[] totals = repository.totals(ticket.meetingId());
        if (totals[0] >= MAX_SEGMENTS || totals[1] >= MAX_CHARS) {
            // 한도에 닿은 회의는 연결을 받자마자 거절한다. 구간을 열지 않아 다시 연결할 때마다 실패한 연결이
            // 쌓이지 않고, 저장된 전사로 회의록을 만드는 길은 그대로 남는다.
            fail(live, "transcript_limit", LIMIT_MESSAGE, CloseStatus.POLICY_VIOLATION);
            return;
        }
        try {
            live.runId = usageCharges.startRun("meeting_live", ticket.workspaceId(), ticket.userId());
        } catch (CreditService.InsufficientCreditException e) {
            fail(live, "insufficient_credit", e.getMessage(), CloseStatus.POLICY_VIOLATION);
            return;
        }
        long interval = environment.getProperty("app.billing.live-renew-seconds", Long.class, 60L);
        live.billing = billingRenewer.scheduleWithFixedDelay(() -> renewCredit(live), interval, interval, TimeUnit.SECONDS);
        live.stream = repository.openStream(ticket.meetingId());
        URI uri = URI.create(environment.getRequiredProperty("app.speech.live-endpoint")
                + "?workspace_id=" + encode(ticket.workspaceId()) + "&user_id=" + encode(ticket.userId())
                + "&run_id=" + encode(live.runId));
        // ai-svc 연결(실측 ready까지 약 3.5초)을 기다리며 요청 스레드를 잡지 않는다. 연결 객체는 onOpen에서 잡는다.
        httpClient.newWebSocketBuilder()
                .header("X-Internal-Token", internalToken)
                .buildAsync(uri, new AiListener(live))
                .orTimeout(15, TimeUnit.SECONDS)
                .whenComplete((upstream, error) -> {
                    if (error != null) {
                        log.warn("[회의 실시간 전사 연결 실패] meetingId={}", ticket.meetingId(), error);
                        fail(live, "transcription_failed", "전사를 시작하지 못했습니다. 다시 연결해 주세요.",
                                CloseStatus.SERVER_ERROR);
                    } else if (live.closing.get()) {
                        upstream.abort();  // 연결되는 사이에 브라우저가 떠났다
                    }
                });
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Live live = sessions.get(session.getId());
        if (live == null || !requireReady(live)) {
            return;
        }
        forward(live, () -> live.upstream.sendBinary(message.getPayload(), true));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Live live = sessions.get(session.getId());
        if (live == null || !requireReady(live)) {
            return;
        }
        String payload = message.getPayload();
        String type = null;
        try {
            if (payload.length() <= 100) {
                type = objectMapper.readTree(payload).path("type").asText(null);
            }
        } catch (Exception ignored) {
            // 아래에서 입력 오류로 닫는다.
        }
        if (!COMMANDS.contains(type)) {
            fail(live, "invalid_audio", "commit 또는 finish 명령이 필요합니다.", CloseStatus.POLICY_VIOLATION);
            return;
        }
        forward(live, () -> live.upstream.sendText(payload, true));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Live live = sessions.remove(session.getId());
        if (live == null) {
            return;
        }
        live.closing.set(true);
        if (live.renewal != null) {
            live.renewal.cancel(false);
        }
        if (live.billing != null) {
            live.billing.cancel(false);
        }
        if (live.upstream != null) {
            live.upstream.abort();
        }
        if (live.stream != null) {
            repository.endStream(live.stream.id(), "interrupted");
        }
        liveLock.release(live.ticket.meetingId(), live.lockToken);
        if (live.runId != null) {
            usageCharges.endRun(live.runId);
        }
    }

    /**
     * 그때까지의 전사 사용량을 차감하고 다음 구간을 다시 예약한다. enforce에서 잔액이 모자라면 이유를 알리고 연결을 닫는다.
     * 확정 구간은 남아 있어 충전한 뒤 새 ticket으로 이어서 녹음한다. AI 조회가 실패하면 다음 구간에 다시 한다.
     */
    private void renewCredit(Live live) {
        if (live.closing.get()) {
            return;
        }
        try {
            usageCharges.renewRun(live.runId, live.ticket.userId(), "meeting_live", ++live.billingSeq);
        } catch (CreditService.InsufficientCreditException e) {
            fail(live, "insufficient_credit", e.getMessage(), CloseStatus.POLICY_VIOLATION);
        } catch (RuntimeException e) {
            log.warn("[회의 실시간 전사 구간 정산 실패] meetingId={} runId={} error={}", live.ticket.meetingId(), live.runId,
                    e.toString());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        session.close(CloseStatus.SERVER_ERROR);
    }

    private boolean requireReady(Live live) {
        if (!live.ready) {
            fail(live, "invalid_audio", "ready 이벤트 전에는 음성을 보낼 수 없습니다.", CloseStatus.POLICY_VIOLATION);
            return false;
        }
        return true;
    }

    /** ai-svc 전송이 끝날 때까지 기다린다. 이 대기가 브라우저 읽기를 멈춰 속도를 맞춘다. */
    private void forward(Live live, java.util.function.Supplier<java.util.concurrent.CompletableFuture<WebSocket>> send) {
        try {
            send.get().get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            fail(live, "backpressure", "음성 전송이 밀리고 있습니다. 다시 연결해 주세요.", CloseStatus.SERVER_ERROR);
        } catch (Exception e) {
            fail(live, "transcription_failed", "전사가 중단됐습니다. 다시 연결해 주세요.", CloseStatus.SERVER_ERROR);
        }
    }

    private final class AiListener implements WebSocket.Listener {
        private final Live live;
        private final StringBuilder buffer = new StringBuilder();

        AiListener(Live live) {
            this.live = live;
        }

        /** buildAsync 완료보다 ready가 먼저 올 수 있어, 연결 객체를 여기서 먼저 잡는다. */
        @Override
        public void onOpen(WebSocket webSocket) {
            live.upstream = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String raw = buffer.toString();
                buffer.setLength(0);
                try {
                    handle(live, objectMapper.readTree(raw));
                } catch (Exception e) {
                    log.warn("[회의 전사 저장 실패] meetingId={}", live.ticket.meetingId(), e);
                    fail(live, "transcript_save_failed", "전사를 저장하지 못했습니다. 다시 연결해 주세요.",
                            CloseStatus.SERVER_ERROR);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            upstreamGone();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            upstreamGone();
        }

        private void upstreamGone() {
            if (!live.closing.get()) {
                fail(live, "transcription_failed", "전사가 중단됐습니다. 다시 연결해 주세요.", CloseStatus.SERVER_ERROR);
            }
        }
    }

    private void handle(Live live, JsonNode event) throws Exception {
        String meetingId = live.ticket.meetingId();
        switch (event.path("type").asText()) {
            case "ready" -> {
                live.ready = true;
                send(live, message("ready").put("sample_rate", event.path("sample_rate").asInt(24000))
                        .put("stream_order", live.stream.order()));
            }
            case "committed" -> {
                String id = live.segmentId(event.get("segment_id"));
                if (repository.totals(meetingId)[0] >= MAX_SEGMENTS) {
                    fail(live, "transcript_limit", LIMIT_MESSAGE, CloseStatus.POLICY_VIOLATION);
                    return;
                }
                int position = repository.register(meetingId, id, live.stream.id());
                send(live, message("committed").put("segment_id", id)
                        .put("previous_segment_id", live.segmentId(event.get("previous_segment_id")))
                        .put("position", position));
            }
            case "delta" -> send(live, message("delta")
                    .put("segment_id", live.segmentId(event.get("segment_id")))
                    .put("text", event.path("text").asText()));
            case "completed" -> {
                String id = live.segmentId(event.get("segment_id"));
                String text = event.path("text").asText();
                if (repository.totals(meetingId)[1] + text.length() > MAX_CHARS) {
                    fail(live, "transcript_limit", LIMIT_MESSAGE, CloseStatus.POLICY_VIOLATION);
                    return;
                }
                switch (repository.complete(meetingId, id, text)) {
                    case UPDATED, DUPLICATE -> send(live, message("completed").put("segment_id", id).put("text", text));
                    case CONFLICT -> fail(live, "segment_conflict", "같은 구간에 다른 확정 문장이 도착했습니다.",
                            CloseStatus.SERVER_ERROR);
                    case MISSING -> fail(live, "transcription_failed", "확정 문장의 구간을 찾을 수 없습니다.",
                            CloseStatus.SERVER_ERROR);
                }
            }
            case "finished" -> {
                int expected = event.path("segment_count").asInt(-1);
                if (repository.completedCount(live.stream.id()) != expected) {
                    fail(live, "transcript_incomplete", "마지막 문장까지 저장됐는지 확인하지 못했습니다.",
                            CloseStatus.SERVER_ERROR);
                    return;
                }
                repository.endStream(live.stream.id(), "finished");
                send(live, message("finished").put("segment_count", expected));
                live.closing.set(true);
                live.browser.close(CloseStatus.NORMAL);
            }
            case "error" -> fail(live, "transcription_failed", "전사가 중단됐습니다. 다시 연결해 주세요.",
                    CloseStatus.SERVER_ERROR);
            default -> log.debug("[회의 전사] 알 수 없는 ai-svc 이벤트 무시: {}", event.path("type").asText());
        }
    }

    /** 확정 구간은 유지한 채 이 연결만 실패로 기록하고 닫는다. 브라우저는 새 ticket으로 이어서 녹음한다. */
    private void fail(Live live, String code, String message, CloseStatus status) {
        if (!live.closing.compareAndSet(false, true)) {
            return;
        }
        if (live.stream != null) {
            repository.endStream(live.stream.id(), "failed");
        }
        try {
            send(live, message("error").put("code", code).put("message", message));
            live.browser.close(status);
        } catch (Exception e) {
            log.debug("[회의 전사] 브라우저 연결 종료 실패", e);
        }
    }

    private ObjectNode message(String type) {
        return objectMapper.createObjectNode().put("type", type);
    }

    private void send(Live live, ObjectNode message) throws java.io.IOException {
        if (live.browser.isOpen()) {
            live.browser.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
