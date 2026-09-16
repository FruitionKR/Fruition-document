package fruition.core.aitask.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.chat.service.ChatEvidenceRecorder;
import fruition.core.chat.service.ChatTurnRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.query.dto.QueryResponse;
import fruition.core.query.repository.PipelineQueryResponse;
import fruition.core.query.service.QueryService;
import fruition.core.aihistory.dto.OperationResultRequest;
import fruition.core.aihistory.exception.RestorePreviewStaleException;
import fruition.core.aihistory.service.LintOperationStarter;
import fruition.core.aihistory.service.OperationIngestService;
import fruition.core.wikimaintenance.repository.PipelineWikiLintResponse;
import fruition.core.wikimaintenance.service.WikiMaintenanceService;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.aihistory.service.RestoreApplier;
import fruition.core.aihistory.service.RestoreExecuteService;
import fruition.core.aihistory.service.RestoreOperationLifecycle;
import fruition.core.document.service.DocumentService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Kafka result event를 core DB에 멱등 반영한다. */
@Service
public class AiTaskResultApplier {

    private static final Set<String> MARKDOWN_ACTIONS = Set.of("markdown_create", "markdown_edit");
    private static final Set<String> NON_MUTATING_ACTIONS = Set.of(
            "chat_answer", "conversation_reply", "clarify", "reject", "skill_authoring", "skill_draft_proposal");
    private static final Set<String> AUTONOMOUS_ACTIONS = Set.of("folder_organize", "workspace_workflow");

    private static final Logger log = LoggerFactory.getLogger(AiTaskResultApplier.class);

    private final ChatTurnRecorder chatTurnRecorder;
    private final ChatEvidenceRecorder chatEvidenceRecorder;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final QueryService queryService;
    private final OperationIngestService operationIngestService;
    private final LintOperationStarter lintOperationStarter;
    private final WikiMaintenanceService wikiMaintenanceService;
    private final OperationLogRepository operationLogRepository;
    private final RestoreApplier restoreApplier;
    private final RestoreOperationLifecycle restoreLifecycle;
    private final DocumentService documentService;

    public AiTaskResultApplier(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                               QueryService queryService,
                               OperationIngestService operationIngestService,
                               LintOperationStarter lintOperationStarter,
                               WikiMaintenanceService wikiMaintenanceService,
                               OperationLogRepository operationLogRepository,
                               RestoreApplier restoreApplier,
                               RestoreOperationLifecycle restoreLifecycle,
                               DocumentService documentService,
                               ChatTurnRecorder chatTurnRecorder,
                               ChatEvidenceRecorder chatEvidenceRecorder) {
        this.chatTurnRecorder = chatTurnRecorder;
        this.chatEvidenceRecorder = chatEvidenceRecorder;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.queryService = queryService;
        this.operationIngestService = operationIngestService;
        this.lintOperationStarter = lintOperationStarter;
        this.wikiMaintenanceService = wikiMaintenanceService;
        this.operationLogRepository = operationLogRepository;
        this.restoreApplier = restoreApplier;
        this.restoreLifecycle = restoreLifecycle;
        this.documentService = documentService;
    }

    @Transactional
    public void applyLint(JsonNode event) {
        String eventId = text(event, "event_id");
        String runId = text(event, "run_id");
        if (!beginResult(runId)) return;
        JsonNode request = required(event, "request");
        String operationId = request.path("operation_id").isNull()
                ? null : request.path("operation_id").asText(null);
        boolean first = jdbcTemplate.update("""
                INSERT INTO ai_task_result_receipts (event_id, run_id, task_kind)
                VALUES (?, ?, 'lint') ON CONFLICT (event_id) DO NOTHING
                """, eventId, runId) == 1;
        if (!first || operationId == null) return;

        if (!"succeeded".equals(text(event, "status"))) {
            lintOperationStarter.markFailed(operationId,
                    event.path("error").asText("Wiki lint 처리에 실패했습니다."));
            return;
        }
        String workspaceId = text(request, "workspace_id");
        String userId = text(request, "user_id");
        JsonNode payload = required(event, "payload");
        var response = PipelineWikiLintResponse.from(payload);
        operationIngestService.accept(operationId, new OperationResultRequest(
                response.operationId(), "lint", "succeeded", workspaceId, userId, null,
                "Wiki lint로 페이지 " + response.changedPages().size() + "개를 변경했습니다.",
                response.changedPages().stream()
                        .map(page -> new OperationResultRequest.ChangedPage(
                                page.pageId(), page.pageType(), page.markdownKey(),
                                page.contributionKey(), page.contentHash(), false))
                        .toList(), java.util.List.of()));
        wikiMaintenanceService.markLintSucceeded(workspaceId);
    }

    @Transactional
    public void applyRestore(JsonNode event) {
        String eventId = text(event, "event_id");
        String runId = text(event, "run_id");
        if (!beginResult(runId)) return;
        if (jdbcTemplate.update("""
                INSERT INTO ai_task_result_receipts (event_id, run_id, task_kind)
                VALUES (?, ?, 'restore') ON CONFLICT (event_id) DO NOTHING
                """, eventId, runId) == 0) return;
        JsonNode request = required(event, "request");
        String operationId = text(request, "operation_id");
        OperationLog operation = operationLogRepository.findById(operationId)
                .orElseThrow(() -> new IllegalArgumentException("Restore operation을 찾을 수 없습니다."));
        if (!"succeeded".equals(text(event, "status"))) {
            restoreLifecycle.fail(operationId,
                    event.path("error").asText("Wiki restore 처리에 실패했습니다."),
                    java.time.Instant.now());
            return;
        }

        OperationResultRequest result = objectMapper.convertValue(
                required(event, "payload"), OperationResultRequest.class);
        if (!operation.getStatus().isTerminal()) {
            RestoreExecuteService.RestoreManifest manifest = readRestoreManifest(operation);
            try {
                restoreApplier.apply(operation, manifest.plan(), manifest.excludedOperationIds(),
                        manifest.expectedContributions(), java.time.Instant.now());
            } catch (RestorePreviewStaleException e) {
                operation.complete(OperationStatus.conflict, e.getMessage(),
                        operation.getChangedResourceCount(), null, java.time.Instant.now());
                return;
            }
        }
        operationIngestService.accept(operationId, result);
    }

    @Transactional
    public void applyIngest(JsonNode event) {
        String eventId = text(event, "event_id");
        String runId = text(event, "run_id");
        if (!beginResult(runId)) return;
        if (jdbcTemplate.update("""
                INSERT INTO ai_task_result_receipts (event_id, run_id, task_kind)
                VALUES (?, ?, 'ingest') ON CONFLICT (event_id) DO NOTHING
                """, eventId, runId) == 0) return;
        JsonNode request = required(event, "request");
        String documentId = text(request, "document_id");
        String operationId = text(request, "operation_id");
        OperationResultRequest result = objectMapper.convertValue(
                required(event, "payload"), OperationResultRequest.class);
        operationIngestService.accept(operationId, result);
        documentService.applyPipelineResult(documentId, runId,
                "succeeded".equals(text(event, "status")) ? "succeeded" : "failed",
                event.path("error").asText(null));
    }

    /**
     * @return 최초 반영 여부와 실제 projection 반영 오류. 호출부는 이 결과로 SSE를 한 번만 종료한다.
     */
    @Transactional
    public AgentApplyResult applyAgent(JsonNode event) {
        String eventId = text(event, "event_id");
        String runId = text(event, "run_id");
        if (!beginResult(runId)) return new AgentApplyResult(false, null);
        if (jdbcTemplate.update("""
                INSERT INTO ai_task_result_receipts (event_id, run_id, task_kind, event_payload)
                VALUES (?, ?, 'agent', CAST(? AS jsonb))
                ON CONFLICT (run_id, task_kind) WHERE task_kind = 'agent' DO NOTHING
                """, eventId, runId, event.toString()) == 0) return new AgentApplyResult(false, null);

        int updated;
        String errorCode;
        if ("succeeded".equals(text(event, "status"))) {
            JsonNode request = event.get("request");
            JsonNode payload = event.get("payload");
            errorCode = null;
            if (request == null || !request.isObject()) {
                errorCode = "agent_result_invalid_request";
            } else if (payload == null || !payload.isObject()) {
                errorCode = "agent_result_invalid_payload";
            } else if (!isSupportedAction(payload)) {
                errorCode = "agent_result_unsupported_action";
            } else if (isMarkdownAction(payload) && expectedMarkdown(event) == null) {
                errorCode = "agent_result_missing_canonical_markdown";
            }
            if (errorCode != null) {
                updated = markAgentFailed(runId, errorCode);
            } else {
                AgentProjection projection = loadAgentProjection(runId);
                errorCode = agentRequestIdentityError(request, projection);
                updated = errorCode == null
                        ? jdbcTemplate.update("""
                                UPDATE agent_apply_projections
                                SET status = 'ready', result = CAST(? AS jsonb), ready_markdown = ?,
                                    error_code = NULL, updated_at = now()
                                WHERE run_id = ? AND status = 'queued'
                                """, payload.toString(), expectedMarkdown(event), runId)
                        : markAgentFailed(runId, errorCode);
            }
        } else {
            String error = event.path("error").asText(null);
            errorCode = error == null || error.isBlank() ? "agent_turn_failed" : error;
            updated = markAgentFailed(runId, errorCode);
        }
        if (updated != 1) {
            throw new IllegalStateException("Agent 적용 projection을 갱신하지 못했습니다: " + runId);
        }
        recordAgentChatMessage(event, errorCode);
        chatTurnRecorder.recordContextSummary(
                textOrNull(event.path("request"), "session_id"),
                event.path("payload").path("updated_conversation_summary").asText(null));
        return new AgentApplyResult(true, errorCode);
    }

    /**
     * Agent 결과를 채팅 말풍선에 채운다. 세션 없이 만들어진 예전 run은 message_context가 없어 건너뛴다.
     * 채팅 기록 실패가 적용 표를 되돌리면 안 되므로 여기서 삼킨다 — 이 시점에 projection은 이미 확정됐다.
     */
    private void recordAgentChatMessage(JsonNode event, String errorCode) {
        JsonNode context = event.path("request").path("message_context");
        String assistantMessageId = textOrNull(context, "assistant_message_id");
        if (assistantMessageId == null) {
            return;
        }
        try {
            if (errorCode == null) {
                JsonNode payload = event.path("payload");
                chatTurnRecorder.completeAgentTurn(assistantMessageId,
                        payload.path("action").asText(null), agentMessageContent(payload));
                recordAgentChatEvidence(assistantMessageId, payload);
            } else {
                chatTurnRecorder.markFailed(assistantMessageId, describeAgentError(errorCode));
            }
        } catch (RuntimeException e) {
            log.warn("[Agent 채팅 기록 실패] runId={} assistantMessageId={} errorType={}",
                    text(event, "run_id"), assistantMessageId, e.getClass().getSimpleName());
        }
    }

    public record AgentApplyResult(boolean applied, String error) {}

    /**
     * 내부 오류 코드에 대응하는 사용자 문구. 코드는 판정과 로그에 쓰고, 화면에는 이 문장을 보낸다.
     * 여기 없는 값은 pipeline이 준 사유 문장이라 그대로 내보낸다.
     */
    private static final Map<String, String> AGENT_ERROR_MESSAGE = Map.of(
            "agent_turn_failed", "Agent 처리 중 오류가 발생했습니다.",
            "agent_result_invalid_request", "Agent 결과의 요청 정보가 올바르지 않습니다.",
            "agent_result_invalid_payload", "Agent 결과 형식이 올바르지 않습니다.",
            "agent_result_unsupported_action", "지원하지 않는 Agent 처리 결과입니다.",
            "agent_result_missing_canonical_markdown", "편집안 본문이 비어 있어 적용할 수 없습니다.",
            "agent_result_request_mismatch", "요청과 결과가 달라 적용할 수 없습니다.");

    /** 화면·SSE로 나갈 문구. 내부 코드가 그대로 사용자에게 보이지 않게 한다. */
    public static String describeAgentError(String errorCode) {
        return AGENT_ERROR_MESSAGE.getOrDefault(errorCode, errorCode);
    }

    /**
     * AI가 질의로 판정한 턴은 답변과 함께 근거가 온다. 질의 경로와 같은 자리에 남겨야
     * 새로고침한 뒤에도 근거가 보인다. 편집·Skill 갈래에는 chat이 없어 아무것도 하지 않는다.
     *
     * <p>해석 실패는 호출부(recordAgentChatMessage)가 이미 삼킨다. DB에 쓰기 전이라 트랜잭션도
     * 멀쩡해 답변은 그대로 남는다. 여기서 또 잡지 않는다.
     */
    private void recordAgentChatEvidence(String assistantMessageId, JsonNode payload) {
        JsonNode chat = payload.path("chat");
        if (!chat.isObject()) {
            return;
        }
        chatEvidenceRecorder.record(assistantMessageId,
                objectMapper.convertValue(chat, PipelineQueryResponse.class));
    }

    /** 갈래별 기본 말풍선 문구. 편집 결과 본문은 미리보기에서 보므로 여기서는 무엇을 했는지만 알린다. */
    private static final Map<String, String> ACTION_FALLBACK_MESSAGE = Map.of(
            "markdown_create", "문서 초안을 만들었습니다. 미리보기에서 확인해 주세요.",
            "markdown_edit", "편집안을 만들었습니다. 미리보기에서 확인해 주세요.",
            "folder_organize", "폴더 정리 계획을 만들었습니다. 미리보기에서 확인해 주세요.",
            "workspace_workflow", "작업 계획을 만들었습니다. 미리보기에서 확인해 주세요.",
            "skill_authoring", "Skill 초안을 만들었습니다. 미리보기에서 확인해 주세요.",
            "skill_draft_proposal", "Skill 제안을 만들었습니다. 미리보기에서 확인해 주세요.");

    /**
     * 말풍선에 보일 본문. chat_answer는 Query 답변이고 conversation_reply는 message에 담긴다.
     *
     * <p>빈 문자열은 돌려주지 않는다. 빈 말풍선은 화면에서 아무것도 아니고,
     * 다음 턴의 대화 맥락에 실리면 pipeline이 요청 전체를 거부한다.
     */
    private static String agentMessageContent(JsonNode payload) {
        String answer = payload.path("chat").path("answer").asText(null);
        if (answer != null && !answer.isBlank()) {
            return answer;
        }
        String message = payload.path("message").asText(null);
        if (message != null && !message.isBlank()) {
            return message;
        }
        return ACTION_FALLBACK_MESSAGE.getOrDefault(payload.path("action").asText(),
                "요청을 처리했습니다.");
    }

    public static String expectedMarkdown(JsonNode event) {
        JsonNode request = event == null ? null : event.get("request");
        JsonNode payload = event == null ? null : event.get("payload");
        if (request == null || !request.isObject() || payload == null || !payload.isObject()) return null;
        if ("markdown_create".equals(payload.path("action").asText())) {
            return payload.path("generated_markdown").path("markdown").asText(null);
        }
        if (!"markdown_edit".equals(payload.path("action").asText())) return null;
        String source = request.path("editor_snapshot").path("markdown").asText(null);
        JsonNode edit = payload.path("edit");
        int start = edit.path("actual_target").path("start_line").asInt(0);
        int end = edit.path("actual_target").path("end_line").asInt(0);
        String replacement = edit.path("replacement_markdown").asText(null);
        String operation = edit.path("operation").asText(null);
        if (source == null || replacement == null || start < 1 || end < start || operation == null) return null;
        java.util.List<String> lines = new java.util.ArrayList<>(java.util.List.of(source.split("\\n", -1)));
        java.util.List<String> replacementLines = java.util.List.of(replacement.split("\\n", -1));
        if (end > lines.size()) return null;
        if ("replace".equals(operation)) {
            lines.subList(start - 1, end).clear();
            lines.addAll(start - 1, replacementLines);
        } else if ("insert_after".equals(operation)) {
            lines.addAll(end, replacementLines);
        } else {
            return null;
        }
        return String.join("\n", lines);
    }

    private static boolean isMarkdownAction(JsonNode payload) {
        return MARKDOWN_ACTIONS.contains(payload.path("action").asText());
    }

    private static boolean isSupportedAction(JsonNode payload) {
        String action = payload.path("action").asText();
        return MARKDOWN_ACTIONS.contains(action)
                || NON_MUTATING_ACTIONS.contains(action)
                || AUTONOMOUS_ACTIONS.contains(action);
    }

    private int markAgentFailed(String runId, String errorCode) {
        return jdbcTemplate.update("""
                UPDATE agent_apply_projections
                SET status = 'failed', error_code = ?, updated_at = now()
                WHERE run_id = ? AND status = 'queued'
                """, errorCode, runId);
    }

    private AgentProjection loadAgentProjection(String runId) {
        return jdbcTemplate.query("""
                SELECT workspace_id, user_id, document_id, base_version, apply_operation_id
                FROM agent_apply_projections
                WHERE run_id = ?
                FOR UPDATE
                """, resultSet -> resultSet.next() ? new AgentProjection(
                resultSet.getString("workspace_id"),
                resultSet.getString("user_id"),
                resultSet.getString("document_id"),
                resultSet.getObject("base_version", Long.class),
                resultSet.getString("apply_operation_id")) : null, runId);
    }

    private String agentRequestIdentityError(JsonNode request, AgentProjection projection) {
        if (projection == null
                || !Objects.equals(textOrNull(request, "workspace_id"), projection.workspaceId())
                || !Objects.equals(textOrNull(request, "user_id"), projection.userId())
                || !Objects.equals(textOrNull(request, "document_id"), projection.documentId())
                || !Objects.equals(textOrNull(request, "apply_operation_id"), projection.applyOperationId())
                || !Objects.equals(longOrNull(request, "base_version"), projection.baseVersion())) {
            return "agent_result_request_mismatch";
        }
        return null;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 문서를 열지 않은 턴은 base_version이 없다. 숫자가 아니면 null로 본다. */
    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }

    static record AgentProjection(
            String workspaceId,
            String userId,
            String documentId,
            Long baseVersion,
            String applyOperationId
    ) {}

    private RestoreExecuteService.RestoreManifest readRestoreManifest(OperationLog operation) {
        try {
            return objectMapper.readValue(operation.getRestoreManifest(),
                    RestoreExecuteService.RestoreManifest.class);
        } catch (Exception e) {
            throw new IllegalStateException("Restore manifest를 읽지 못했습니다.", e);
        }
    }

    @Transactional
    public QueryProjection applyQuery(JsonNode event) {
        String runId = text(event, "run_id");
        if (!beginResult(runId)) return null;
        boolean first = jdbcTemplate.update("""
                INSERT INTO ai_task_result_receipts (event_id, run_id, task_kind, event_payload)
                VALUES (?, ?, 'query', CAST(? AS jsonb))
                ON CONFLICT (run_id, task_kind) WHERE task_kind = 'query' DO NOTHING
                """, text(event, "event_id"), runId, event.toString()) == 1;
        JsonNode canonical = first ? event : canonicalQueryEvent(runId);
        JsonNode request = required(canonical, "request");
        String status = text(canonical, "status");
        String sessionId = text(request, "session_id");
        String question = text(request, "question");
        QueryService.QueryMessageContext context = objectMapper.convertValue(
                required(request, "message_context"), QueryService.QueryMessageContext.class);

        if ("succeeded".equals(status)) {
            PipelineQueryResponse result = objectMapper.convertValue(
                    required(canonical, "payload"), PipelineQueryResponse.class);
            QueryResponse response = first
                    ? queryService.completeAsync(sessionId, question, runId, context, result)
                    : responseFrom(question, context, result);
            if (first) {
                chatTurnRecorder.recordContextSummary(sessionId, result.updatedConversationSummary());
            }
            return new QueryProjection(runId, response, null);
        }

        String error = canonical.path("error").asText("질의 처리 중 오류가 발생했습니다.");
        if (first) {
            queryService.failAsync(runId, context, error);
        }
        return new QueryProjection(runId, null, error);
    }

    private JsonNode canonicalQueryEvent(String runId) {
        String payload = jdbcTemplate.queryForObject("""
                SELECT event_payload::text
                FROM ai_task_result_receipts
                WHERE run_id = ? AND task_kind = 'query'
                """, String.class, runId);
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Query terminal event를 읽지 못했습니다: " + runId, e);
        }
    }

    private QueryResponse responseFrom(String question,
                                       QueryService.QueryMessageContext context,
                                       PipelineQueryResponse result) {
        return new QueryResponse(
                new QueryResponse.MessageSummary(context.userMessageId(), "user", question,
                        "completed", context.createdAt()),
                new QueryResponse.MessageSummary(context.assistantMessageId(), "assistant",
                        result.answer(), "completed", context.createdAt()),
                result.relatedPages(), result.evidenceSnippets(), result.graphContext(),
                result.traversalPaths(), result.webSearchRequested(), result.webSearchExecuted(),
                result.resultCount(), result.errorCode());
    }

    private boolean beginResult(String runId) {
        var states = jdbcTemplate.queryForList("SELECT status FROM ai_task_runs WHERE id = ? FOR UPDATE", String.class, runId);
        if (states.isEmpty()) throw new IllegalStateException("AI 작업 등록을 찾을 수 없습니다: " + runId);
        if (!Set.of("running", "completed").contains(states.getFirst())) return false;
        jdbcTemplate.queryForObject("SELECT set_config('app.ai_task_run_id', ?, true)", String.class, runId);
        jdbcTemplate.update("UPDATE ai_task_runs SET status = 'completed', updated_at = now() WHERE id = ?", runId);
        return true;
    }

    public boolean acceptsProgress(String runId) {
        var states = jdbcTemplate.queryForList("SELECT status FROM ai_task_runs WHERE id = ?", String.class, runId);
        return !states.isEmpty() && Set.of("running", "completed").contains(states.getFirst());
    }

    private JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("AI task event 필드가 없습니다: " + field);
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        String value = required(node, field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("AI task event 필드가 비었습니다: " + field);
        }
        return value;
    }

    public record QueryProjection(String runId, QueryResponse response, String error) {}
}
