package fruition.core.aihistory.repository;

import fruition.TestcontainersConfiguration;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.domain.RestoreAction;
import fruition.core.aihistory.dto.OperationResultRequest;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.service.LintRestorePlanner;
import fruition.core.aihistory.service.RestoreApplier;
import fruition.core.aihistory.service.LineCounter;
import fruition.core.aihistory.service.LintOperationApplier;
import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** lint 원장 행이 실제 스키마(V84 lint_operation_id)에 저장되고 재전송에 중복되지 않는지 본다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LintLedgerContributionIntegrationTest {

    @Autowired OperationLogRepository operationLogRepository;
    @Autowired OperationChangeRepository operationChangeRepository;
    @Autowired WikiPageVersionRepository versionRepository;
    @Autowired WikiPageContributionRepository contributionRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void lintLedgerRowsPersistInOrderAndRedeliveryIsIdempotent() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ws = "ws_" + suffix;
        String ingestId = "op_x_" + suffix;
        String lintId = "op_lint_" + suffix;
        String child = "child_" + suffix;
        Instant now = Instant.now();

        var wiki = mock(PipelineWikiStateRequester.class);
        when(wiki.lookup(List.of(child), ws)).thenReturn(List.of(
                new PipelineWikiStateRequester.WikiPageSnapshot(child, "concept", "자식", child, ws, "active")));
        var lines = mock(LineCounter.class);
        when(lines.count(anyString(), any(), any(), anyLong(), anyString())).thenReturn(LineCounter.LineCount.none());
        var applier = new LintOperationApplier(operationLogRepository, operationChangeRepository,
                wiki, versionRepository, contributionRepository, lines);
        List<OperationResultRequest.LedgerContribution> ledger = List.of(
                new OperationResultRequest.LedgerContribution(child, lintId, null,
                        "wiki/" + ws + "/pages/" + child + "/ops/" + lintId + ".json", null),
                new OperationResultRequest.LedgerContribution(child, ingestId, null,
                        "wiki/" + ws + "/pages/" + child + "/ops/" + ingestId + ".json", lintId));
        OperationResultRequest request = new OperationResultRequest(lintId, "lint", "succeeded", ws, "user_1",
                null, "lint", List.of(), List.of(), List.of(), null, null, ledger);
        var page = new LintOperationApplier.LoadedPage(child,
                "wiki/" + ws + "/pages/" + child + "/ops/" + lintId + ".md", "# 자식", "sha256:child");

        transactionTemplate.executeWithoutResult(status -> {
            operationLogRepository.save(OperationLog.completed(
                    ingestId, ws, "user_1", OperationType.ingest, null, "편입", 1, now));
            operationLogRepository.save(OperationLog.processing(
                    lintId, ws, "user_1", OperationType.lint, null, now));
        });
        for (int delivery = 0; delivery < 2; delivery++) {
            transactionTemplate.executeWithoutResult(status ->
                    applier.apply(lintId, request, List.of(page), ledger, "payload-hash", now));
        }

        assertThat(contributionRepository.findByPageIds(List.of(child)))
                .extracting(WikiPageContribution::getIngestOperationId, WikiPageContribution::getSequenceRevision,
                        WikiPageContribution::getLintOperationId, WikiPageContribution::isActive)
                .containsExactly(tuple(lintId, 1L, lintId, true), tuple(ingestId, 2L, lintId, true));
        assertThat(versionRepository.findMaxRevision(child)).isEqualTo(2L);
    }

    @Test
    void lintRestoreDeletesChildRecreatedAfterEarlierLintRestore() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ws = "ws_" + suffix;
        String ingestId = "op_x_" + suffix;
        String child = "child_" + suffix;
        Instant now = Instant.now();
        var wiki = mock(PipelineWikiStateRequester.class);
        when(wiki.lookup(List.of(child), ws)).thenReturn(List.of(
                new PipelineWikiStateRequester.WikiPageSnapshot(child, "concept", "자식", child, ws, "active")));
        var lines = mock(LineCounter.class);
        when(lines.count(anyString(), any(), any(), anyLong(), anyString())).thenReturn(LineCounter.LineCount.none());
        var applier = new LintOperationApplier(operationLogRepository, operationChangeRepository,
                wiki, versionRepository, contributionRepository, lines);
        var lintPlanner = new LintRestorePlanner(operationChangeRepository, contributionRepository);
        var restoreApplier = new RestoreApplier(operationLogRepository, operationChangeRepository,
                wiki, versionRepository, contributionRepository);
        transactionTemplate.executeWithoutResult(status -> operationLogRepository.save(OperationLog.completed(
                ingestId, ws, "user_1", OperationType.ingest, null, "편입", 1, now)));

        // 분리 → lint 복원으로 자식 삭제 → 다음 lint가 같은 자식을 다시 나눈다.
        split(applier, ws, "op_lint1_" + suffix, ingestId, child, now);
        restoreLint(lintPlanner, restoreApplier, ws, "op_lint1_" + suffix, "op_restore1_" + suffix, child, now);
        split(applier, ws, "op_lint2_" + suffix, ingestId, child, now);

        RestorePlan plan = transactionTemplate.execute(status ->
                lintPlanner.plan(operationLogRepository.findById("op_lint2_" + suffix).orElseThrow()).plan());
        assertThat(plan.pages()).extracting(PageRestorePlan::pageId, PageRestorePlan::action)
                .containsExactly(tuple(child, RestoreAction.delete));
    }

    private void split(LintOperationApplier applier, String ws, String lintId, String ingestId,
                       String child, Instant now) {
        String prefix = "wiki/" + ws + "/pages/" + child + "/ops/";
        List<OperationResultRequest.LedgerContribution> ledger = List.of(
                new OperationResultRequest.LedgerContribution(child, lintId, null, prefix + lintId + ".json", null),
                new OperationResultRequest.LedgerContribution(child, ingestId, null, prefix + ingestId + ".json", lintId));
        transactionTemplate.executeWithoutResult(status -> {
            operationLogRepository.save(OperationLog.processing(lintId, ws, "user_1", OperationType.lint, null, now));
            applier.apply(lintId, new OperationResultRequest(lintId, "lint", "succeeded", ws, "user_1", null,
                            "lint", List.of(), List.of(), List.of(), null, null, ledger),
                    List.of(new LintOperationApplier.LoadedPage(child, prefix + lintId + ".md",
                            "# 자식 " + lintId, "sha256:" + lintId)),
                    ledger, "payload-hash", now);
        });
    }

    private void restoreLint(LintRestorePlanner planner, RestoreApplier restoreApplier, String ws,
                             String lintId, String restoreId, String child, Instant now) {
        transactionTemplate.executeWithoutResult(status -> {
            RestorePlan plan = planner.plan(operationLogRepository.findById(lintId).orElseThrow()).plan();
            assertThat(plan.pages()).extracting(PageRestorePlan::action).containsExactly(RestoreAction.delete);
            OperationLog restore = operationLogRepository.save(
                    OperationLog.applying(restoreId, ws, "user_1", null, lintId, "{}", now));
            List<String> signature = contributionRepository.findByPageIds(List.of(child)).stream()
                    .map(row -> row.getIngestOperationId() + ":" + row.getSequenceRevision()
                            + ":" + (row.isActive() ? "1" : "0"))
                    .toList();
            restoreApplier.apply(restore, plan, Set.of(lintId), Map.of(child, signature), now);
        });
    }
}
