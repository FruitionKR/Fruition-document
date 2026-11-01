package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.domain.RestoreAction;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * lint가 원장에 남긴 행(ai ADR 0042)의 복구 판정과 반영.
 *
 * <p>분리 L1: 부모 P의 X 항목을 자식 C로 옮겼다. 괄호 안 숫자는 sequence_revision이다.
 * <pre>
 * P  X(1) Y(2) L1(3, lint 기록)
 * C  L1(1, lint 기록) X(2, L1이 이은 기록)
 * </pre>
 * 다시 합치기 L2: 분리 뒤 C에만 기여한 Z를 P에 잇고 C를 지웠다.
 * <pre>
 * P  X(1) Y(2) L1(3) L2(4, lint 기록) Z(5, L2가 이은 기록)
 * C  L1(1) X(2) Z(3) L2(4, lint 기록) — 모두 L2가 끔
 * </pre>
 */
class LintLedgerRestoreTest {

    private static final String WS = "ws_test";
    private static final Instant T = Instant.parse("2026-11-01T00:00:00Z");

    private final PipelineWikiStateRequester wiki = mock(PipelineWikiStateRequester.class);
    private final OperationChangeRepository changes = mock(OperationChangeRepository.class);
    private final WikiPageContributionRepository contributions = mock(WikiPageContributionRepository.class);

    @Test
    @DisplayName("ingest 복구는 남은 lint 기록을 순서대로 재조립에 넣는다")
    void ingestRestoreReplaysLintRows() {
        when(wiki.lookup(any(), eq(WS))).thenReturn(List.of());
        Map<String, List<WikiPageContribution>> state = new LinkedHashMap<>();
        state.put("P", List.of(ingest("P", "X", 1), ingest("P", "Y", 2), lintRecord("P", "L1", 3)));

        RestorePlan plan = new RestorePlanner(wiki).plan(Set.of("Y"), state, WS);

        assertThat(kept(plan, "P")).containsExactly("X", "L1");
    }

    @Test
    @DisplayName("분리 뒤 ingest 복구는 부모와 자식 양쪽에서 그 작업을 빼고, lint 기록만 남은 자식은 지운다")
    void ingestRestoreAfterSplitCoversChildPage() {
        when(wiki.lookup(any(), eq(WS))).thenReturn(List.of());
        Map<String, List<WikiPageContribution>> state = splitState();

        RestorePlan plan = new RestorePlanner(wiki).plan(Set.of("X"), state, WS);

        assertThat(kept(plan, "P")).containsExactly("Y", "L1");
        assertThat(page(plan, "C").action()).isEqualTo(RestoreAction.delete);

        restoreApplier(state).apply(restoreOperation(), plan, Set.of("X"), signatures(state), T);
        assertThat(state.get("P").get(0).isActive()).isFalse();
        assertThat(state.get("C").get(1).isActive()).isFalse();
        assertThat(state.get("C").get(1).getDeactivatedBy()).isEqualTo("op_restore");
        assertThat(state.get("C").get(0).isActive()).as("lint 기록은 제외 대상이 아니다").isTrue();
    }

    @Test
    @DisplayName("분리 lint 복원은 만든 자식을 지우고 부모를 lint 기록 없이 다시 만들며 이은 기록도 끈다")
    void lintRestoreOfSplit() {
        Map<String, List<WikiPageContribution>> state = splitState();
        givenChanges("L1", change(1L, "P", ChangeType.updated), change(2L, "C", ChangeType.created));
        givenContributions(state);

        RestorePlan plan = new LintRestorePlanner(changes, contributions).plan(lint("L1")).plan();

        assertThat(kept(plan, "P")).containsExactly("X", "Y");
        assertThat(page(plan, "C").action()).isEqualTo(RestoreAction.delete);

        restoreApplier(state).apply(restoreOperation(), plan, Set.of("L1"), signatures(state), T);
        assertThat(state.get("P")).extracting(WikiPageContribution::isActive)
                .containsExactly(true, true, false);
        assertThat(state.get("C")).extracting(WikiPageContribution::isActive)
                .as("lint 자기 기록과 그 lint가 원래 작업 id로 이은 기록을 모두 끈다")
                .containsExactly(false, false);
    }

    @Test
    @DisplayName("다시 합치기 lint 복원은 지운 자식을 그 lint가 끈 기여로 다시 만든다")
    void lintRestoreOfMergeBackRecreatesChild() {
        Map<String, List<WikiPageContribution>> state = mergeBackState();
        givenChanges("L2", change(1L, "P", ChangeType.updated),
                change(2L, "C", ChangeType.updated), change(3L, "C", ChangeType.deleted));
        givenContributions(state);

        RestorePlan plan = new LintRestorePlanner(changes, contributions).plan(lint("L2")).plan();

        assertThat(kept(plan, "P")).containsExactly("X", "Y", "L1");
        assertThat(kept(plan, "C")).containsExactly("L1", "X", "Z");

        restoreApplier(state).apply(restoreOperation(), plan, Set.of("L2"), signatures(state), T);
        assertThat(state.get("P")).extracting(WikiPageContribution::isActive)
                .containsExactly(true, true, true, false, false);
        assertThat(state.get("C")).extracting(WikiPageContribution::isActive)
                .as("L2 자기 기록만 꺼진 채 남는다")
                .containsExactly(true, true, true, false);
    }

    private Map<String, List<WikiPageContribution>> splitState() {
        Map<String, List<WikiPageContribution>> state = new LinkedHashMap<>();
        state.put("P", List.of(ingest("P", "X", 1), ingest("P", "Y", 2), lintRecord("P", "L1", 3)));
        state.put("C", List.of(lintRecord("C", "L1", 1), linked("C", "X", 2, "L1")));
        return state;
    }

    private Map<String, List<WikiPageContribution>> mergeBackState() {
        Map<String, List<WikiPageContribution>> state = new LinkedHashMap<>();
        state.put("P", List.of(ingest("P", "X", 1), ingest("P", "Y", 2), lintRecord("P", "L1", 3),
                lintRecord("P", "L2", 4), linked("P", "Z", 5, "L2")));
        List<WikiPageContribution> child = List.of(lintRecord("C", "L1", 1), linked("C", "X", 2, "L1"),
                ingest("C", "Z", 3), lintRecord("C", "L2", 4));
        child.forEach(row -> row.deactivate("L2"));
        state.put("C", child);
        return state;
    }

    private RestoreApplier restoreApplier(Map<String, List<WikiPageContribution>> state) {
        List<WikiPageContribution> all = new ArrayList<>();
        state.values().forEach(all::addAll);
        when(contributions.findByPageIds(any())).thenReturn(all);
        when(wiki.lookup(any(), eq(WS))).thenAnswer(invocation -> ((List<String>) invocation.getArgument(0))
                .stream()
                .map(id -> new PipelineWikiStateRequester.WikiPageSnapshot(id, "concept", id, id, WS, "active"))
                .toList());
        WikiPageVersionRepository versions = mock(WikiPageVersionRepository.class);
        when(versions.findMaxRevision(anyString())).thenReturn(1L);
        return new RestoreApplier(mock(OperationLogRepository.class), changes, wiki, versions, contributions);
    }

    private void givenContributions(Map<String, List<WikiPageContribution>> state) {
        List<WikiPageContribution> all = new ArrayList<>();
        state.values().forEach(all::addAll);
        when(contributions.findByPageIds(any())).thenReturn(all);
    }

    private void givenChanges(String lintId, OperationChange... rows) {
        when(changes.findByOperationIdOrderByIdAsc(lintId)).thenReturn(List.of(rows));
        when(changes.existsByResourceIdAndIdGreaterThan(anyString(), anyLong())).thenReturn(false);
    }

    private static Map<String, List<String>> signatures(Map<String, List<WikiPageContribution>> state) {
        Map<String, List<String>> expected = new LinkedHashMap<>();
        state.forEach((pageId, rows) -> expected.put(pageId, rows.stream()
                .map(row -> row.getIngestOperationId() + ":" + row.getSequenceRevision()
                        + ":" + (row.isActive() ? "1" : "0"))
                .toList()));
        return expected;
    }

    private static OperationChange change(long id, String pageId, ChangeType type) {
        OperationChange change = new OperationChange("lint", ResourceType.wiki_page, pageId,
                pageId, null, null, type, null, null, null);
        ReflectionTestUtils.setField(change, "id", id);
        return change;
    }

    private static OperationLog lint(String id) {
        return OperationLog.completed(id, WS, "user", OperationType.lint, null, "lint", 1, T);
    }

    private static OperationLog restoreOperation() {
        return OperationLog.applying("op_restore", WS, "user", null, "target", "{}", T);
    }

    private static WikiPageContribution ingest(String pageId, String operationId, long sequence) {
        return new WikiPageContribution(pageId, operationId, "doc_" + operationId, sequence,
                "wiki/" + WS + "/pages/" + pageId + "/ops/" + operationId + ".json", T);
    }

    private static WikiPageContribution lintRecord(String pageId, String lintId, long sequence) {
        return WikiPageContribution.lint(pageId, lintId, null, sequence,
                "wiki/" + WS + "/pages/" + pageId + "/ops/" + lintId + ".json", lintId, T);
    }

    private static WikiPageContribution linked(String pageId, String operationId, long sequence, String lintId) {
        return WikiPageContribution.lint(pageId, operationId, "doc_" + operationId, sequence,
                "wiki/" + WS + "/pages/" + pageId + "/ops/" + operationId + ".json", lintId, T);
    }

    private static PageRestorePlan page(RestorePlan plan, String pageId) {
        return plan.pages().stream().filter(page -> page.pageId().equals(pageId)).findFirst().orElseThrow();
    }

    private static List<String> kept(RestorePlan plan, String pageId) {
        PageRestorePlan page = page(plan, pageId);
        assertThat(page.action()).isEqualTo(RestoreAction.rebuild);
        return page.keepContributions().stream().map(PageRestorePlan.Kept::operationId).toList();
    }
}
