package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.document.domain.DocumentContentVersion;
import fruition.core.document.dto.DocumentContentDiffResponse;
import fruition.core.document.exception.MarkdownDiffTooLargeException;
import fruition.core.document.repository.DocumentContentVersionRepository;
import fruition.core.document.service.MarkdownDiffService;
import fruition.core.wiki.domain.WikiPageVersion;
import fruition.core.wiki.repository.WikiPageVersionRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 로그 상세에 실을 변경분. 한 항목이 실패해도 나머지는 정상이어야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChangeDiffLoaderTest {

    private static final String OPERATION = "op_a2";
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Mock WikiPageVersionRepository wikiVersionRepository;
    @Mock DocumentContentVersionRepository documentVersionRepository;

    private ChangeDiffLoader loader;

    @BeforeEach
    void setUp() {
        loader = new ChangeDiffLoader(wikiVersionRepository, documentVersionRepository,
                new MarkdownDiffService());
    }

    @Test
    @DisplayName("Wiki 페이지 갱신은 두 revision 본문을 읽어 변경분을 만든다")
    void computesWikiDiff() {
        givenWikiVersion("wp_C3", 3, "# 제목\n첫 줄\n");
        givenWikiVersion("wp_C3", 4, "# 제목\n첫 줄\n둘째 줄\n");

        ChangeDiffLoader.Diff diff = loadOne(
                change(ResourceType.wiki_page, "wp_C3", 3L, 4L, ChangeType.updated));

        assertThat(diff.hunks()).isNotEmpty();
        assertThat(diff.tooLarge()).isFalse();
    }

    @Test
    @DisplayName("문서 편집은 문서 버전 테이블에서 읽는다")
    void computesDocumentDiff() {
        givenDocumentVersion("doc_A", 5, "원래 문단\n");
        givenDocumentVersion("doc_A", 6, "다듬은 문단\n");

        ChangeDiffLoader.Diff diff = loadOne(
                change(ResourceType.document, "doc_A", 5L, 6L, ChangeType.updated));

        assertThat(diff.hunks()).isNotEmpty();
        verify(wikiVersionRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("새로 만든 리소스는 빈 본문과 비교해 전체 내용을 추가로 표시한다")
    void showsAllContentWhenCreated() {
        givenWikiVersion("wp_C3", 1, "# 제목\n첫 줄");

        ChangeDiffLoader.Diff diff = loadOne(
                change(ResourceType.wiki_page, "wp_C3", null, 1L, ChangeType.created));

        assertThat(diff.hunks()).hasSize(1);
        assertThat(diff.hunks().get(0).lines())
                .extracting(DocumentContentDiffResponse.Line::type,
                        DocumentContentDiffResponse.Line::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(DocumentContentDiffResponse.Type.ADD, "# 제목"),
                        org.assertj.core.groups.Tuple.tuple(DocumentContentDiffResponse.Type.ADD, "첫 줄"));
        assertThat(diff.tooLarge()).isFalse();
        verify(wikiVersionRepository, times(1)).findAllById(any());
    }

    @Test
    @DisplayName("삭제·위임·재작성실패는 after_revision이 없어 건너뛴다")
    void skipsWhenNoAfterRevision() {
        assertThat(loadOne(change(ResourceType.wiki_page, "wp_C6", 4L, null,
                ChangeType.deleted)).hunks()).isNull();
        assertThat(loadOne(change(ResourceType.wiki_page, "wp_C3", 4L, null,
                ChangeType.delegated)).hunks()).isNull();
        assertThat(loadOne(change(ResourceType.wiki_page, "wp_C7", 4L, null,
                ChangeType.rebuild_failed)).hunks()).isNull();
    }

    @Test
    @DisplayName("버전 행이 없으면 조용히 건너뛴다")
    void skipsWhenVersionMissing() {
        givenWikiVersion("wp_C3", 3, "# 제목\n");   // revision 4는 넣지 않는다

        ChangeDiffLoader.Diff diff = loadOne(
                change(ResourceType.wiki_page, "wp_C3", 3L, 4L, ChangeType.updated));

        assertThat(diff.hunks()).isNull();
        assertThat(diff.tooLarge()).isFalse();
    }

    @Test
    @DisplayName("계산이 거부되면 그 항목만 표시하고 예외를 올리지 않는다")
    void marksTooLargeInsteadOfThrowing() {
        MarkdownDiffService refusing = new MarkdownDiffService() {
            @Override
            public fruition.core.document.dto.MarkdownDiff diff(long fromVersion, String before,
                                                           long toVersion, String after) {
                throw new MarkdownDiffTooLargeException("너무 큽니다.");
            }
        };
        loader = new ChangeDiffLoader(wikiVersionRepository, documentVersionRepository, refusing);
        givenWikiVersion("wp_C3", 3, "가");
        givenWikiVersion("wp_C3", 4, "나");

        ChangeDiffLoader.Diff diff = loadOne(
                change(ResourceType.wiki_page, "wp_C3", 3L, 4L, ChangeType.updated));

        assertThat(diff.hunks()).isNull();
        assertThat(diff.tooLarge()).isTrue();
    }

    @Test
    @DisplayName("변경내역이 여러 건이어도 리소스 종류마다 한 번만 조회한다")
    void loadsVersionsInOneQueryPerResourceType() {
        for (int i = 1; i <= 10; i++) {
            givenWikiVersion("wp_" + i, 1, "이전 " + i);
            givenWikiVersion("wp_" + i, 2, "이후 " + i);
        }
        List<OperationChange> changes = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            changes.add(change(ResourceType.wiki_page, "wp_" + i, 1L, 2L, ChangeType.updated));
        }
        when(wikiVersionRepository.findAllById(any())).thenReturn(wikiVersions);
        when(documentVersionRepository.findAllById(any())).thenReturn(documentVersions);

        List<ChangeDiffLoader.Diff> diffs = loader.load(changes);

        assertThat(diffs).hasSize(10).allSatisfy(d -> assertThat(d.hunks()).isNotEmpty());
        verify(wikiVersionRepository, times(1)).findAllById(any());
        verify(documentVersionRepository, never()).findAllById(any());
    }

    // --- helpers ---

    private OperationChange change(ResourceType type, String resourceId,
                                   Long before, Long after, ChangeType changeType) {
        return new OperationChange(OPERATION, type, resourceId, before, after,
                changeType, null, null, null);
    }

    private final List<WikiPageVersion> wikiVersions = new java.util.ArrayList<>();
    private final List<DocumentContentVersion> documentVersions = new java.util.ArrayList<>();

    private ChangeDiffLoader.Diff loadOne(OperationChange change) {
        when(wikiVersionRepository.findAllById(any())).thenReturn(wikiVersions);
        when(documentVersionRepository.findAllById(any())).thenReturn(documentVersions);
        return loader.load(List.of(change)).get(0);
    }

    private void givenWikiVersion(String pageId, long revision, String markdown) {
        wikiVersions.add(new WikiPageVersion(pageId, revision, 1, markdown,
                "wiki/key.md", "sha256:x", OPERATION, "user_1", NOW));
    }

    private void givenDocumentVersion(String documentId, long version, String markdown) {
        documentVersions.add(new DocumentContentVersion(documentId, version, markdown,
                "sha256:x", "user_1", NOW));
    }
}
