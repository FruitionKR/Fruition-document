package fruition.core.aihistory.service;

import fruition.TestcontainersConfiguration;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.domain.RestoreAction;
import fruition.core.aihistory.dto.OperationResultRequest;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 같은 문서를 다시 편입하면 이전 편입 기여를 새 편입이 대체해 끈다. 새 편입이 빠뜨린 개념(Q)의 이전 기여가
 * 재생에 다시 들어오지 않고, 새 편입을 되돌리면 정확히 그 행들이 다시 켜진다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReingestSupersedeIntegrationTest {

    @Autowired OperationLogRepository operationLogRepository;
    @Autowired OperationChangeRepository operationChangeRepository;
    @Autowired WikiPageVersionRepository versionRepository;
    @Autowired WikiPageContributionRepository contributionRepository;
    @Autowired DocumentRepository documentRepository;
    @Autowired RestorePreviewService previewService;
    @Autowired ReingestRebuildStarter reingestRebuildStarter;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void reingestSupersedesEarlierRowsAndRestoreBringsThemBack() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ws = "ws_" + suffix;
        String doc = "doc_" + suffix;
        String first = "op_first_" + suffix;
        String second = "op_second_" + suffix;
        String other = "op_other_" + suffix;
        String lint = "op_lint_" + suffix;
        String source = "S_" + suffix;
        String kept = "P_" + suffix;
        String dropped = "Q_" + suffix;
        String child = "C_" + suffix;
        Instant now = Instant.now();
        jdbc.update("INSERT INTO documents(id, byte_size, content_hash, filename, display_name, normalized_filename, "
                + "mime_type, status, uploaded_at, updated_at, user_id, workspace_id, current_content_hash, current_version, document_role, sort_order) "
                + "VALUES (?, 1, 'h', ?, ?, ?, 'text/markdown', 'completed', now(), now(), 'user_1', ?, 'h', 1, 'EDITABLE', 0)",
                doc, doc, doc, doc, ws);

        var wiki = mock(PipelineWikiStateRequester.class);
        when(wiki.lookup(any(), eq(ws))).thenAnswer(invocation -> ((List<String>) invocation.getArgument(0)).stream()
                .map(id -> new PipelineWikiStateRequester.WikiPageSnapshot(
                        id, id.equals(source) ? "source" : "concept", id, id, ws, "active"))
                .toList());
        var lines = mock(LineCounter.class);
        when(lines.count(anyString(), any(), any(), anyLong(), anyString())).thenReturn(LineCounter.LineCount.none());
        var applier = new OperationApplier(operationLogRepository, operationChangeRepository, wiki,
                versionRepository, contributionRepository, lines, documentRepository, reingestRebuildStarter);

        // 처음 편입: S·P·Q. 다른 문서가 Q에 기여하고, lint가 첫 편입의 Q 기록을 자식 C에 이었다.
        transactionTemplate.executeWithoutResult(status -> {
            operationLogRepository.save(OperationLog.processing(first, ws, "user_1", OperationType.ingest,
                    doc, now.minusSeconds(3600)));
            operationLogRepository.save(OperationLog.completed(other, ws, "user_1", OperationType.ingest,
                    null, "다른 문서", 1, now.minusSeconds(3000)));
            operationLogRepository.save(OperationLog.completed(lint, ws, "user_1", OperationType.lint,
                    null, "lint", 1, now.minusSeconds(2000)));
            applier.apply(first, result(first, ws, doc), List.of(page(ws, source, first), page(ws, kept, first),
                    page(ws, dropped, first)), "hash-1", now.minusSeconds(3600));
            contributionRepository.save(new WikiPageContribution(dropped, other, "doc_other", 10L,
                    "wiki/" + ws + "/pages/" + dropped + "/ops/" + other + ".json", now));
            contributionRepository.save(WikiPageContribution.lint(child, first, doc, 1L,
                    "wiki/" + ws + "/pages/" + child + "/ops/" + first + ".json", lint, now));
            contributionRepository.save(WikiPageContribution.lint(child, lint, null, 2L,
                    "wiki/" + ws + "/pages/" + child + "/ops/" + lint + ".json", lint, now));
        });

        // 다시 편입: Q 개념이 빠졌다.
        transactionTemplate.executeWithoutResult(status -> {
            operationLogRepository.save(OperationLog.processing(second, ws, "user_1", OperationType.ingest, doc, now));
            applier.apply(second, result(second, ws, doc), List.of(page(ws, source, second), page(ws, kept, second)),
                    "hash-2", now);
        });

        assertThat(rows(source, kept, dropped, child))
                .contains(tuple(dropped, first, false, second), tuple(kept, first, false, second),
                        tuple(source, first, false, second), tuple(child, first, false, second),
                        tuple(kept, second, true, null), tuple(dropped, other, true, null),
                        tuple(child, lint, true, null));

        // 다시 편입 결과에 없는 Q·C는 남은 활성 기여(첫 편입 행 제외)로 다시 만들라는 명령을 보냈다.
        var command = objectMapper.readTree(jdbc.queryForObject(
                "SELECT payload FROM ai_command_outbox WHERE payload LIKE ? ", String.class,
                "%\"target_operation_id\":\"" + second + "\"%"));
        assertThat(command.path("kind").asText()).isEqualTo("restore_rebuild");
        assertThat(command.path("cancel_operation_ids").isNull()).isTrue();
        assertThat(command.path("deleted_pages")).isEmpty();
        assertThat(command.path("rebuild_pages")).hasSize(2);
        Map<String, List<String>> rebuild = new LinkedHashMap<>();
        command.path("rebuild_pages").forEach(page -> rebuild.put(page.path("page_id").asText(),
                java.util.stream.StreamSupport.stream(page.path("keep_contributions").spliterator(), false)
                        .map(item -> item.path("operation_id").asText()).toList()));
        assertThat(rebuild).containsExactly(Map.entry(child, List.of(lint)), Map.entry(dropped, List.of(other)));

        // ai 결과를 복구와 같은 경로로 받아 새 버전으로 기록한다. 원장은 바뀌지 않는다.
        String rebuildId = command.path("operation_id").asText();
        var before = rows(source, kept, dropped, child);
        transactionTemplate.executeWithoutResult(status -> {
            OperationLog rebuildOp = operationLogRepository.findById(rebuildId).orElseThrow();
            assertThat(rebuildOp.getOperationType()).isEqualTo(OperationType.restore);
            assertThat(rebuildOp.getRestoredFrom()).isNull();
            var manifest = readManifest(rebuildOp.getRestoreManifest());
            new RestoreApplier(operationLogRepository, operationChangeRepository, wiki, versionRepository,
                    contributionRepository).apply(rebuildOp, manifest.plan(), manifest.excludedOperationIds(),
                    manifest.expectedContributions(), now);
            new RestoreRebuildApplier(operationLogRepository, operationChangeRepository, wiki, versionRepository,
                    lines, objectMapper).apply(rebuildId,
                    new OperationResultRequest(rebuildId, "restore_rebuild", "succeeded", ws, "user_1", null, null,
                            List.of(), List.of(), List.of(child), null, null, null),
                    List.of(new RestoreRebuildApplier.RebuiltPage(dropped,
                            "wiki/" + ws + "/pages/" + dropped + "/ops/" + rebuildId + ".md", "# Q 다시", "sha256:q")),
                    "hash-rebuild", now);
        });
        assertThat(rows(source, kept, dropped, child)).containsExactlyInAnyOrderElementsOf(before);
        assertThat(versionRepository.findTopByIdPageIdOrderByIdRevisionDesc(dropped).orElseThrow().getOperationId())
                .isEqualTo(rebuildId);
        assertThat(operationChangeRepository.findByOperationIdOrderByIdAsc(rebuildId))
                .extracting(change -> change.getResourceId() + ":" + change.getChangeType())
                .contains(dropped + ":rebuilt", child + ":deleted");
        assertThat(operationLogRepository.findById(rebuildId).orElseThrow().getStatus().name()).isEqualTo("succeeded");

        // 다른 편입을 되돌려 Q를 다시 쓰면 첫 편입의 Q 기여는 남길 기여에 들어가지 않는다.
        Map<String, List<WikiPageContribution>> otherPages = previewService.loadContributions(Set.of(other));
        RestorePlan undoOther = new RestorePlanner(wiki).plan(Set.of(other), otherPages, ws);
        assertThat(undoOther.pages()).extracting(PageRestorePlan::pageId, PageRestorePlan::action)
                .containsExactly(tuple(dropped, RestoreAction.delete));

        // 다시 편입을 되돌리면 그 편입이 끈 행만 다시 켠다. 새 편입이 다루지 않은 Q·C도 대상이다.
        RestorePlan undoSecond = transactionTemplate.execute(status -> {
            Map<String, List<WikiPageContribution>> pages = previewService.loadContributions(Set.of(second));
            assertThat(pages.keySet()).containsExactlyInAnyOrder(source, kept, dropped, child);
            RestorePlan plan = new RestorePlanner(wiki).plan(Set.of(second), pages, ws);
            OperationLog restore = operationLogRepository.save(
                    OperationLog.applying("op_restore_" + suffix, ws, "user_1", doc, second, "{}", now));
            new RestoreApplier(operationLogRepository, operationChangeRepository, wiki, versionRepository,
                    contributionRepository).apply(restore, plan, Set.of(second), signatures(pages), now);
            return plan;
        });
        assertThat(keptOperations(undoSecond, dropped)).containsExactly(first, other);
        assertThat(keptOperations(undoSecond, child)).containsExactly(first, lint);
        assertThat(rows(source, kept, dropped, child))
                .contains(tuple(dropped, first, true, null), tuple(kept, first, true, null),
                        tuple(child, first, true, null), tuple(kept, second, false, "op_restore_" + suffix));
    }

    private RestoreExecuteService.RestoreManifest readManifest(String json) {
        try {
            return objectMapper.readValue(json, RestoreExecuteService.RestoreManifest.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<org.assertj.core.groups.Tuple> rows(String... pageIds) {
        return contributionRepository.findByPageIds(List.of(pageIds)).stream()
                .map(row -> tuple(row.getPageId(), row.getIngestOperationId(), row.isActive(), row.getDeactivatedBy()))
                .toList();
    }

    private static List<String> keptOperations(RestorePlan plan, String pageId) {
        PageRestorePlan page = plan.pages().stream().filter(item -> item.pageId().equals(pageId)).findFirst()
                .orElseThrow();
        assertThat(page.action()).isEqualTo(RestoreAction.rebuild);
        return page.keepContributions().stream().map(PageRestorePlan.Kept::operationId).toList();
    }

    private static Map<String, List<String>> signatures(Map<String, List<WikiPageContribution>> pages) {
        Map<String, List<String>> expected = new LinkedHashMap<>();
        pages.forEach((pageId, rows) -> expected.put(pageId, new ArrayList<>(rows.stream()
                .sorted(java.util.Comparator.comparingLong(WikiPageContribution::getSequenceRevision))
                .map(row -> row.getIngestOperationId() + ":" + row.getSequenceRevision()
                        + ":" + (row.isActive() ? "1" : "0"))
                .toList())));
        return expected;
    }

    private static OperationResultRequest result(String operationId, String ws, String doc) {
        return new OperationResultRequest(operationId, "ingest", "succeeded", ws, "user_1", doc, "편입",
                List.of(), List.of());
    }

    private static OperationApplier.LoadedPage page(String ws, String pageId, String operationId) {
        String prefix = "wiki/" + ws + "/pages/" + pageId + "/ops/" + operationId;
        return new OperationApplier.LoadedPage(pageId, prefix + ".md", prefix + ".json",
                "# " + pageId + " " + operationId, "sha256:" + pageId + operationId);
    }
}
