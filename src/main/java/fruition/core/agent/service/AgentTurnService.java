package fruition.core.agent.service;

import fruition.shared.i18n.ResponseLanguage;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.agent.dto.AgentRunEditPreviewsResponse;
import fruition.core.agent.dto.AgentTurnRequest;
import fruition.core.agent.dto.AgentTurnResponse;
import fruition.core.agent.dto.AgentRunApproveRequest;
import fruition.core.agent.dto.AgentRunReviseRequest;
import fruition.core.agent.exception.AgentRunNotFoundException;
import fruition.core.agent.exception.AgentTurnAlreadyFinishedException;
import fruition.core.agent.exception.InvalidAgentTurnRequestException;
import fruition.core.agent.repository.AgentRunCommandRepository;
import fruition.core.agent.repository.PipelineAgentRunStatusRequester;
import fruition.core.aihistory.service.AgentApplyOperationStore;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.chat.service.ChatConversationReader;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.service.ChatSessionService;
import fruition.core.chat.service.ChatTurnRecorder;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.document.dto.DocumentDetailResponse;
import fruition.core.document.exception.HierarchyItemNotFoundException;
import fruition.core.document.repository.FolderRepository;
import fruition.core.document.service.DocumentEditLockService;
import fruition.shared.ai.AiModelCatalog;
import fruition.core.document.service.DocumentService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AgentTurnService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentTurnService.class);


    @org.springframework.beans.factory.annotation.Autowired
    private fruition.core.analytics.DocumentAnalytics analytics;

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
    private final fruition.core.usage.service.UsageChargeService usageCharges;
    private final FolderRepository folderRepository;
    private final TransactionTemplate transaction;
    private AgentAutoApprovalService autoApproval;

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
                            com.fasterxml.jackson.databind.ObjectMapper mapper,
                            fruition.core.usage.service.UsageChargeService usageCharges,
                            FolderRepository folderRepository,
                            TransactionTemplate transaction) {
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
        this.usageCharges = usageCharges;
        this.folderRepository = folderRepository;
        this.transaction = transaction;
    }

    /** 자동 승인은 선택 기능이다. 없으면 모든 계획을 사용자가 직접 승인한다. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAutoApproval(AgentAutoApprovalService autoApproval) {
        this.autoApproval = autoApproval;
    }

    /**
     * 검증(문서 상세의 AI 위키 맥락 조회, skill draft run 조회, 권한 확인 포함)은 트랜잭션 밖에서 하고,
     * 말풍선·실행 등록·command 적재만 한 트랜잭션에 둔다. 검증은 잠금 없는 읽기라 순서와 오류는 그대로다.
     */
    public AgentTurnResponse turn(String workspaceId, String userId, AgentTurnRequest request) {
        // 소유권 확인이 읽은 세션을 재사용해 제목을 판단한다. 세션 조회를 더하지 않는다.
        boolean sessionTitleNeeded = ChatSession.isTitleUnset(
                chatSessionService.verifyOwnedSession(workspaceId, userId, request.sessionId()).getTitle());
        // 문서를 열지 않은 턴은 적용할 대상이 없어 편집 전제 검사를 하지 않는다.
        // AI는 이 경우 chat_answer·conversation_reply·clarify·reject만 낼 수 있다.
        // 작업 탭 턴(mode=task)은 문서 없이 폴더 정리·워크플로를 낼 수 있고, 문서 편집이면 AI가 되묻는다.
        // 답변은 첨부(reference_document_ids)가 있는 작업 탭 턴에서만 나온다.
        if (request.hasDocumentContext()) {
            DocumentDetailResponse document = documentService.findById(workspaceId, userId, request.documentId());
            if (!isMarkdown(document)) {
                throw new InvalidAgentTurnRequestException("Markdown 문서만 Agent 편집을 요청할 수 있습니다.");
            }
            // 다른 사용자가 편집 중이면 pipeline 호출 전에 423으로 거절한다.
            // 첨부 수정도 열린 문서 잠금으로 막히므로 어느 문서 때문인지 응답에 담는다.
            editLockService.requireWritableNamingDocument(workspaceId, userId, request.documentId());
            // 오래된 snapshot(baseVersion)이어도 거절하지 않는다. 적용 저장이 baseVersion 스냅샷 기준으로
            // 그 사이 변경과 자동 병합한다(ADR 0032).
            validateTarget(request.editorSnapshot());
        } else {
            workspaceAccessGuard.requireMember(workspaceId, userId);
        }
        UUID folderId = targetFolderId(workspaceId, request.folderId());
        AiModelCatalog.AiModel selectedModel = aiModelCatalog.resolve(request.provider(), request.model());
        List<CanonicalSkillDraftSource> skillDraftSources = canonicalSkillDraftSources(
                workspaceId, userId, request.skillDraftSources());
        requireUsableReferences(workspaceId, userId, request.referenceDocumentIds());
        String readOnlyDocumentId = confirmedReadOnlyDocumentId(workspaceId, userId, request);
        AgentTurnResponse queued = transaction.execute(status ->
                queueTurn(workspaceId, userId, request, folderId, selectedModel, skillDraftSources, readOnlyDocumentId,
                        sessionTitleNeeded));
        log.info("[{} 접수] operation={} stage=accepted runId={} applyOperationId={} workspaceId={} userId={} sessionId={} documentId={} skillMode={} skillId={} provider={} 설명=실행 명령을 등록했습니다. 결과는 별도 로그로 남습니다.",
                "explicit".equals(request.skillMode()) ? "스킬 실행" : "Agent 요청",
                "explicit".equals(request.skillMode()) ? "skill_run" : "agent_turn", queued.requestId(), queued.applyOperationId(),
                workspaceId, userId, request.sessionId(), request.documentId(), request.skillMode(), request.skillId(), selectedModel.provider());
        return queued;
    }

    /** 조용히 빼면 근거 없이 답하게 되므로 하나라도 어긋나면 거절한다. 사유를 나누지 않아 존재 여부가 응답으로 새지 않는다. */
    private void requireUsableReferences(String workspaceId, String userId, List<String> documentIds) {
        if (!documentIds.isEmpty() && !documentService.canUseAsAgentReferences(workspaceId, userId, documentIds)) {
            throw new InvalidAgentTurnRequestException("고른 문서 중 사용할 수 없는 문서가 있습니다. 다시 골라 주세요.");
        }
    }

    /**
     * 메시지에 남길 보기 전용 문서. 클라이언트 값을 믿지 않고 서버가 확인한 것만 남기며, 맞지 않으면 오류 없이 버려 존재 여부가 응답으로 새지 않는다.
     * 편집 문맥이 있는 요청은 보기 전용 문서를 연 요청이 아니다. 안내 표시와 AI의 "이 문서" 해석에 쓰고 권한 판단에는 쓰지 않는다.
     */
    private String confirmedReadOnlyDocumentId(String workspaceId, String userId, AgentTurnRequest request) {
        String documentId = request.readOnlyDocumentId();
        if (documentId == null || request.hasDocumentContext()) {
            return null;
        }
        return documentService.isReadOnlyFor(workspaceId, userId, documentId) ? documentId : null;
    }

    private AgentTurnResponse queueTurn(String workspaceId, String userId, AgentTurnRequest request, UUID folderId,
                                        AiModelCatalog.AiModel selectedModel,
                                        List<CanonicalSkillDraftSource> skillDraftSources,
                                        String readOnlyDocumentId, boolean sessionTitleNeeded) {
        String runId = "agent_" + UUID.randomUUID().toString().replace("-", "");
        // 질의와 같은 방식으로 말풍선을 먼저 만들고 결과가 오면 채운다. command와 같은 트랜잭션이라
        // 발행에 실패하면 메시지도 남지 않는다.
        // 실행 등록(begin) 전에 만들어 취소 복구 기록에 넣지 않는다. 취소하면 복구가 지우는 대신
        // 말풍선을 취소로 닫아 대화에 남긴다(AiTaskCancellationService.requestAgentTurnCancel).
        AgentMessageContext messageContext = new AgentMessageContext(
                UUID.randomUUID().toString(),
                "chat_user_" + UUID.randomUUID(),
                "chat_assistant_" + UUID.randomUUID());
        java.time.Instant requestedAt = java.time.Instant.now();
        chatTurnRecorder.createPendingAgentPair(request.sessionId(), messageContext.pairId(),
                messageContext.userMessageId(), messageContext.assistantMessageId(), request.message(),
                requestedAt, selectedModel.provider(), selectedModel.model(), runId, readOnlyDocumentId);
        outboxWriter.begin(runId, workspaceId, userId, "agent");
        // 편집안을 적용할 때 되돌려받을 표. source=agent 문자열 대신 이 값으로 AI 작업 여부를 가린다.
        // 적용할 문서가 없으면 표도 만들지 않는다.
        String applyOperationId = request.hasDocumentContext() ? applyOperationStore.newOperationId() : null;
        runRepository.create(runId, workspaceId, userId, request.documentId(),
                request.baseVersion(), applyOperationId, folderId);
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
                        CommandEditorSnapshot.from(request.editorSnapshot()),
                        request.mode(),
                        folderId == null ? null : folderId.toString(),
                        request.referenceDocumentIds(), sessionTitleNeeded, readOnlyDocumentId,
                        ResponseLanguage.current().code()));
        if (analytics != null) {
            analytics.queryStarted(runId, userId, workspaceId, request.sessionId(), requestedAt, "task");
            if ("explicit".equals(request.skillMode()))
                analytics.explicitSkillRun(runId, request.skillId(), userId, workspaceId, requestedAt);
        }
        return new AgentTurnResponse(
                request.documentId(),
                request.baseVersion(),
                runId,
                applyOperationId,
                "queued",
                null,
                null,
                folderId == null ? null : folderId.toString()
        );
    }

    /** 대상 폴더는 이 워크스페이스의 살아 있는 폴더여야 한다. 생략하면 지정하지 않은 것(루트)이다. */
    private UUID targetFolderId(String workspaceId, String folderId) {
        if (folderId == null) {
            return null;
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(folderId);
        } catch (IllegalArgumentException e) {
            throw new InvalidAgentTurnRequestException("folder_id 형식이 올바르지 않습니다.");
        }
        folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(parsed, workspaceId)
                .orElseThrow(() -> new HierarchyItemNotFoundException("대상 폴더를 찾을 수 없습니다."));
        return parsed;
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
        // 대상 폴더는 document만 기록하므로 취소·AI 상태로 답할 때도 projection에서 읽는다.
        var projection = runRepository.find(workspaceId, userId, runId);
        String folderId = projection.map(AgentRunCommandRepository.RunView::folderId).orElse(null);
        if (cancelling.isPresent()) {
            var row = cancelling.get();
            return new AgentTurnResponse(null, null, runId, null, (String) row.get("status"), null,
                    (String) row.get("error_code"), folderId);
        }
        var aiRun = statusRequester.find(workspaceId, userId, runId);
        if (aiRun.isPresent()) {
            var run = aiRun.get();
            if ("completed".equals(run.status()) && projection.isPresent()) {
                var core = projection.get();
                return new AgentTurnResponse(core.documentId(), core.baseVersion(), core.runId(),
                        core.applyOperationId(), publicStatus(core.status()),
                        core.result(), core.error(), folderId);
            }
            return new AgentTurnResponse(run.documentId(), run.baseVersion(), run.id(),
                    run.applyOperationId(), run.status(), run.result(), run.errorCode(), folderId);
        }
        var core = projection.orElseThrow(() -> new AgentRunNotFoundException(runId));
        return new AgentTurnResponse(core.documentId(), core.baseVersion(), core.runId(),
                core.applyOperationId(), publicStatus(core.status()),
                core.result(), core.error(), folderId);
    }

    /**
     * 끝나지 않은 Agent turn을 취소한다. 조회와 같이 요청자 본인의 turn만 대상이다.
     * 취소 요청이 확정되면 늦게 도착한 결과는 반영되지 않고 처리 중 말풍선은 바로 취소로 닫힌다.
     * AI 쪽 중단·복구 확정은 기존 작업 취소 흐름이 이어서 처리하고, 응답은 그 시점의 turn 상태다
     * (cancel_requested·rolling_back·cancelled 등).
     */
    public AgentTurnResponse cancelTurn(String workspaceId, String userId, String runId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        if (!RUN_ID_PATTERN.matcher(runId).matches()) {
            throw new InvalidAgentTurnRequestException("Agent run ID 형식이 올바르지 않습니다.");
        }
        switch (cancellation.requestAgentTurnCancel(runId, workspaceId, userId)) {
            case NOT_FOUND -> throw new AgentRunNotFoundException(runId);
            case FINISHED -> throw new AgentTurnAlreadyFinishedException(runId);
            case REQUESTED -> { }
        }
        return get(workspaceId, userId, runId);
    }

    public JsonNode getRun(String workspaceId, String userId, String runId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        JsonNode run = statusRequester.getAutonomousRun(workspaceId, userId, runId);
        // 승인 대기 계획은 사용자의 자동 승인 설정을 지금 다시 적용해 본다. ai-svc의 계획 알림이 실패했거나
        // 알림 뒤에 설정을 켠 경우에도 화면이 계획을 열 때 판단한다.
        return closeIfTerminal(workspaceId, userId, runId, autoApproveIfAllowed(workspaceId, userId, runId, run));
    }

    /**
     * 첨부 문서 수정 계획의 변경 미리 보기. run 소유는 AI가 user_id로 가른다(남의 run은 404).
     * 계획을 세운 뒤 권한이 바뀌었을 수 있으므로 문서마다 지금 읽을 수 있는지 다시 보고, 제목은 현재 값으로 덮는다.
     */
    public AgentRunEditPreviewsResponse getEditPreviews(String workspaceId, String userId, String runId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        AgentRunEditPreviewsResponse previews = statusRequester.getEditPreviews(workspaceId, userId, runId);
        List<AgentRunEditPreviewsResponse.Item> items = previews.items().stream().filter(Objects::nonNull).toList();
        Map<String, String> titles = documentService.readableDocumentTitles(workspaceId, userId,
                items.stream().map(AgentRunEditPreviewsResponse.Item::documentId).toList());
        return new AgentRunEditPreviewsResponse(
                items.stream()
                        .filter(item -> titles.containsKey(item.documentId()))
                        .map(item -> item.withDisplayName(titles.get(item.documentId())))
                        .toList(),
                previews.expiresAt());
    }

    /**
     * ai-svc가 계획을 만들어 승인 대기에 들어갔을 때 부른다(내부 API). 사용자의 자동 승인 설정과 대상 권한을 보고
     * 승인 확인을 건너뛸 수 있으면 사용자 승인과 같은 경로(크레딧 예약 포함)로 승인한다. 아니면 그대로 둔다.
     * 계획 단계 사용량은 승인과 관계없이 지금 수집한다. 뒤의 조회·승인이 실패해도 잃지 않게 먼저 넣는다.
     */
    public JsonNode autoApprove(String workspaceId, String userId, String runId) {
        usageCharges.collectAgentPlan(runId);
        return getRun(workspaceId, userId, runId);
    }

    private JsonNode autoApproveIfAllowed(String workspaceId, String userId, String runId, JsonNode run) {
        if (autoApproval == null || run == null || !"awaiting_approval".equals(run.path("status").asText())) {
            return run;
        }
        AgentAutoApprovalPolicy.Decision decision = autoApproval.decide(workspaceId, userId, run);
        if (!decision.autoApprove()) {
            log.debug("Agent 계획 수동 승인 유지: run_id={} reason={}", runId, decision.reason());
            return run;
        }
        try {
            rerun(workspaceId, userId, runId, APPROVABLE, current -> () -> statusRequester.approveAutomatically(
                    workspaceId, userId, runId, decision.planVersion(), decision.operationHash(),
                    AgentAutoApprovalPolicy.AUTO_APPROVED_BY));
            log.info("[Agent 실행 자동 승인 전달] operation=agent_run stage=approve_forwarded runId={} userId={} version={} 설명=승인을 AI에 전달했습니다. 실행 완료가 아닙니다.",
                    runId, userId, decision.planVersion());
        } catch (RuntimeException e) {
            // 크레딧 부족·동시 승인(계획 변경)·ai-svc 오류. 자동 승인만 포기하고 사용자가 직접 승인하게 둔다.
            log.warn("[Agent 실행 자동 승인 실패] operation=agent_run stage=auto_approve retryable=true runId={} errorType={} message={} 원인=크레딧 부족·계획 변경·AI 오류 중 하나로 자동 승인하지 못해 수동 승인으로 남겼습니다.",
                    runId, e.getClass().getSimpleName(), e.getMessage());
        }
        // 승인 응답은 계획을 싣지 않으므로 화면에 보일 최신 run을 다시 읽는다.
        return statusRequester.getAutonomousRun(workspaceId, userId, runId);
    }

    public JsonNode approve(String workspaceId, String userId, String runId,
                            AgentRunApproveRequest request) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        // 고른 작업은 예약하기 전에 검사한다. 잘못된 선택이면 크레딧을 건드리지 않고 400으로 끝난다.
        JsonNode approved = rerun(workspaceId, userId, runId, APPROVABLE, current -> {
            List<String> operationIds = selectedOperationIds(current, request.operationIds());
            return () -> operationIds == null
                    ? statusRequester.approve(workspaceId, userId, runId, request.planVersion(), request.operationHash())
                    : statusRequester.approve(workspaceId, userId, runId, request.planVersion(),
                    request.operationHash(), operationIds);
        });
        logForwarded("[Agent 실행 승인 전달]", "approve_forwarded", runId, workspaceId, userId, approved);
        return approved;
    }

    /**
     * 부분 승인할 작업 ID를 현재 계획에 비춰 검사한다. null이면 전체 승인이다.
     * 계획의 모든 작업을 골랐으면 전체 승인과 같으므로 null을 돌려 기존 요청 모양으로 보낸다.
     * 고른 작업이 고르지 않은 작업의 결과에 기대면(depends_on) 실행할 수 없으므로 거절한다.
     */
    private static List<String> selectedOperationIds(JsonNode run, List<String> requested) {
        if (requested == null) {
            return null;
        }
        Set<String> selected = new java.util.LinkedHashSet<>(requested);
        if (selected.size() != requested.size()) {
            throw new InvalidAgentTurnRequestException("operation_ids에 중복된 작업이 있습니다.");
        }
        JsonNode operations = run == null ? null : run.path("plan").path("operations");
        if (operations == null || !operations.isArray() || operations.isEmpty()) {
            throw new InvalidAgentTurnRequestException("선택해 승인할 계획이 없습니다.");
        }
        Set<String> planned = new HashSet<>();
        for (JsonNode operation : operations) {
            planned.add(operation.path("id").asText());
        }
        if (!planned.containsAll(selected)) {
            throw new InvalidAgentTurnRequestException("계획에 없는 작업을 승인할 수 없습니다.");
        }
        for (JsonNode operation : operations) {
            if (!selected.contains(operation.path("id").asText())) continue;
            for (JsonNode dependency : operation.path("depends_on")) {
                if (!selected.contains(dependency.asText())) {
                    throw new InvalidAgentTurnRequestException("선택한 작업이 선택하지 않은 작업에 의존합니다.");
                }
            }
        }
        return selected.size() == planned.size() ? null : List.copyOf(selected);
    }

    public JsonNode reject(String workspaceId, String userId, String runId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        JsonNode rejected = closeIfTerminal(workspaceId, userId, runId, statusRequester.reject(workspaceId, userId, runId));
        logForwarded("[Agent 실행 거절 전달]", "reject_forwarded", runId, workspaceId, userId, rejected);
        return rejected;
    }

    public JsonNode cancel(String workspaceId, String userId, String runId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        if (cancellation.findStatus(runId, workspaceId, userId).isPresent()) {
            return mapper.valueToTree(cancellation.cancel(runId, workspaceId, userId));
        }
        JsonNode cancelled = closeIfTerminal(workspaceId, userId, runId, statusRequester.cancel(workspaceId, userId, runId));
        logForwarded("[Agent 실행 취소 전달]", "cancel_forwarded", runId, workspaceId, userId, cancelled);
        return cancelled;
    }

    public JsonNode revise(String workspaceId, String userId, String runId,
                           AgentRunReviseRequest request) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        JsonNode revised = rerun(workspaceId, userId, runId, REVISABLE,
                current -> () -> statusRequester.revise(workspaceId, userId, runId, request.instruction()));
        logForwarded("[Agent 실행 수정 전달]", "revise_forwarded", runId, workspaceId, userId, revised);
        return revised;
    }

    /** 전달 응답은 AI가 받았다는 뜻이다. runStatus가 끝난 상태일 때만 그 실행이 끝난 것이다. */
    private static void logForwarded(String title, String stage, String runId, String workspaceId, String userId, JsonNode run) {
        log.info("{} operation=agent_run stage={} runId={} workspaceId={} userId={} runStatus={}",
                title, stage, runId, workspaceId, userId, run == null ? null : run.path("status").asText(null));
    }

    /** ai-svc가 승인을 받는 run 상태({@code approve_and_enqueue}). */
    private static final Set<String> APPROVABLE = Set.of("awaiting_approval");
    /** ai-svc가 계획 수정을 받는 run 상태({@code revise}). */
    private static final Set<String> REVISABLE = Set.of("awaiting_approval", "clarification_required");

    /**
     * 소유 확인과 요청 검증 뒤 AgentRun 단위로 예약한다. 예약은 동시 요청·시간 초과된 이전 요청도 공유하므로,
     * 개별 전달 실패로 풀지 않는다. 종료 정산 또는 CreditService.releaseStale이 해제한다.
     */
    private JsonNode rerun(String workspaceId, String userId, String runId, Set<String> accepting,
                           java.util.function.Function<JsonNode, java.util.function.Supplier<JsonNode>> prepare) {
        JsonNode current = statusRequester.getAutonomousRun(workspaceId, userId, runId);
        // 현재 run으로 요청을 검사·준비한다(예약 전). 전달은 아래에서 한다.
        java.util.function.Supplier<JsonNode> forward = prepare.apply(current);
        // 이미 끝났거나 이 요청을 받지 않는 상태(예: 이미 승인돼 실행 중)면 ai-svc가 거절한다. 예약을 건드리지 않는다.
        // 여기서 예약하면 거절(409)을 받은 뒤 실행 중인 run의 예약을 풀게 된다(#167).
        if (current == null || !accepting.contains(current.path("status").asText())) {
            closeIfTerminal(workspaceId, userId, runId, current);
            return forward.get();
        }
        usageCharges.reserveAutonomousRun(runId, workspaceId, userId);
        try {
            return closeIfTerminal(workspaceId, userId, runId, forward.get());
        } catch (RuntimeException e) {
            // 이번 요청이 거절됐어도 이전 요청이 아직 커밋 전일 수 있다. run 단위 예약은 유지한다.
            reconcileFailedForward(workspaceId, userId, runId, e);
            throw e;
        }
    }

    /** 재조회는 종료 정산을 시작하는 데만 사용한다. 상태가 그대로여도 미처리라는 증거는 아니다. */
    private void reconcileFailedForward(String workspaceId, String userId, String runId, RuntimeException failure) {
        if (failure instanceof fruition.core.agent.exception.PipelineAgentNoResponseException) {
            log.warn("[Agent 전달 응답 없음] operation=agent_run stage=forward retryable=true runId={} 원인=AI 응답을 받지 못해 처리 여부를 알 수 없습니다. 크레딧 예약을 유지합니다.", runId);
            return;
        }
        try {
            closeIfTerminal(workspaceId, userId, runId, statusRequester.getAutonomousRun(workspaceId, userId, runId));
        } catch (RuntimeException unknown) {
            // 정산 확인 실패가 원래 전달 오류를 덮어쓰지 않도록 한다.
            log.warn("[Agent 전달 결과 확인 실패] operation=agent_run stage=forward retryable=true runId={} errorType={} error={} 원인=전달 실패 뒤 실행 상태를 다시 확인하지 못했습니다. 크레딧 예약을 유지합니다.", runId, unknown.getClass().getSimpleName(), unknown.toString());
        }
    }

    /** AgentRun이 끝났으면 크레딧 실행을 닫아 사용량 수집·정산(예약 해제)을 시작한다. */
    private JsonNode closeIfTerminal(String workspaceId, String userId, String runId, JsonNode run) {
        usageCharges.endAutonomousRunIfTerminal(runId, workspaceId, userId, run);
        return run;
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
            @com.fasterxml.jackson.annotation.JsonProperty("editor_snapshot") CommandEditorSnapshot editorSnapshot,
            // 작업 탭 턴이면 "task". 기존 턴은 command 모양을 바꾸지 않도록 키 자체를 싣지 않는다.
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String mode,
            // 작업 대상 폴더(폴더 정리 범위·새 문서 위치). 지정하지 않은 턴은 command 모양을 바꾸지 않도록 키를 싣지 않는다.
            @com.fasterxml.jackson.annotation.JsonProperty("folder_id")
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String folderId,
            @com.fasterxml.jackson.annotation.JsonProperty("reference_document_ids") List<String> referenceDocumentIds,
            // false면 사용자가 제목을 정한 세션이라 AI가 제목을 만들지 않는다.
            @com.fasterxml.jackson.annotation.JsonProperty("session_title_needed") boolean sessionTitleNeeded,
            // 서버가 확인한 보기 전용 문서. AI가 "이 문서"를 첨부로 오해해 고치지 않게 한다. 그 밖의 턴은 command 모양을 바꾸지 않도록 키를 싣지 않는다.
            @com.fasterxml.jackson.annotation.JsonProperty("read_only_document_id")
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String readOnlyDocumentId,
            // 요청자 화면 언어("ko"|"en"). AI가 답변·안내 문구의 언어로 쓴다. 승인형 run은 AI가 이 값을 run에 저장한다.
            @com.fasterxml.jackson.annotation.JsonProperty("response_language") String responseLanguage
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
            @com.fasterxml.jackson.annotation.JsonProperty("allowed_tools") java.util.List<String> allowedTools,
            // 생략(null)하면 키를 빼 AI가 판단하게 한다.
            @com.fasterxml.jackson.annotation.JsonProperty("requires_source")
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            Boolean requiresSource
    ) {
        static CommandPendingSkillProposal from(AgentTurnRequest.ConversationContext.PendingSkillProposal proposal) {
            return proposal == null ? null
                    : new CommandPendingSkillProposal(proposal.scopeType(), proposal.name(), proposal.description(),
                    proposal.instructionsMarkdown(), proposal.capabilities(), proposal.allowedTools(),
                    proposal.requiresSource());
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
