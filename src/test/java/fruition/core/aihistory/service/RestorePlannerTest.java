package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.RestoreAction;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 복구 판정 시나리오. 기존 pipeline 계약대로 Source는 복원하고 Concept는 재작성한다.
 *
 * <p>기준 상태는 {@code A → B → C → A2 → D} 순서의 ingest다. 괄호 안 숫자는 그 기여가 만든 revision이다.
 *
 * <pre>
 * S_A  A(1)
 * C1   A(1)
 * C2   A(1)  B(2)  A2(3)
 * C3   A(1)  B(2)  C(3)  A2(4)  D(5)
 * C4   A(1)  C(2)
 * C5   A(1)  A2(2)
 * C6   A2(1) D(2)
 * </pre>
 */
class RestorePlannerTest {

    private final PipelineWikiStateRequester wiki = mock(PipelineWikiStateRequester.class);
    private final RestorePlanner planner = new RestorePlanner(wiki);

    @BeforeEach
    void setUp() {
        when(wiki.lookup(any(), eq("ws_test"))).thenReturn(List.of(
                new PipelineWikiStateRequester.WikiPageSnapshot(
                        "S_A", "source", "원문", "source", "ws_test", "active")));
    }

    private static final String OP_A = "op_a";
    private static final String OP_B = "op_b";
    private static final String OP_C = "op_c";
    private static final String OP_A2 = "op_a2";
    private static final String OP_D = "op_d";

    /** 기준 상태의 페이지별 활성 기여. 실제 조회는 sequence_revision 순으로 정렬해 넘긴다. */
    private Map<String, List<WikiPageContribution>> baseState() {
        Map<String, List<WikiPageContribution>> state = new LinkedHashMap<>();
        state.put("S_A", contributions("S_A", entry(OP_A, "doc_A", 1)));
        state.put("C1", contributions("C1", entry(OP_A, "doc_A", 1)));
        state.put("C2", contributions("C2",
                entry(OP_A, "doc_A", 1), entry(OP_B, "doc_B", 2), entry(OP_A2, "doc_A", 3)));
        state.put("C3", contributions("C3",
                entry(OP_A, "doc_A", 1), entry(OP_B, "doc_B", 2), entry(OP_C, "doc_C", 3),
                entry(OP_A2, "doc_A", 4), entry(OP_D, "doc_D", 5)));
        state.put("C4", contributions("C4",
                entry(OP_A, "doc_A", 1), entry(OP_C, "doc_C", 2)));
        state.put("C5", contributions("C5",
                entry(OP_A, "doc_A", 1), entry(OP_A2, "doc_A", 2)));
        state.put("C6", contributions("C6",
                entry(OP_A2, "doc_A", 1), entry(OP_D, "doc_D", 2)));
        return state;
    }

    @Nested
    @DisplayName("기본 시나리오 5개")
    class BaseScenarios {

        @Test
        @DisplayName("D 취소 — C3·C6 모두 남은 기여로 재작성")
        void cancelD() {
            RestorePlan plan = planner.plan(Set.of(OP_D), baseState(), "ws_test");

            assertThat(plan.pages()).hasSize(2);
            assertRebuild(plan, "C3", 4, List.of(OP_A, OP_B, OP_C, OP_A2));
            assertRebuild(plan, "C6", 1, List.of(OP_A2));
        }

        @Test
        @DisplayName("A2 취소 — C2·C5·C3·C6 모두 남은 기여로 재작성")
        void cancelA2() {
            RestorePlan plan = planner.plan(Set.of(OP_A2), baseState(), "ws_test");

            assertThat(plan.pages()).hasSize(4);
            assertRebuild(plan, "C2", 2, List.of(OP_A, OP_B));
            assertRebuild(plan, "C5", 1, List.of(OP_A));
            assertRebuild(plan, "C3", 4, List.of(OP_A, OP_B, OP_C, OP_D));
            assertRebuild(plan, "C6", 1, List.of(OP_D));
        }

        @Test
        @DisplayName("C 취소 — C4·C3 모두 남은 기여로 재작성")
        void cancelC() {
            RestorePlan plan = planner.plan(Set.of(OP_C), baseState(), "ws_test");

            assertThat(plan.pages()).hasSize(2);
            assertRebuild(plan, "C4", 1, List.of(OP_A));
            assertRebuild(plan, "C3", 4, List.of(OP_A, OP_B, OP_A2, OP_D));
        }

        @Test
        @DisplayName("B 취소 — 둘 다 뒤에 다른 기여가 있어 재조립")
        void cancelB() {
            RestorePlan plan = planner.plan(Set.of(OP_B), baseState(), "ws_test");

            assertThat(plan.pages()).hasSize(2);
            assertRebuild(plan, "C2", 2, List.of(OP_A, OP_A2));
            assertRebuild(plan, "C3", 4, List.of(OP_A, OP_C, OP_A2, OP_D));
        }

        @Test
        @DisplayName("A 취소 — A·A2를 함께 빼면 S_A·C1·C5는 삭제, 나머지는 재조립")
        void cancelA() {
            RestorePlan plan = planner.plan(Set.of(OP_A, OP_A2), baseState(), "ws_test");

            assertThat(plan.pages()).hasSize(7);
            assertDelete(plan, "S_A");
            assertDelete(plan, "C1");
            assertDelete(plan, "C5");
            assertRebuild(plan, "C2", 1, List.of(OP_B));
            assertRebuild(plan, "C3", 3, List.of(OP_B, OP_C, OP_D));
            assertRebuild(plan, "C4", 1, List.of(OP_C));
            assertRebuild(plan, "C6", 1, List.of(OP_D));
        }
    }

    @Nested
    @DisplayName("연속 복구")
    class SequentialRestore {

        @Test
        @DisplayName("A2 취소 후 D 취소 — 비활성 A2가 든 revision 4를 목적지로 고르지 않는다")
        void cancelA2ThenD() {
            // A2 취소가 끝난 상태. A2 기여는 행으로 남아 있고 active만 꺼져 있다.
            Map<String, List<WikiPageContribution>> afterA2 = new LinkedHashMap<>();
            afterA2.put("C3", contributions("C3",
                    entry(OP_A, "doc_A", 1), entry(OP_B, "doc_B", 2), entry(OP_C, "doc_C", 3),
                    inactive(OP_A2, "doc_A", 4), entry(OP_D, "doc_D", 5)));
            afterA2.put("C6", contributions("C6",
                    inactive(OP_A2, "doc_A", 1), entry(OP_D, "doc_D", 2)));

            RestorePlan plan = planner.plan(Set.of(OP_D), afterA2, "ws_test");

            // 과거 revision 유무와 무관하게 활성 기여 A+B+C만 재작성에 전달한다.
            assertRebuild(plan, "C3", 3, List.of(OP_A, OP_B, OP_C));
            // C6는 D를 빼면 받치는 기여가 없어 삭제된다.
            assertDelete(plan, "C6");
        }

        @Test
        @DisplayName("A 취소 후 A3 ingest — 과거 A 기여는 비활성으로 남고 A3만 새 기여가 된다")
        void cancelAThenReingest() {
            // A 취소로 A·A2가 비활성이 된 뒤 A3가 들어와 C3에 기여한 상태.
            Map<String, List<WikiPageContribution>> afterA = new LinkedHashMap<>();
            afterA.put("C3", contributions("C3",
                    inactive(OP_A, "doc_A", 1), entry(OP_B, "doc_B", 2), entry(OP_C, "doc_C", 3),
                    inactive(OP_A2, "doc_A", 4), entry(OP_D, "doc_D", 5),
                    entry("op_a3", "doc_A", 7)));

            RestorePlan plan = planner.plan(Set.of(OP_B), afterA, "ws_test");

            // B는 가운데라 재조립. 비활성인 A·A2가 남은 기여에 다시 끼지 않는다.
            assertRebuild(plan, "C3", 3, List.of(OP_C, OP_D, "op_a3"));
        }

        @Test
        @DisplayName("비활성 기여가 앞에 있으면 스냅샷을 쓸 수 없어 재조립이 된다")
        void inactiveBeforeKeptForcesRebuild() {
            Map<String, List<WikiPageContribution>> state = new LinkedHashMap<>();
            state.put("C9", contributions("C9",
                    inactive(OP_A, "doc_A", 1), entry(OP_B, "doc_B", 2), entry(OP_C, "doc_C", 3)));

            RestorePlan plan = planner.plan(Set.of(OP_C), state, "ws_test");

            // 남길 것은 B뿐인데 revision 2는 A까지 담고 있어 그대로 쓸 수 없다.
            assertRebuild(plan, "C9", 1, List.of(OP_B));
        }
    }

    @Nested
    @DisplayName("다중 버전 시나리오")
    class SinceScenarios {

        @Test
        @DisplayName("A를 5번 ingest하고 op_a2 지목 — 3번째가 만든 페이지도 다른 문서가 붙었으면 남는다")
        void sinceWithSharedPage() {
            Map<String, List<WikiPageContribution>> state = new LinkedHashMap<>();
            state.put("S_A", contributions("S_A",
                    entry("op_a1", "doc_A", 1), entry("op_a2", "doc_A", 2),
                    entry("op_a3", "doc_A", 3), entry("op_a4", "doc_A", 4),
                    entry("op_a5", "doc_A", 5)));
            state.put("C1", contributions("C1",
                    entry("op_a1", "doc_A", 1), entry("op_a2", "doc_A", 2),
                    entry("op_a3", "doc_A", 3), entry("op_a5", "doc_A", 4)));
            state.put("C2", contributions("C2", entry("op_a2", "doc_A", 1)));
            state.put("C7", contributions("C7",
                    entry("op_a3", "doc_A", 1), entry(OP_B, "doc_B", 2)));
            state.put("C8", contributions("C8", entry("op_a4", "doc_A", 1)));

            RestorePlan plan = planner.plan(Set.of("op_a3", "op_a4", "op_a5"), state, "ws_test");

            assertRestore(plan, "S_A", 2);
            assertRebuild(plan, "C1", 2, List.of("op_a1", "op_a2"));
            assertRebuild(plan, "C7", 1, List.of(OP_B));
            assertDelete(plan, "C8");
            // C2는 op_a2가 만든 페이지이고 제외 대상이 건드리지 않아 후보가 아니다.
            assertThat(pageIds(plan)).doesNotContain("C2");
        }

        @Test
        @DisplayName("A1 A2 A3 E A4 F 순서 — 사이에 다른 문서가 끼면 복원이 재조립으로 바뀐다")
        void sinceWithInterleavedDocuments() {
            Map<String, List<WikiPageContribution>> state = new LinkedHashMap<>();
            state.put("C1", contributions("C1",
                    entry("op_a1", "doc_A", 1), entry("op_a2", "doc_A", 2),
                    entry("op_a3", "doc_A", 3), entry("op_e", "doc_E", 4),
                    entry("op_a4", "doc_A", 5), entry("op_f", "doc_F", 6)));
            state.put("C2", contributions("C2", entry("op_a2", "doc_A", 1)));
            state.put("C3", contributions("C3",
                    entry("op_a3", "doc_A", 1), entry("op_e", "doc_E", 2)));
            state.put("C5", contributions("C5", entry("op_a4", "doc_A", 1)));

            RestorePlan plan = planner.plan(Set.of("op_a3", "op_a4"), state, "ws_test");

            // 뺄 것 사이에 op_e·op_f가 끼어 있어 "a1+a2+e+f" 조합의 본문이 존재한 적이 없다.
            assertRebuild(plan, "C1", 4, List.of("op_a1", "op_a2", "op_e", "op_f"));
            assertRebuild(plan, "C3", 1, List.of("op_e"));
            assertDelete(plan, "C5");
            assertThat(pageIds(plan)).doesNotContain("C2");
        }
    }

    @Nested
    @DisplayName("경계")
    class EdgeCases {

        @Test
        @DisplayName("제외 대상이 건드리지 않은 페이지는 후보에 들어가지 않는다")
        void untouchedPageIsNotCandidate() {
            RestorePlan plan = planner.plan(Set.of(OP_D), baseState(), "ws_test");

            assertThat(pageIds(plan)).containsExactlyInAnyOrder("C3", "C6");
        }

        @Test
        @DisplayName("제외 집합이 비면 아무 페이지도 계획에 없다")
        void emptyExclusion() {
            assertThat(planner.plan(Set.of(), baseState(), "ws_test").pages()).isEmpty();
        }

        @Test
        @DisplayName("계획에는 삭제·복원·재조립 건수가 함께 담긴다")
        void planSummary() {
            RestorePlan plan = planner.plan(Set.of(OP_A, OP_A2), baseState(), "ws_test");

            assertThat(plan.deleteCount()).isEqualTo(3);
            assertThat(plan.restoreCount()).isZero();
            assertThat(plan.rebuildCount()).isEqualTo(4);
        }
    }

    // --- helpers ---

    private record Entry(String operationId, String documentId, long sequenceRevision, boolean active) {}

    private static Entry entry(String operationId, String documentId, long sequenceRevision) {
        return new Entry(operationId, documentId, sequenceRevision, true);
    }

    /** 이전 복구로 이미 걷어낸 기여. 행은 남아 있고 active만 꺼져 있다. */
    private static Entry inactive(String operationId, String documentId, long sequenceRevision) {
        return new Entry(operationId, documentId, sequenceRevision, false);
    }

    private static List<WikiPageContribution> contributions(String pageId, Entry... entries) {
        List<WikiPageContribution> list = new ArrayList<>();
        for (Entry e : entries) {
            WikiPageContribution c = new WikiPageContribution(pageId, e.operationId(), e.documentId(),
                    e.sequenceRevision(), "wiki/ws/pages/" + pageId + "/ops/" + e.operationId() + ".json",
                    Instant.parse("2026-07-20T00:00:00Z"));
            if (!e.active()) {
                c.deactivate("op_previous_restore");
            }
            list.add(c);
        }
        return list;
    }

    private static List<String> pageIds(RestorePlan plan) {
        return plan.pages().stream().map(PageRestorePlan::pageId).toList();
    }

    private static PageRestorePlan find(RestorePlan plan, String pageId) {
        return plan.pages().stream()
                .filter(p -> p.pageId().equals(pageId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(pageId + " 계획이 없습니다"));
    }

    private static void assertDelete(RestorePlan plan, String pageId) {
        PageRestorePlan page = find(plan, pageId);
        assertThat(page.action()).isEqualTo(RestoreAction.delete);
        assertThat(page.contributionCount()).isZero();
    }

    private static void assertRestore(RestorePlan plan, String pageId, long targetRevision) {
        PageRestorePlan page = find(plan, pageId);
        assertThat(page.action()).isEqualTo(RestoreAction.restore);
        assertThat(page.targetRevision()).isEqualTo(targetRevision);
    }

    private static void assertRebuild(RestorePlan plan, String pageId,
                                      int contributionCount, List<String> keepOperationIds) {
        PageRestorePlan page = find(plan, pageId);
        assertThat(page.action()).isEqualTo(RestoreAction.rebuild);
        assertThat(page.contributionCount()).isEqualTo(contributionCount);
        assertThat(page.keepContributions().stream().map(PageRestorePlan.Kept::operationId).toList())
                .containsExactlyElementsOf(keepOperationIds);
    }
}
