package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.exception.InvalidRestoreRequestException;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 되돌릴 수 있는 대상인지 판단하는 규칙. 미리보기와 실행이 이것을 공유한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RestoreTargetValidatorTest {

    private static final String WORKSPACE = "ws_1";
    private static final String USER = "user_1";
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @Mock PipelineWikiStateRequester wikiStateRequester;

    private RestoreTargetValidator validator;

    @BeforeEach
    void setUp() {
        validator = new RestoreTargetValidator(wikiStateRequester);
    }

    @Test
    @DisplayName("document_edit·ingest·lint는 되돌릴 수 있다")
    void allowsRestorableTypes() {
        assertThatCode(() -> validator.requireRestorable(target(OperationType.document_edit)))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.requireRestorable(target(OperationType.ingest)))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.requireRestorable(target(OperationType.lint)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("되돌리기를 다시 되돌릴 수는 없다")
    void rejectsRestoreOfRestore() {
        assertThatThrownBy(() -> validator.requireRestorable(target(OperationType.restore)))
                .isInstanceOf(InvalidRestoreRequestException.class);
    }

    @Test
    @DisplayName("fresh cutover 이전 document_edit는 복구하지 않는다")
    void rejectsDocumentEditBlockedByFreshCutover() {
        OperationLog blocked = mock(OperationLog.class);
        when(blocked.isDocumentRestoreBlocked()).thenReturn(true);
        when(blocked.getOperationType()).thenReturn(OperationType.document_edit);

        assertThatThrownBy(() -> validator.requireRestorable(blocked))
                .isInstanceOf(InvalidRestoreRequestException.class)
                .hasMessageContaining("fresh cutover");
    }

    @Test
    @DisplayName("계획이 비면 거절한다")
    void rejectsEmptyPlan() {
        assertThatThrownBy(() ->
                validator.requireApplicable(target(OperationType.ingest), new RestorePlan(List.of())))
                .isInstanceOf(InvalidRestoreRequestException.class);
    }

    @Test
    @DisplayName("ingest는 원문 페이지를 page_type으로 찾아 돌려준다")
    void findsSourcePageByPageType() {
        RestorePlan plan = new RestorePlan(List.of(
                PageRestorePlan.rebuild("wp_C7", List.of()),
                PageRestorePlan.restore("wp_S_A", 2L, "op_a1", 1)));
        when(wikiStateRequester.lookup(List.of("wp_C7", "wp_S_A"), WORKSPACE)).thenReturn(List.of(
                new PipelineWikiStateRequester.WikiPageSnapshot(
                        "wp_S_A", "source", "제목", "title", WORKSPACE, "active")));

        PageRestorePlan sourcePage = validator.requireApplicable(target(OperationType.ingest), plan);

        // document_wiki_links 는 llmPipeline 이 관리해 문서 재처리 때 지워질 수 있다.
        assertThat(sourcePage.pageId()).isEqualTo("wp_S_A");
        assertThat(sourcePage.targetOperationId()).isEqualTo("op_a1");
    }

    @Test
    @DisplayName("ingest인데 원문 페이지가 없으면 거절한다")
    void rejectsIngestWithoutSourcePage() {
        RestorePlan plan = new RestorePlan(List.of(PageRestorePlan.rebuild("wp_C7", List.of())));
        when(wikiStateRequester.lookup(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> validator.requireApplicable(target(OperationType.ingest), plan))
                .isInstanceOf(InvalidRestoreRequestException.class)
                .hasMessageContaining("원문 페이지");
    }

    @Test
    @DisplayName("lint는 원문 페이지가 없어 조회하지 않는다")
    void lintNeedsNoSourcePage() {
        RestorePlan plan = new RestorePlan(List.of(PageRestorePlan.rebuild("wp_C3", List.of())));

        assertThat(validator.requireApplicable(target(OperationType.lint), plan)).isNull();
        verify(wikiStateRequester, never()).lookup(any(), any());
    }

    private OperationLog target(OperationType type) {
        return OperationLog.completed("op_a2", WORKSPACE, USER, type, "doc_A", "요약", 1, NOW);
    }
}
