package fruition.core.wiki.controller;

import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalWikiContributionControllerTest {

    private final WikiPageContributionRepository repository =
            mock(WikiPageContributionRepository.class);
    private final InternalWikiContributionController controller =
            new InternalWikiContributionController(repository, "token");

    @Test
    void find_rejectsPageOutsideWorkspace() {
        when(repository.findPageIdsOutsideWorkspace(List.of("page-1"), "ws-1"))
                .thenReturn(List.of("page-1"));

        var response = controller.find(
                "token",
                new InternalWikiContributionController.ContributionRequest(
                        List.of("page-1"), "ws-1"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void find_allowsLegacyPageWithoutContributions() {
        when(repository.findPageIdsOutsideWorkspace(List.of("legacy-page"), "ws-1"))
                .thenReturn(List.of());
        when(repository.findByPageIdsAndWorkspaceId(List.of("legacy-page"), "ws-1"))
                .thenReturn(List.of());

        var response = controller.find(
                "token",
                new InternalWikiContributionController.ContributionRequest(
                        List.of("legacy-page"), "ws-1"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void find_returnsScopedContributions() {
        WikiPageContribution contribution = mock(WikiPageContribution.class);
        when(contribution.getPageId()).thenReturn("page-1");
        when(contribution.getObjectKey()).thenReturn("wiki/ws-1/page-1.json");
        when(contribution.isActive()).thenReturn(true);
        when(repository.findPageIdsOutsideWorkspace(List.of("page-1"), "ws-1"))
                .thenReturn(List.of());
        when(repository.findByPageIdsAndWorkspaceId(List.of("page-1"), "ws-1"))
                .thenReturn(List.of(contribution));

        var response = controller.find(
                "token",
                new InternalWikiContributionController.ContributionRequest(
                        List.of("page-1"), "ws-1"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
