package fruition.core.aitask.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.query.service.QueryEventBroker;
import fruition.core.query.service.QueryRunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** ai.task.event를 받아 core 상태와 Redis/SSE projection을 갱신한다. */
@Component
public class AiTaskResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(AiTaskResultConsumer.class);

    private final ObjectMapper objectMapper;
    private final AiTaskResultApplier applier;
    private final QueryRunStore queryRunStore;
    private final QueryEventBroker queryEventBroker;

    public AiTaskResultConsumer(ObjectMapper objectMapper,
                                AiTaskResultApplier applier,
                                QueryRunStore queryRunStore,
                                QueryEventBroker queryEventBroker) {
        this.objectMapper = objectMapper;
        this.applier = applier;
        this.queryRunStore = queryRunStore;
        this.queryEventBroker = queryEventBroker;
    }

    @KafkaListener(
            topics = "${app.ai-task.result-topic}",
            groupId = "${app.ai-task.result-consumer-group}",
            containerFactory = "aiTaskResultKafkaListenerContainerFactory")
    public void consume(String raw) throws Exception {
        JsonNode event = objectMapper.readTree(raw);
        String kind = event.path("kind").asText("unknown");
        String flowId = resolveFlowId(event);
        MDC.put("flowId", flowId);
        long startedAt = System.nanoTime();
        log.info("[AI task 결과 소비 시작] kind={} flowId={}", kind, flowId);
        try {
            // 진행 이벤트는 종류와 무관하게 중계만 한다. kind 분기보다 먼저 걸러야 한다 —
            // 뒤에 두면 agent 진행 이벤트가 applyAgent로 들어가 최종 결과로 오인된다.
            if ("progress".equals(event.path("status").asText())) {
                if (applier.acceptsProgress(flowId)) relayRunProgress(event, flowId);
                return;
            }
            if ("ingest".equals(kind)) {
                applier.applyIngest(event);
                return;
            }
            if ("lint".equals(kind)) {
                applier.applyLint(event);
                return;
            }
            if (kind.startsWith("restore_")) {
                applier.applyRestore(event);
                return;
            }
            if ("agent".equals(kind)) {
                // 질의와 같은 규칙이다. 최초 반영일 때만 종료 이벤트를 내 재전송으로 두 번 끝나지 않게 한다.
                var result = applier.applyAgent(event);
                if (result.applied()) {
                    if (result.error() == null) {
                        queryEventBroker.complete(flowId);
                    } else {
                        queryEventBroker.fail(flowId, AiTaskResultApplier.describeAgentError(result.error()));
                    }
                }
                return;
            }
            if (!"query".equals(kind)) {
                log.warn("[AI task 결과 소비 생략] kind={} flowId={} reason=unsupported_kind", kind, flowId);
                return;
            }
            var projection = applier.applyQuery(event);
            if (projection == null) return;
            if (projection.error() == null) {
                if (queryRunStore.markCompleted(projection.runId(), projection.response())) {
                    queryEventBroker.complete(projection.runId());
                }
            } else if (queryRunStore.markFailed(projection.runId(), projection.error())) {
                queryEventBroker.fail(projection.runId(), projection.error());
            }
        } catch (Exception e) {
            log.error("[AI task 결과 소비 실패] kind={} flowId={} errorType={}",
                    kind, flowId, e.getClass().getSimpleName(), e);
            throw e;
        } finally {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("[AI task 결과 소비 종료] kind={} flowId={} elapsedMs={}", kind, flowId, elapsedMs);
            MDC.remove("flowId");
        }
    }

    /**
     * 진행 이벤트는 화면 피드백용이라 유실을 허용한다. 여기서 예외를 올리면 error handler가
     * 같은 record를 무한 재시도하며 그 파티션의 최종 결과까지 막으므로 로그만 남기고 넘어간다.
     *
     * <p>run 종류를 가리지 않는다. Agent turn도 AI가 chat_answer로 판정하면 질의와 같은 단계를 낸다.
     */
    private void relayRunProgress(JsonNode event, String flowId) {
        JsonNode payload = event.path("payload");
        try {
            queryEventBroker.publish(
                    event.path("run_id").asText(),
                    event.path("event_id").asText(),
                    payload.path("stage").asText(),
                    payload.path("message").asText(),
                    objectMapper.convertValue(
                            payload.path("data"),
                            new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            log.warn("[질의 진행 이벤트 중계 실패] flowId={} eventId={} errorType={} error={}",
                    flowId, event.path("event_id").asText(), e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private String resolveFlowId(JsonNode event) {
        for (String field : new String[]{"run_id", "operation_id"}) {
            if (event.hasNonNull(field) && !event.path(field).asText().isBlank()) {
                return event.path(field).asText();
            }
        }
        return "-";
    }
}
