package fruition.core.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.agent.dto.AgentTurnRequest;
import fruition.core.agent.dto.AgentTurnResponse;
import fruition.core.agent.dto.AgentRunApproveRequest;
import fruition.core.agent.dto.AgentRunReviseRequest;
import fruition.core.agent.exception.AgentRunNotFoundException;
import fruition.core.agent.exception.InvalidAgentTurnRequestException;
import fruition.core.agent.repository.AgentRunCommandRepository;
import fruition.core.agent.repository.PipelineAgentRunStatusRequester;
import fruition.core.aihistory.service.AgentApplyOperationStore;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.chat.service.ChatConversationReader;
import fruition.core.chat.service.ChatSessionService;
import fruition.core.chat.service.ChatTurnRecorder;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.document.dto.DocumentDetailResponse;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.core.document.service.DocumentEditLockService;
import fruition.shared.ai.AiModelCatalog;
import fruition.core.document.service.DocumentService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AgentTurnService {

    private static final Set<String> TARGET_TYPES = Set.of("selection", "current_section", "whole_document");
    private static final Set<String> AUTONOMOUS_ACTIONS = Set.of("folder_organize", "workspace_workflow");
    private static final int MAX_SKILL_DRAFT_SOURCES = 3;
    private static final Pattern RUN_ID_PATTERN = Pattern.compile("agent_[0-9a-f]{32}");

    private final DocumentService documentService;
    private final DocumentEditLockService editLockService;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final AgentRunCommandRepository runRepository;
    private final PipelineAgentRunStatusRequester statusRequester;
    private final AiCommandOutboxWriter outboxWriter;
    private final AgentApplyOperationStore applyOperationStore;
    private final AiModelCatalog aiModelCatalog;
    private final ChatSessionService chatSessionService;
    private final ChatConversationReader chatConversationReader;
    private final ChatTurnRecorder chatTurnRecorder;
    private final String commandTopic;
    private final fruition.core.aitask.service.AiTaskCancellationService cancellation;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public AgentTurnService(DocumentService documentService,
                            DocumentEditLockService editLockService,
                            WorkspaceAccessGuard workspaceAccessGuard,
                            AgentRunCommandRepository runRepository,
                            PipelineAgentRunStatusRequester statusRequester,
                            AiCommandOutboxWriter outboxWriter,
                            AgentApplyOperationStore applyOperationStore,
                            AiModelCatalog aiModelCatalog,
                            ChatSessionService chatSessionService,
                            ChatConversationReader chatConversationReader,
                            ChatTurnRecorder chatTurnRecorder,
                            @Value("${app.agent.command-topic}") String commandTopic,
                            fruition.core.aitask.service.AiTaskCancellationService cancellation,
                            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.documentService = documentService;
        this.editLockService = editLockService;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.runRepository = runRepository;
        this.statusRequester = statusRequester;
        this.outboxWriter = outboxWriter;
        this.applyOperationStore = applyOperationStore;
        this.aiModelCatalog = aiModelCatalog;
        this.chatSessionService = chatSessionService;
        this.chatConversationReader = chatConversationReader;
        this.chatTurnRecorder = chatTurnRecorder;
        this.commandTopic = commandTopic;
        this.cancellation = cancellation;
        this.mapper = mapper;
    }

    @Transactional
    public AgentTurnResponse turn(String workspaceId, String userId, AgentTurnRequest request) {
        chatSessionService.verifyOwnedSession(workspaceId, userId, request.sessionId());
        // 문서를 열지 않은 턴은 적용할 대상이 없어 편집 전제 검사를 하지 않는다.
        // AI는 이 경우 chat_answer·conversation_reply·clarify·reject만 낼 수 있다.
        if (request.hasDocumentContext()) {
            DocumentDetailResponse document = documentService.findById(workspaceId, userId, request.documentId());
            if (!isMarkdown(document)) {
                throw new InvalidAgentTurnRequestException("Markdown 문서만 Agent 편집을 요청할 수 있습니다.");
            }
            // 다른 사용자가 편집 중이면 pipeline 호출 전에 423으로 거절한다.
            editLockService.requireWritable(request.documentId(), userId);
            // 오래된 snapshot(baseVersion)이면 pipeline 호출 전에 충돌로 거절해 LLM 낭비를 막는다.
            // 본문 편집 기준 version은 edit_revision이다(없으면 current_version과 같다).
            if (document.editRevision() != request.baseVersion()) {
                throw new DocumentVersionConflictException(
                        "문서가 이미 변경되어 오래된 버전으로 편집을 요청할 수 없습니다.");
            }
            validateTarget(request.editorSnapshot());
        } else {
            workspaceAccessGuard.requireMember(workspaceId, userId);
        }
        AiModelCatalog.AiModel selectedModel = aiModelCatalog.resolve(request.provider(), request.model());
        List<CanonicalSkillDraftSource> skillDraftSources = canonicalSkillDraftSources(
                workspaceId, userId, request.skillDraftSources());

        String runId = "agent_" + UUID.randomUUID().toString().replace("-", "");
        outboxWriter.begin(runId, workspaceId, userId, "agent");
        // 편집안을 적용할 때 되돌려받을 표. source=agent 문자열 대신 이 값으로 AI 작업 여부를 가린다.
        // 적용할 문서가 없으면 표도 만들지 않는다.
        String applyOperationId = request.hasDocumentContext() ? applyOperationStore.newOperationId() : null;
        runRepository.create(runId, workspaceId, userId, request.documentId(),
                request.baseVersion(), applyOperationId);
        // 질의와 같은 방식으로 말풍선을 먼저 만들고 결과가 오면 채운다. command와 같은 트랜잭션이라
        // 발행에 실패하면 메시지도 남지 않는다.
        AgentMessageContext messageContext = new AgentMessageContext(
                UUID.randomUUID().toString(),
                "chat_user_" + UUID.randomUUID(),
                "chat_assistant_" + UUID.randomUUID());
        chatTurnRecorder.createPendingAgentPair(request.sessionId(), messageContext.pairId(),
                messageContext.userMessageId(), messageContext.assistantMessageId(), request.message(),
                java.time.Instant.now(), selectedModel.provider(), selectedModel.model(), runId);
        // 질의와 같이 sessionId를 key로 쓴다. 두 갈래가 같은 세션에 쌓이고 세션 단위 누적 요약을
        // 함께 갱신하므로, 한 세션의 턴은 같은 파티션에서 보낸 순서대로 처리돼야 한다.
        outboxWriter.enqueue(runId, commandTopic, request.sessionId(),
                new AgentCommand(runId, "agent", workspaceId, userId, request.sessionId(), messageContext,
                        request.documentId(),
                        request.baseVersion(), applyOperationId, request.message(),
                        selectedModel.provider(), selectedModel.model(), request.allowWebSearch(),
                        request.skillMode(), request.skillId(),
                        CommandConversationContext.from(request.conversationContext(),
                                chatConversationReader.read(request.sessionId(), selectedPairIds(request)),
                                runIdHint -> canonicalAgentRoute(workspaceId, userId, runIdHint)),
                        skillDraftSources,
                        request.skillDraftUserDirectives(), request.skillDraftExcludedLiterals(),
                        request.skillScopeType(),
                        CommandEditorSnapshot.from(request.editorSnapshot())));
        return new AgentTurnResponse(
                request.documentId(),
                request.baseVersion(),
                runId,
                applyOperationId,
                "queued",
                null,
                null
        );
    }

    private static List<String> selectedPairIds(AgentTurnRequest request) {
        if (request.conversationContext() == null || request.conversationContext().selectedPairIds() == null) {
            return List.of();
        }
        return request.conversationContext().selectedPairIds();
    }

    /** 저장된 완료 결과의 route만 이전 턴 힌트로 사용한다. */
    private JsonNode canonicalAgentRoute(String workspaceId, String userId, String runId) {
        if (runId == null) return null;
        return runRepository.find(workspaceId, userId, runId)
                .filter(run -> "ready".equals(run.status()) || "consumed".equals(run.status()))
                .map(AgentRunCommandRepository.RunView::result)
                .filter(result -> result != null && result.isObject())
                .map(result -> result.get("route"))
                .filter(route -> route != null && route.isObject())
                .orElse(null);
    }

    /**
     * 이 run을 볼 자격이 있는지만 확인한다. 진행 이벤트 구독은 pipeline 없이도 흘려보낼 수 있으므로
     * 결과 조회와 달리 외부 호출을 하지 않는다 — pipeline이 잠깐 멈춰도 구독은 열려야 한다.
     */
    public void verifyRunAccess(String workspaceId, String userId, String runId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        if (!RUN_ID_PATTERN.matcher(runId).matches()) {
            throw new InvalidAgentTurnRequestException("Agent run ID 형식이 올바르지 않습니다.");
        }
        cancellation.findStatus(runId, workspaceId, userId)
                .orElseThrow(() -> new AgentRunNotFoundException(runId));
    }

    public AgentTurnResponse get(String workspaceId, String userId, String runId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        if (!RUN_ID_PATTERN.matcher(runId).matches()) {
            throw new InvalidAgentTurnRequestException("Agent run ID 형식이 올바르지 않습니다.");
        }
        var cancelling = cancellation.findStatus(runId, workspaceId, userId)
                .filter(row -> Set.of("cancel_requested", "rolling_back", "rollback_failed", "cancelled").contains(row.get("status")));
        if (cancelling.isPresent()) {
            var row = cancelling.get();
            return new AgentTurnResponse(null, null, runId, null, (String) row.get("status"), null, (String) row.get("error_code"));
        }
        var aiRun = statusRequester.find(workspaceId, userId, runId);
        if (aiRun.isPresent()) {
            var run = aiRun.get();
            if ("completed".equals(run.status())) {
                var projection = runRepository.find(workspaceId, userId, runId);
                if (projection.isPresent()) {
                    var core = projection.get();
                    return new AgentTurnResponse(core.documentId(), core.baseVersion(), core.runId(),
                            core.applyOperationId(), publicStatus(core.status()),
                            core.result(), core.error());
                }
            }
            return new AgentTurnResponse(run.documentId(), run.baseVersion(), run.id(),
                    run.applyOperationId(), run.status(), run.result(), run.errorCode());
        }
        var projection = runRepository.find(workspaceId, userId, runId)
                .orElseThrow(() -> new AgentRunNotFoundException(runId));
        return new AgentTurnResponse(projection.documentId(), projection.baseVersion(), projection.runId(),
                projection.applyOperationId(), publicStatus(projection.status()),
                projection.result(), projection.error());
    }

    public JsonNode getRun(String workspaceId, String userId, String runId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        return statusRequester.getAutonomousRun(workspaceId, userId, runId);
    }

    public JsonNode approve(String workspaceId, String userId, String runId,
                            AgentRunApproveRequest request) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        return statusRequester.approve(workspaceId, userId, runId,
                request.planVersion(), request.operationHash());
    }

    public JsonNode reject(String workspaceId, String userId, String runId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        return statusRequester.reject(workspaceId, userId, runId);
    }

    public JsonNode cancel(String workspaceId, String userId, String runId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        if (cancellation.findStatus(runId, workspaceId, userId).isPresent()) {
            return mapper.valueToTree(cancellation.cancel(runId, workspaceId, userId));
        }
        return statusRequester.cancel(workspaceId, userId, runId);
    }

    public JsonNode revise(String workspaceId, String userId, String runId,
                           AgentRunReviseRequest request) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        return statusRequester.revise(workspaceId, userId, runId, request.instruction());
    }

    private String publicStatus(String status) {
        return "ready".equals(status) || "consumed".equals(status) ? "completed" : status;
    }

    private boolean isMarkdown(DocumentDetailResponse document) {
        return "text/markdown".equalsIgnoreCase(document.mimeType())
                || document.filename().toLowerCase().endsWith(".md")
                || document.filename().toLowerCase().endsWith(".markdown");
    }

    private void validateTarget(AgentTurnRequest.EditorSnapshot snapshot) {
        AgentTurnRequest.Target target = snapshot.target();
        if (target == null) return;
        int lineCount = snapshot.markdown().split("\\n", -1).length;
        if (!TARGET_TYPES.contains(target.type())
                || target.endLine() < target.startLine()
                || target.endLine() > lineCount) {
            throw new InvalidAgentTurnRequestException("Markdown 편집 범위가 올바르지 않습니다.");
        }
    }

    private List<CanonicalSkillDraftSource> canonicalSkillDraftSources(
            String workspaceId, String userId, List<AgentTurnRequest.SkillDraftSourceSelector> requestedSources) {
        if (requestedSources == null || requestedSources.isEmpty()) return List.of();
        if (requestedSources.size() > MAX_SKILL_DRAFT_SOURCES) {
            throw new InvalidAgentTurnRequestException("Skill draft source는 최대 3개까지 선택할 수 있습니다.");
        }
        Set<String> seenRunIds = new HashSet<>();
        List<CanonicalSkillDraftSource> sources = new ArrayList<>();
        for (AgentTurnRequest.SkillDraftSourceSelector requested : requestedSources) {
            if (requested == null || !seenRunIds.add(requested.runId())) {
                throw new InvalidAgentTurnRequestException("Skill draft AgentRun 선택이 올바르지 않습니다.");
            }
            var run = statusRequester.findAutonomous(workspaceId, userId, requested.runId())
                    .orElseThrow(() -> new InvalidAgentTurnRequestException(
                            "접근 가능한 Skill draft AgentRun을 찾을 수 없습니다."));
            if (!requested.runId().equals(run.id()) || !AUTONOMOUS_ACTIONS.contains(run.action())) {
                throw new InvalidAgentTurnRequestException("Skill draft AgentRun 선택이 올바르지 않습니다.");
            }
            if (!"completed".equals(run.status())) {
                throw new InvalidAgentTurnRequestException("완료된 AgentRun만 Skill draft에 사용할 수 있습니다.");
            }
            sources.add(canonicalSkillDraftSource(run));
        }
        return List.copyOf(sources);
    }

    private CanonicalSkillDraftSource canonicalSkillDraftSource(
            PipelineAgentRunStatusRequester.AutonomousRun run) {
        if (run.requestSummary() == null || run.requestSummary().isBlank()
                || run.plan() == null || run.plan().summary() == null || run.plan().summary().isBlank()) {
            throw new InvalidAgentTurnRequestException("Skill draft canonical source가 올바르지 않습니다.");
        }
        List<CanonicalSkillDraftOperation> operations = new ArrayList<>();
        if (run.plan().operations() != null) {
            for (var operation : run.plan().operations()) {
                if (operation == null || !"succeeded".equals(operation.status())) continue;
                if (operation.toolName() == null || operation.toolName().isBlank()
                        || operation.reason() == null || operation.reason().isBlank()) {
                    throw new InvalidAgentTurnRequestException("Skill draft operation 결과가 올바르지 않습니다.");
                }
                operations.add(new CanonicalSkillDraftOperation(
                        operation.toolName().trim(), operation.reason().trim()));
            }
        }
        if (operations.isEmpty()) {
            throw new InvalidAgentTurnRequestException("성공한 operation이 있는 AgentRun만 Skill draft에 사용할 수 있습니다.");
        }
        return new CanonicalSkillDraftSource(run.id(), "completed", run.requestSummary().trim(),
                run.plan().summary().trim(), List.copyOf(operations));
    }

    /** 결과가 왔을 때 어느 말풍선을 채울지 알기 위해 command와 함께 실어 되받는다. */
    record AgentMessageContext(
            @com.fasterxml.jackson.annotation.JsonProperty("pair_id") String pairId,
            @com.fasterxml.jackson.annotation.JsonProperty("user_message_id") String userMessageId,
            @com.fasterxml.jackson.annotation.JsonProperty("assistant_message_id") String assistantMessageId
    ) {}

    record AgentCommand(
            @com.fasterxml.jackson.annotation.JsonProperty("run_id") String runId,
            String kind,
            @com.fasterxml.jackson.annotation.JsonProperty("workspace_id") String workspaceId,
            @com.fasterxml.jackson.annotation.JsonProperty("user_id") String userId,
            @com.fasterxml.jackson.annotation.JsonProperty("session_id") String sessionId,
            @com.fasterxml.jackson.annotation.JsonProperty("message_context") AgentMessageContext messageContext,
            @com.fasterxml.jackson.annotation.JsonProperty("document_id") String documentId,
            @com.fasterxml.jackson.annotation.JsonProperty("base_version") Long baseVersion,
            @com.fasterxml.jackson.annotation.JsonProperty("apply_operation_id") String applyOperationId,
            String message,
            String provider,
            String model,
            @com.fasterxml.jackson.annotation.JsonProperty("allow_web_search") Boolean allowWebSearch,
            @com.fasterxml.jackson.annotation.JsonProperty("skill_mode") String skillMode,
            @com.fasterxml.jackson.annotation.JsonProperty("skill_id") String skillId,
            @com.fasterxml.jackson.annotation.JsonProperty("conversation_context") CommandConversationContext conversationContext,
            @com.fasterxml.jackson.annotation.JsonProperty("skill_draft_sources") List<CanonicalSkillDraftSource> skillDraftSources,
            @com.fasterxml.jackson.annotation.JsonProperty("skill_draft_user_directives") List<String> skillDraftUserDirectives,
            @com.fasterxml.jackson.annotation.JsonProperty("skill_draft_excluded_literals") List<String> skillDraftExcludedLiterals,
            @com.fasterxml.jackson.annotation.JsonProperty("skill_scope_type") String skillScopeType,
            @com.fasterxml.jackson.annotation.JsonProperty("editor_snapshot") CommandEditorSnapshot editorSnapshot
    ) {}

    record CanonicalSkillDraftSource(
            @com.fasterxml.jackson.annotation.JsonProperty("run_id") String runId,
            String status,
            @com.fasterxml.jackson.annotation.JsonProperty("request_summary") String requestSummary,
            @com.fasterxml.jackson.annotation.JsonProperty("plan_summary") String planSummary,
            @com.fasterxml.jackson.annotation.JsonProperty("successful_operations")
            List<CanonicalSkillDraftOperation> successfulOperations
    ) {}

    record CanonicalSkillDraftOperation(
            @com.fasterxml.jackson.annotation.JsonProperty("tool_name") String toolName,
            String reason
    ) {}

    record CommandConversationContext(
            @com.fasterxml.jackson.annotation.JsonProperty("recent_conversation_summary") String recentConversationSummary,
            @com.fasterxml.jackson.annotation.JsonProperty("recent_messages") List<CommandConversationMessage> recentMessages,
            @com.fasterxml.jackson.annotation.JsonProperty("reference_context") java.util.Map<String, Object> referenceContext,
            @com.fasterxml.jackson.annotation.JsonProperty("pending_skill_proposal") CommandPendingSkillProposal pendingSkillProposal
    ) {
        /** 대화 내용은 서버가 읽은 것을 쓰고, 참조·Skill 제안은 클라이언트 상태를 그대로 옮긴다. */
        static CommandConversationContext from(AgentTurnRequest.ConversationContext context,
                                               ChatConversationReader.Conversation conversation,
                                               java.util.function.Function<String, JsonNode> routeResolver) {
            return new CommandConversationContext(
                    conversation.summary(),
                    conversation.recentMessages().stream()
                            .map(message -> new CommandConversationMessage(
                                    message.role(), message.content(), message.action(), message.runId(),
                                    "assistant".equals(message.role())
                                            ? routeResolver.apply(message.runId())
                                            : null))
                            .toList(),
                    context == null ? null : context.referenceContext(),
                    context == null ? null : CommandPendingSkillProposal.from(context.pendingSkillProposal()));
        }
    }

    record CommandConversationMessage(
            String role,
            String content,
            String action,
            @com.fasterxml.jackson.annotation.JsonProperty("run_id") String runId,
            @com.fasterxml.jackson.annotation.JsonProperty("agent_route") JsonNode agentRoute
    ) {}

    record CommandPendingSkillProposal(
            @com.fasterxml.jackson.annotation.JsonProperty("scope_type") String scopeType,
            String name,
            String description,
            @com.fasterxml.jackson.annotation.JsonProperty("instructions_markdown") String instructionsMarkdown,
            java.util.List<String> capabilities,
            @com.fasterxml.jackson.annotation.JsonProperty("allowed_tools") java.util.List<String> allowedTools
    ) {
        static CommandPendingSkillProposal from(AgentTurnRequest.ConversationContext.PendingSkillProposal proposal) {
            return proposal == null ? null
                    : new CommandPendingSkillProposal(proposal.scopeType(), proposal.name(), proposal.description(),
                    proposal.instructionsMarkdown(), proposal.capabilities(), proposal.allowedTools());
        }
    }

    record CommandEditorSnapshot(String markdown, CommandTarget target) {
        static CommandEditorSnapshot from(AgentTurnRequest.EditorSnapshot snapshot) {
            // 문서를 열지 않은 턴은 snapshot이 없다.
            if (snapshot == null) {
                return null;
            }
            return new CommandEditorSnapshot(snapshot.markdown(), CommandTarget.from(snapshot.target()));
        }
    }

    record CommandTarget(
            String type,
            @com.fasterxml.jackson.annotation.JsonProperty("start_line") int startLine,
            @com.fasterxml.jackson.annotation.JsonProperty("end_line") int endLine
    ) {
        static CommandTarget from(AgentTurnRequest.Target target) {
            return target == null ? null : new CommandTarget(target.type(), target.startLine(), target.endLine());
        }
    }
}
