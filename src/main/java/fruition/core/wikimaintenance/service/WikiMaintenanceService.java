package fruition.core.wikimaintenance.service;

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
        WikiLintRequest safe = request == null ? new WikiLintRequest(null, null) : request;
        boolean dryRun = !Boolean.FALSE.equals(safe.dryRun());
        String runId = UUID.randomUUID().toString();
        outboxWriter.begin(runId, workspaceId, userId, "lint");
        String operationId = dryRun ? null : operationStarter.start(workspaceId, userId);
        WorkspaceAiModelClient.AiModelSelection aiModel = workspaceAiModelClient.get(workspaceId);
        outboxWriter.enqueue(runId, commandTopic, workspaceId,
                new LintCommand(runId, "lint", workspaceId, userId, operationId,
                        Boolean.TRUE.equals(safe.materializePromotions()), dryRun,
                        aiModel.provider(), aiModel.model()));
        return objectMapper.valueToTree(new LintRunResponse(runId, operationId, "queued"));
    }

    public JsonNode run(String workspaceId, String userId, String runId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        var run = runStatusRequester.find(runId)
                .filter(value -> workspaceId.equals(value.workspaceId()) && userId.equals(value.userId()))
                .orElseThrow(() -> new PipelineWikiMaintenanceException(
                        "Wiki maintenance run을 찾을 수 없습니다.", 404, null));
        return objectMapper.valueToTree(run);
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
