package fruition.core.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.domain.RestoreAction;
import fruition.core.aihistory.dto.DocumentRestorePlan;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestoreExecuteResponse;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.exception.InvalidRestoreRequestException;
import fruition.core.aihistory.exception.RestorePreviewStaleException;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.wiki.domain.WikiPageContribution;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 복구 실행. 미리보기에서 본 상태가 아직 그대로인지 확인하고 Kafka 재조립 작업을 등록한다.
 *
 * <p>ingest 되돌리기는 <b>Wiki만</b> 다룬다. ingest는 원문 문서를 읽기만 하고 바꾸지 않으므로
 * 되돌릴 문서 본문이 없다.
 */
@Service
public class RestoreExecuteService {

    private static final int MAX_DOCUMENT_RESTORE_ATTEMPTS = 3;

    private final RestorePreviewService previewService;
    private final RestoreScopeResolver scopeResolver;
    private final RestorePlanner planner;
    private final LintRestorePlanner lintRestorePlanner;
    private final PreviewTokenSigner tokenSigner;
    private final RestoreOperationLifecycle lifecycle;
    private final DocumentRestorePlanner documentPlanner;
    private final DocumentRestoreApplier documentApplier;
    private final AiCommandOutboxWriter outboxWriter;
    private final RestoreTargetValidator validator;
    private final ObjectMapper objectMapper;
    private final String commandTopic;
    private final PlatformTransactionManager transactionManager;

    public RestoreExecuteService(RestorePreviewService previewService,
                                 RestoreScopeResolver scopeResolver,
                                 RestorePlanner planner,
                                 LintRestorePlanner lintRestorePlanner,
                                 PreviewTokenSigner tokenSigner,
                                 RestoreOperationLifecycle lifecycle,
                                 DocumentRestorePlanner documentPlanner,
                                 DocumentRestoreApplier documentApplier,
                                 AiCommandOutboxWriter outboxWriter,
                                 RestoreTargetValidator validator,
                                 ObjectMapper objectMapper,
                                 @Value("${app.maintenance.command-topic}") String commandTopic,
                                 PlatformTransactionManager transactionManager) {
        this.previewService = previewService;
        this.scopeResolver = scopeResolver;
        this.planner = planner;
        this.lintRestorePlanner = lintRestorePlanner;
        this.tokenSigner = tokenSigner;
        this.lifecycle = lifecycle;
        this.documentPlanner = documentPlanner;
        this.documentApplier = documentApplier;
        this.outboxWriter = outboxWriter;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.commandTopic = commandTopic;
        this.transactionManager = transactionManager;
    }

    @Transactional
    public RestoreExecuteResponse execute(String workspaceId, String userId,
                                          String operationId, String previewToken) {
        OperationLog target = previewService.loadOperation(workspaceId, userId, operationId);
        validator.requireRestorable(target);
        // 인가·대상 유형을 확인한 뒤 claim을 먼저 본다. 이후 상태가 바뀌어도 재실행은 중복이다.
        String restoreTokenHash = tokenHash(previewToken);
        if (lifecycle.isClaimed(target.getOperationId(), restoreTokenHash)) {
            throw new InvalidRestoreRequestException(
                    "같은 미리보기 토큰으로 복구가 이미 접수되었습니다.");
        }
        if (target.getOperationType() == OperationType.document_edit) {
            return executeDocument(target, previewToken, restoreTokenHash);
        }

        Set<String> excluded = scopeResolver.resolve(target);
        Map<String, List<WikiPageContribution>> contributions;
        RestorePlan plan;
        if (target.getOperationType() == OperationType.lint) {
            LintRestorePlanner.Context context = lintRestorePlanner.plan(target);
            contributions = context.contributions();
            plan = context.plan();
        } else {
            contributions = previewService.loadContributions(excluded);
            plan = planner.plan(excluded, contributions, workspaceId);
        }

        // 미리보기 이후 대상이 바뀌었으면 실행하지 않는다. 되돌리기는 무를 수 없다.
        if (!tokenSigner.matches(previewToken, operationId, contributions)) {
            throw new RestorePreviewStaleException();
        }

        // 반영 전에 확인한다. 뒤에서 걸리면 이미 DB가 바뀐 뒤라 되돌릴 수 없다.
        PageRestorePlan sourcePage = validator.requireApplicable(target, plan);

        String runId = java.util.UUID.randomUUID().toString();
        outboxWriter.begin(runId, workspaceId, userId, target.getOperationType() == OperationType.lint ? "restore_lint" : "restore_ingest");
        Instant now = Instant.now();
        Map<String, List<String>> expected = contributionSignatures(contributions);
        OperationLog restore = lifecycle.startQueued(
                        target, manifestJson(new RestoreManifest(plan, excluded, expected)),
                        restoreTokenHash, now)
                .orElseThrow(() -> new InvalidRestoreRequestException(
                        "같은 미리보기 토큰으로 복구가 이미 접수되었습니다."));
        outboxWriter.enqueue(runId, commandTopic, workspaceId,
                restoreCommand(runId, restore, target, excluded, plan, sourcePage, expected));
        return RestoreExecuteResponse.queued(runId, restore.getOperationId(), operationId, plan);
    }

    /**
     * 문서 편집 되돌리기. Wiki와 달리 재작성이 없어 llmPipeline을 부르지 않고 그 자리에서 끝난다.
     */
    private RestoreExecuteResponse executeDocument(OperationLog target, String previewToken,
                                                   String restoreTokenHash) {
        DocumentRestorePlan plan = documentPlanner.plan(target);
        if (!tokenSigner.matches(previewToken, target.getOperationId(), plan)) {
            throw new RestorePreviewStaleException();
        }

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            return executeWithDocumentRestoreRetry(() -> transaction.execute(status -> {
                Instant now = Instant.now();
                OperationLog restore = lifecycle.start(
                                target, manifestJson(plan), restoreTokenHash, now)
                        .orElseThrow(() -> new InvalidRestoreRequestException(
                                "같은 미리보기 토큰으로 복구가 이미 접수되었습니다."));
                long newVersion = documentApplier.apply(restore, plan);
                lifecycle.finishDocument(restore.getOperationId(), plan.toVersion(), newVersion, now);

                return RestoreExecuteResponse.forDocument(
                        restore.getOperationId(), target.getOperationId());
            }));
        } catch (DocumentVersionConflictException exception) {
            throw new RestorePreviewStaleException();
        }
    }

    private <T> T executeWithDocumentRestoreRetry(java.util.function.Supplier<T> operation) {
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException exception) {
                if (attempt >= MAX_DOCUMENT_RESTORE_ATTEMPTS || !isTransientDocumentRestoreFailure(exception)) {
                    throw exception;
                }
            }
        }
    }

    private boolean isTransientDocumentRestoreFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DuplicateKeyException
                    || cause instanceof DeadlockLoserDataAccessException
                    || cause instanceof CannotSerializeTransactionException) {
                return true;
            }
        }
        return false;
    }

    private RestoreCommand restoreCommand(String runId, OperationLog restore, OperationLog target,
                                          Set<String> excluded, RestorePlan plan,
                                          PageRestorePlan sourcePage,
                                          Map<String, List<String>> expected) {
        boolean lint = target.getOperationType() == OperationType.lint;
        return new RestoreCommand(
                runId, lint ? "restore_lint" : "restore_ingest",
                restore.getWorkspaceId(), restore.getUserId(), restore.getOperationId(),
                lint ? target.getOperationId() : null,
                lint ? null : sourcePage.targetOperationId(),
                lint ? null : List.copyOf(excluded),
                lint ? null : new SourcePage(sourcePage.pageId(), target.getTargetDocumentId()),
                rebuildPages(plan),
                lint ? deletedPages(plan) : deletedPagesExcept(plan, sourcePage.pageId()),
                expected);
    }

    private static List<RebuildPage> rebuildPages(RestorePlan plan) {
        return plan.byAction(RestoreAction.rebuild).stream().map(RebuildPage::from).toList();
    }

    private static List<String> deletedPages(RestorePlan plan) {
        return plan.byAction(RestoreAction.delete).stream().map(PageRestorePlan::pageId).toList();
    }

    private static List<String> deletedPagesExcept(RestorePlan plan, String sourcePageId) {
        return deletedPages(plan).stream().filter(id -> !id.equals(sourcePageId)).toList();
    }

    private Map<String, List<String>> contributionSignatures(
            Map<String, List<WikiPageContribution>> contributions) {
        Map<String, List<String>> signatures = new java.util.LinkedHashMap<>();
        contributions.forEach((pageId, rows) -> signatures.put(pageId, rows.stream()
                .sorted(java.util.Comparator.comparingLong(WikiPageContribution::getSequenceRevision))
                .map(row -> row.getIngestOperationId() + ":" + row.getSequenceRevision()
                        + ":" + (row.isActive() ? "1" : "0"))
                .toList()));
        return signatures;
    }

    /** 지시서 원본을 보관한다. 재조립 결과를 받을 때 목표 기여 수를 여기서 꺼내면 그사이 새 ingest가 들어와도 값이 안 흔들린다. */
    private String manifestJson(Object plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (Exception e) {
            throw new IllegalStateException("복구 지시서를 직렬화하지 못했습니다.", e);
        }
    }

    private String tokenHash(String previewToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(previewToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("복구 미리보기 토큰 지문을 계산하지 못했습니다.", e);
        }
    }

    public record RestoreManifest(
            RestorePlan plan,
            @com.fasterxml.jackson.annotation.JsonProperty("excluded_operation_ids") Set<String> excludedOperationIds,
            @com.fasterxml.jackson.annotation.JsonProperty("expected_contributions") Map<String, List<String>> expectedContributions
    ) {}

    record RestoreCommand(
            @com.fasterxml.jackson.annotation.JsonProperty("run_id") String runId,
            String kind,
            @com.fasterxml.jackson.annotation.JsonProperty("workspace_id") String workspaceId,
            @com.fasterxml.jackson.annotation.JsonProperty("user_id") String userId,
            @com.fasterxml.jackson.annotation.JsonProperty("operation_id") String operationId,
            @com.fasterxml.jackson.annotation.JsonProperty("target_operation_id") String targetOperationId,
            @com.fasterxml.jackson.annotation.JsonProperty("restore_to_operation_id") String restoreToOperationId,
            @com.fasterxml.jackson.annotation.JsonProperty("cancel_operation_ids") List<String> cancelOperationIds,
            @com.fasterxml.jackson.annotation.JsonProperty("source_page") SourcePage sourcePage,
            @com.fasterxml.jackson.annotation.JsonProperty("rebuild_pages") List<RebuildPage> rebuildPages,
            @com.fasterxml.jackson.annotation.JsonProperty("deleted_pages") List<String> deletedPages,
            @com.fasterxml.jackson.annotation.JsonProperty("expected_contributions") Map<String, List<String>> expectedContributions
    ) {}

    record RebuildPage(
            @com.fasterxml.jackson.annotation.JsonProperty("page_id") String pageId,
            @com.fasterxml.jackson.annotation.JsonProperty("keep_contributions") List<Kept> keepContributions
    ) {
        static RebuildPage from(PageRestorePlan page) {
            return new RebuildPage(page.pageId(), page.keepContributions().stream()
                    .map(value -> new Kept(value.operationId(), value.documentId())).toList());
        }
    }

    record Kept(
            @com.fasterxml.jackson.annotation.JsonProperty("operation_id") String operationId,
            @com.fasterxml.jackson.annotation.JsonProperty("document_id") String documentId
    ) {}

    record SourcePage(
            @com.fasterxml.jackson.annotation.JsonProperty("page_id") String pageId,
            @com.fasterxml.jackson.annotation.JsonProperty("document_id") String documentId
    ) {}
}
