package fruition.core.wiki.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.service.MarkdownDiffService;
import fruition.core.wiki.dto.WikiGraphNode;
import fruition.core.wiki.dto.WikiGraphResponse;
import fruition.core.wiki.dto.WikiPageRenameRequest;
import fruition.core.wiki.dto.WikiPageRenameResponse;
import fruition.core.wiki.exception.WikiPageNotFoundException;
import fruition.core.wiki.repository.PipelineWikiPageRequester;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiServiceTest {

    @Mock DocumentRepository documentRepository;
    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock PipelineWikiPageRequester pageRequester;
    @Mock PipelineWikiStateRequester stateRequester;
    @Mock WikiPageVersionRepository versionRepository;
    @Mock MarkdownDiffService markdownDiffService;
    WikiService service;

    @BeforeEach
    void setUp() {
        service = new WikiService(documentRepository, workspaceAccessGuard, pageRequester,
                stateRequester, versionRepository, markdownDiffService);
    }

    @Test
    void graphDelegatesToAiState() {
        WikiGraphResponse graph = new WikiGraphResponse(List.of(
                new WikiGraphNode("wp_1", "concept", "제목", "title", "요약", "active", null)),
                List.of());
        when(stateRequester.graph("ws_1")).thenReturn(graph);

        assertThat(service.findGraph("ws_1", "user_1").nodes()).hasSize(1);
        verify(workspaceAccessGuard).requireMember("ws_1", "user_1");
    }

    @Test
    void missingPageIsNotFound() {
        when(stateRequester.page("ws_1", "wp_1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById("ws_1", "user_1", "wp_1"))
                .isInstanceOf(WikiPageNotFoundException.class);
    }

    @Test
    void renameDelegates() {
        var request = new WikiPageRenameRequest("새 제목", true);
        var expected = new WikiPageRenameResponse("wp_1", "concept", "새 제목", "이전 제목",
                "new", "old", true, Instant.parse("2026-08-10T00:00:00Z"));
        when(pageRequester.rename("ws_1", "user_1", "wp_1", request)).thenReturn(expected);

        assertThat(service.rename("ws_1", "user_1", "wp_1", request)).isEqualTo(expected);
    }
}
