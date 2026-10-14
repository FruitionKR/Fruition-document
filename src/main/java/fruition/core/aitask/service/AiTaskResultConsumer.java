package fruition.core.aitask.service;

import fruition.core.aitask.AiErrorMessages;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.query.service.QueryEventBroker;
import fruition.core.query.service.QueryRunStore;
import fruition.core.wiki.repository.WikiWorkspaceVersionRepository;
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
    private final WikiWorkspaceVersionRepository wikiVersions;

    public AiTaskResultConsumer(ObjectMapper objectMapper,
                                AiTaskResultApplier applier,
                                QueryRunStore queryRunStore,
                                QueryEventBroker queryEventBroker,
                                WikiWorkspaceVersionRepository wikiVersions) {
        this.objectMapper = objectMapper;
        this.applier = applier;
        this.queryRunStore = queryRunStore;
        this.queryEventBroker = queryEventBroker;
        this.wikiVersions = wikiVersions;
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
            if ("session_title".equals(event.path("status").asText())) {
                applier.applySessionTitle(event);
                return;
            }
            if ("convert_batch".equals(kind)) {
                applier.applyConvertBatch(event);
                return;
            }
            if ("meeting_transcription".equals(kind)) {
                applier.applyMeetingTranscription(event);
                return;
            }
            if ("ingest".equals(kind)) {
                applier.applyIngest(event);
                bumpWikiVersion(event);
                return;
            }
            if ("post_ingest".equals(kind)) {
                applier.applyPostIngest(event);
                bumpWikiVersion(event);
                return;
            }
            if ("lint".equals(kind)) {
                applier.applyLint(event);
                // dry_run은 위키를 바꾸지 않는다.
                if (!event.path("request").path("dry_run").asBoolean(false)) bumpWikiVersion(event);
                return;
            }
            if ("meeting_notes".equals(kind)) {
                applier.applyMeetingNotes(event);
                return;
            }
            if (kind.startsWith("restore_")) {
                applier.applyRestore(event);
                bumpWikiVersion(event);
                return;
            }
            if ("agent".equals(kind)) {
                // 질의와 같은 규칙이다. 최초 반영일 때만 종료 이벤트를 내 재전송으로 두 번 끝나지 않게 한다.
                var result = applier.applyAgent(event);
                if (result.applied()) {
                    if (result.error() == null) {
                        queryEventBroker.complete(flowId);
                    } else {
                        queryEventBroker.fail(flowId, result.error(), AiErrorMessages.agent(result.error()));
                    }
                }
                return;
            }
            if ("document_cleanup".equals(kind) || "document_rename".equals(kind)) {
                // 반영할 결과는 없지만 ai가 source 페이지를 지우거나 제목을 바꿨다.
                bumpWikiVersion(event);
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
                queryEventBroker.fail(projection.runId(), projection.error(),
                        AiErrorMessages.query(projection.error()));
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
            applier.recordProgress(event);
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

    /**
     * 위키를 바꾸는 결과마다 워크스페이스 위키 버전을 올린다(#68). 반영과 별도 트랜잭션이라 반영 중 잠금을 늘리지 않는다.
     * 반영이 커밋된 뒤 여기서 실패하면 레코드가 다시 소비되고, 반영은 receipt로 건너뛰며 버전만 다시 오른다.
     * 상태와 무관하게 올린다. 실패한 편입·복구도 일부 페이지를 바꿨을 수 있다. 중복 이벤트는 304 한 번을 놓칠 뿐이다.
     */
    private void bumpWikiVersion(JsonNode event) {
        String workspaceId = event.path("workspace_id").asText("");
        if (workspaceId.isBlank()) workspaceId = event.path("request").path("workspace_id").asText("");
        if (workspaceId.isBlank()) {
            log.warn("[위키 버전 갱신 생략] kind={} reason=workspace_id_missing", event.path("kind").asText());
            return;
        }
        wikiVersions.bump(workspaceId);
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
