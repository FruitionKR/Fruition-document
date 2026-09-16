package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.exception.RestorePreviewStaleException;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.domain.WikiPageVersion;
import fruition.core.wiki.domain.WikiPageVersionId;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 복구 반영. 잠금을 잡은 뒤 계획을 만들 때 본 상태와 다르면(TOCTOU) 반영을 거부해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class RestoreApplierTest {

    private static final String WORKSPACE = "ws_1";
    private static final String USER = "user_1";
    private static final Instant T = Instant.parse("2026-07-28T10:00:00Z");

    @Mock OperationLogRepository operationLogRepository;
    @Mock OperationChangeRepository operationChangeRepository;
    @Mock PipelineWikiStateRequester wikiStateRequester;
    @Mock WikiPageVersionRepository versionRepository;
    @Mock WikiPageContributionRepository contributionRepository;

    private RestoreApplier applier;

    @BeforeEach
    void setUp() {
        applier = new RestoreApplier(operationLogRepository, operationChangeRepository,
                wikiStateRequester, versionRepository, contributionRepository);
        org.mockito.Mockito.lenient().when(wikiStateRequester.lookup(any(), any()))
                .thenAnswer(invocation -> ((List<String>) invocation.getArgument(0)).stream()
                        .map(id -> new PipelineWikiStateRequester.WikiPageSnapshot(
                                id, "source", "제목", "title", WORKSPACE, "active"))
                        .toList());
    }

    @Test
    @DisplayName("잠금 아래서 다시 읽은 기여가 계획을 만들 때와 같으면 그대로 반영한다")
    void appliesWhenContributionsUnchanged() {
        OperationLog restore = OperationLog.applying(
                "op_restore", WORKSPACE, USER, "doc_A", "op_a2", "{}", T);
        RestorePlan plan = new RestorePlan(
                List.of(PageRestorePlan.restore("wp_S_A", 1L, "op_a1", 1)));
        WikiPageContribution kept = contribution("wp_S_A", "op_a1", 1, true);
        Map<String, List<String>> expected = Map.of("wp_S_A", List.of("op_a1:1:1"));

        when(contributionRepository.findByPageIds(any())).thenReturn(List.of(kept));
        when(versionRepository.findById(new WikiPageVersionId("wp_S_A", 1L)))
                .thenReturn(Optional.of(version("wp_S_A", 1L)));
        when(versionRepository.findMaxRevision("wp_S_A")).thenReturn(1L);

        applier.apply(restore, plan, Set.of(), expected, T);

        verify(operationLogRepository).save(restore);
        ArgumentCaptor<OperationChange> captor = ArgumentCaptor.forClass(OperationChange.class);
        verify(operationChangeRepository).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getResourceDisplayName()).isEqualTo("제목");
    }

    @Test
    @DisplayName("잠금 아래서 다시 읽은 기여가 계획 당시와 다르면 반영을 거부한다")
    void rejectsWhenContributionsChangedUnderLock() {
        OperationLog restore = OperationLog.applying(
                "op_restore", WORKSPACE, USER, "doc_A", "op_a2", "{}", T);
        RestorePlan plan = new RestorePlan(
                List.of(PageRestorePlan.restore("wp_S_A", 1L, "op_a1", 1)));
        // 계획을 세울 때 본 상태: 기여 1건.
        WikiPageContribution planned = contribution("wp_S_A", "op_a1", 1, true);
        Map<String, List<String>> expected = Map.of("wp_S_A", List.of("op_a1:1:1"));

        // 잠금을 잡고 다시 읽었더니 그사이 동시 ingest가 새 기여를 하나 더 얹었다.
        WikiPageContribution concurrent = contribution("wp_S_A", "op_new", 2, true);
        when(contributionRepository.findByPageIds(any())).thenReturn(List.of(planned, concurrent));

        assertThatThrownBy(() -> applier.apply(restore, plan, Set.of(), expected, T))
                .isInstanceOf(RestorePreviewStaleException.class);

        verify(operationLogRepository, never()).save(any());
        verify(versionRepository, never()).save(any());
    }

    @Test
    @DisplayName("잠금 아래서 다시 읽었더니 기여가 비활성화됐으면 반영을 거부한다")
    void rejectsWhenContributionDeactivatedUnderLock() {
        OperationLog restore = OperationLog.applying(
                "op_restore", WORKSPACE, USER, "doc_A", "op_a2", "{}", T);
        RestorePlan plan = new RestorePlan(
                List.of(PageRestorePlan.restore("wp_S_A", 1L, "op_a1", 1)));
        WikiPageContribution plannedActive = contribution("wp_S_A", "op_a1", 1, true);
        Map<String, List<String>> expected = Map.of("wp_S_A", List.of("op_a1:1:1"));

        // 다른 복구가 그사이 같은 기여를 이미 꺼버렸다.
        WikiPageContribution nowInactive = contribution("wp_S_A", "op_a1", 1, false);
        when(contributionRepository.findByPageIds(any())).thenReturn(List.of(nowInactive));

        assertThatThrownBy(() -> applier.apply(restore, plan, Set.of(), expected, T))
                .isInstanceOf(RestorePreviewStaleException.class);

        verify(operationLogRepository, never()).save(any());
    }

    private WikiPageContribution contribution(String pageId, String opId, long seq, boolean active) {
        WikiPageContribution contribution =
                new WikiPageContribution(pageId, opId, "doc_" + opId, seq, "wiki/frag.md", T);
        if (!active) {
            contribution.deactivate("op_other_restore");
        }
        return contribution;
    }

    private WikiPageVersion version(String pageId, long revision) {
        return new WikiPageVersion(pageId, revision, 1, "본문", "wiki/key.md", "hash",
                "op_a1", USER, T);
    }
}
