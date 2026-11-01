package fruition.core.wikimaintenance.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import fruition.core.aitask.AiErrorMessages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.aihistory.service.LintOperationStarter;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.document.repository.PipelineRunStatusRequester;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wikimaintenance.domain.WikiLintState;
import fruition.core.wikimaintenance.dto.WikiLintRequest;
import fruition.core.wikimaintenance.dto.WikiMaintenanceStatusResponse;
import fruition.core.wikimaintenance.exception.PipelineWikiMaintenanceException;
import fruition.core.wikimaintenance.repository.WikiLintStateRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspacePermissions;
import fruition.core.authz.WorkspaceAiModelClient;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class WikiMaintenanceService {

    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final LintOperationStarter operationStarter;
    private final AiCommandOutboxWriter outboxWriter;
    private final PipelineRunStatusRequester runStatusRequester;
    private final WikiLintStateRepository lintStateRepository;
    private final PipelineWikiStateRequester wikiStateRequester;
    private final ObjectMapper objectMapper;
    private final String commandTopic;
    private final WorkspaceAiModelClient workspaceAiModelClient;

    public WikiMaintenanceService(WorkspaceAccessGuard workspaceAccessGuard,
                                  LintOperationStarter operationStarter,
                                  AiCommandOutboxWriter outboxWriter,
                                  PipelineRunStatusRequester runStatusRequester,
                                  WikiLintStateRepository lintStateRepository,
                                  PipelineWikiStateRequester wikiStateRequester,
                                  ObjectMapper objectMapper,
                                  @Value("${app.maintenance.command-topic}") String commandTopic,
                                  WorkspaceAiModelClient workspaceAiModelClient) {
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.operationStarter = operationStarter;
        this.outboxWriter = outboxWriter;
        this.runStatusRequester = runStatusRequester;
        this.lintStateRepository = lintStateRepository;
        this.wikiStateRequester = wikiStateRequester;
        this.objectMapper = objectMapper;
        this.commandTopic = commandTopic;
        this.workspaceAiModelClient = workspaceAiModelClient;
    }

    @Transactional
    public JsonNode lint(String workspaceId, String userId, WikiLintRequest request) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        // dry_run도 LLM 비용을 쓰므로 모든 lint 요청에 권한을 요구한다.
        workspaceAccessGuard.requirePermission(workspaceId, userId, WorkspacePermissions.WIKI_LINT);
        WikiLintRequest safe = request == null ? new WikiLintRequest(null, null) : request;
        boolean dryRun = !Boolean.FALSE.equals(safe.dryRun());
        // 값을 주지 않은 실제 실행은 승격까지 반영한다. 화면의 "위키 최신화"는 이 값을 보내지 않아 후보만 계산하고 끝났다.
        boolean materializePromotions = safe.materializePromotions() == null ? !dryRun : safe.materializePromotions();
        String runId = UUID.randomUUID().toString();
        // access HTTP 조회는 크레딧 예약(계정 행 잠금) 전에 한다. 잠금을 쥔 채 기다리면 같은 사용자의 AI 요청이 줄을 선다.
        WorkspaceAiModelClient.AiModelSelection aiModel = workspaceAiModelClient.get(workspaceId);
        outboxWriter.begin(runId, workspaceId, userId, "lint");
        String operationId = dryRun ? null : operationStarter.start(workspaceId, userId);
        outboxWriter.enqueue(runId, commandTopic, workspaceId,
                new LintCommand(runId, "lint", workspaceId, userId, operationId,
                        materializePromotions, dryRun,
                        aiModel.provider(), aiModel.model()));
        return objectMapper.valueToTree(new LintRunResponse(runId, operationId, "queued"));
    }

    public JsonNode run(String workspaceId, String userId, String runId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        // lint run ID는 UUID다. 다른 값을 ai에 넘기면 ai가 500을 내 404 대신 서버 오류로 보인다.
        try {
            UUID.fromString(runId);
        } catch (IllegalArgumentException e) {
            throw new PipelineWikiMaintenanceException("Wiki maintenance run을 찾을 수 없습니다.", 404, null);
        }
        var found = runStatusRequester.find(runId)
                .filter(value -> workspaceId.equals(value.workspaceId()) && userId.equals(value.userId()));
        if (found.isEmpty()) return coreRun(workspaceId, userId, runId);
        var run = found.get();
        // error는 ai가 보낸 코드다. 화면에는 한국어 문구를 error로, 코드를 error_code로 내보낸다.
        var node = (ObjectNode) objectMapper.valueToTree(run);
        if (run.error() != null) {
            node.put("error_code", run.error());
            node.put("error", AiErrorMessages.lint(run.error()));
        }
        return node;
    }

    /**
     * ai는 워커가 명령을 꺼낼 때 run 행을 만든다. 그 전(대기 중, 또는 행을 만들기 전 실패)에는 core 기록으로 답한다.
     * 실패를 404로 숨기면 화면은 폴링 한도(5분)까지 아무 말 없이 기다린다.
     */
    private JsonNode coreRun(String workspaceId, String userId, String runId) {
        var row = outboxWriter.findRun(runId, workspaceId, userId)
                .orElseThrow(() -> new PipelineWikiMaintenanceException(
                        "Wiki maintenance run을 찾을 수 없습니다.", 404, null));
        String status = (String) row.get("status");
        String code = (String) row.get("error_code");
        var node = objectMapper.createObjectNode().put("id", runId);
        // 취소 처리 중인 run은 아직 끝나지 않았다. 끝난 취소는 실패가 아니라 취소 문구로 알린다.
        if (code == null && java.util.Set.of("running", "cancel_requested", "rolling_back").contains(status)) {
            return node.put("status", "queued");
        }
        if (code == null && "cancelled".equals(status)) code = "cancelled";
        // 결과가 왔는데 ai에 run이 없다면 행을 만들기 전에 실패한 것이다.
        node.put("status", "failed");
        if (code != null) node.put("error_code", code);
        return node.put("error", AiErrorMessages.lint(code));
    }

    /** 마지막 lint 성공 이후 위키 페이지가 변경됐으면 needs_lint = true. */
    public WikiMaintenanceStatusResponse status(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        Instant lastLintAt = lintStateRepository.findById(workspaceId)
                .map(WikiLintState::getLastLintAt)
                .orElse(null);
        Instant lastWikiChangeAt = wikiStateRequester.lastUpdatedAt(workspaceId);
        boolean needsLint = lastWikiChangeAt != null
                && (lastLintAt == null || lastWikiChangeAt.isAfter(lastLintAt));
        return new WikiMaintenanceStatusResponse(needsLint, lastLintAt, lastWikiChangeAt);
    }

    public void markLintSucceeded(String workspaceId) {
        lintStateRepository.upsert(workspaceId, Instant.now());
    }

    record LintCommand(
            @JsonProperty("run_id") String runId,
            String kind,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("operation_id") String operationId,
            @JsonProperty("materialize_promotions") boolean materializePromotions,
            @JsonProperty("dry_run") boolean dryRun,
            String provider,
            String model
    ) {}

    record LintRunResponse(
            @JsonProperty("run_id") String runId,
            @JsonProperty("operation_id") String operationId,
            String status
    ) {}
}
