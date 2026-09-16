package fruition.core.query.service;

import fruition.core.query.domain.QueryRun;
import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.chat.service.ChatSessionService;
import fruition.core.document.repository.AiCommandOutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QueryRunService {

    private static final Logger log = LoggerFactory.getLogger(QueryRunService.class);
    private final QueryRunStore queryRunStore;
    private final QueryService queryService;
    private final ChatSessionService chatSessionService;
    private final AiCommandOutboxWriter outboxWriter;
    private final String commandTopic;
    private final fruition.core.aitask.service.AiTaskCancellationService cancellation;
    private final long timeoutSeconds;

    public QueryRunService(
            QueryRunStore queryRunStore,
            QueryService queryService,
            ChatSessionService chatSessionService,
            AiCommandOutboxWriter outboxWriter,
            @Value("${app.query.command-topic}") String commandTopic,
            fruition.core.aitask.service.AiTaskCancellationService cancellation,
            @Value("${app.query.timeout-seconds:30}") long timeoutSeconds) {
        this.queryRunStore = queryRunStore;
        this.queryService = queryService;
        this.chatSessionService = chatSessionService;
        this.outboxWriter = outboxWriter;
        this.commandTopic = commandTopic;
        this.cancellation = cancellation;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Transactional
    public QueryRun start(String workspaceId, String userId, String sessionId, String question) {
        return start(workspaceId, userId, sessionId, question, "openai", "gpt-5-nano");
    }

    @Transactional
    public QueryRun start(String workspaceId, String userId, String sessionId, String question,
                          String provider, String model) {
        return start(workspaceId, userId, sessionId, question, provider, model, false);
    }

    @Transactional
    public QueryRun start(String workspaceId, String userId, String sessionId, String question,
                          String provider, String model, boolean webSearchEnabled) {
        return start(workspaceId, userId, sessionId, question, provider, model, webSearchEnabled, null);
    }

    @Transactional
    public QueryRun start(String workspaceId, String userId, String sessionId, String question,
                          String provider, String model, boolean webSearchEnabled, String runId) {
        if (runId != null) outboxWriter.reserve(runId, workspaceId, userId, "query");
        QueryRun run = runId != null
                ? queryRunStore.createWithId(runId, workspaceId, sessionId, provider, model, webSearchEnabled, question)
                : webSearchEnabled
                ? queryRunStore.create(workspaceId, sessionId, provider, model, true, question)
                : queryRunStore.create(workspaceId, sessionId, provider, model, question);
        log.info("[질의 run 생성] requestId={} sessionId={} questionLength={}",
                run.requestId(), sessionId, question.length());
        outboxWriter.begin(run.requestId(), workspaceId, userId, "query");
        QueryService.QueryMessageContext messageContext = webSearchEnabled
                ? queryService.prepareMessages(sessionId, question, run.requestId(), provider, model, true)
                : queryService.prepareMessages(sessionId, question, run.requestId(), provider, model);
        outboxWriter.enqueue(run.requestId(), commandTopic, sessionId,
                new QueryCommand(run.requestId(), "query", workspaceId, userId, sessionId,
                        question, provider, model, webSearchEnabled,
                        chatSessionService.contextSummary(sessionId),
                        messageContext.recentMessages(), messageContext));
        log.info("[질의 command 등록 완료] requestId={}", run.requestId());
        return run;
    }

    public fruition.core.query.dto.QueryResponse awaitResult(QueryRun started, String userId) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            while (System.nanoTime() < deadline) {
                QueryRun current = queryRunStore.find(started.requestId()).orElseThrow();
                switch (current.status()) {
                    case COMPLETED -> { return current.result(); }
                    case CANCELLED -> throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.CONFLICT, "취소된 질의입니다.");
                    case FAILED -> throw new fruition.core.query.exception.PipelineQueryException(
                            "PIPELINE_UNAVAILABLE", current.errorMessage(), 503, null);
                    default -> Thread.sleep(100);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cancellation.cancel(started.requestId(), started.workspaceId(), userId);
        throw new fruition.core.query.exception.PipelineQueryException(
                "PIPELINE_TIMEOUT", "질의 대기 시간이 지나 취소를 요청했습니다.", 503, null);
    }

    record QueryCommand(
            @JsonProperty("run_id") String runId,
            String kind,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("session_id") String sessionId,
            String question,
            String provider,
            String model,
            @JsonProperty("allow_web_search") boolean webSearchEnabled,
            /** 세션에 쌓인 누적 대화 요약. recent_messages가 담지 못하는 앞쪽 맥락을 이어준다. */
            @JsonProperty("recent_conversation_summary") String recentConversationSummary,
            @JsonProperty("recent_messages") java.util.List<QueryService.RecentMessage> recentMessages,
            @JsonProperty("message_context") QueryService.QueryMessageContext messageContext
    ) {}
}
