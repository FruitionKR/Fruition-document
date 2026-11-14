package fruition.core.wiki.controller;

import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalWikiContributionControllerTest {

    private final WikiPageContributionRepository repository =
            mock(WikiPageContributionRepository.class);
    private final InternalWikiContributionController controller =
            new InternalWikiContributionController(repository, "token");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void find_reportsAuthoritativeSourceDeletion(boolean deleted) {
        var contribution = WikiPageContribution.lint("page-1", "ingest-1", "doc-1",
                1, "wiki/ws-1/page-1.json", null, java.time.Instant.now());
        when(repository.findByPageIdsAndWorkspaceId(List.of("page-1"), "ws-1"))
                .thenReturn(List.of(contribution));
        when(repository.findDeletedSourceDocumentIds(List.of("doc-1"), "ws-1"))
                .thenReturn(deleted ? List.of("doc-1") : List.of());

        var response = controller.find("token",
                new InternalWikiContributionController.ContributionRequest(List.of("page-1"), "ws-1"));
        var row = new ObjectMapper().valueToTree(response.getBody()).get(0);
        assertThat(row.get("source_document_deleted").asBoolean()).isEqualTo(deleted);
        assertThat(row.get("active").asBoolean()).isEqualTo(!deleted);
    }

    @ParameterizedTest
    @CsvSource({"ingest-1,,", "lint-1,lint-1,", "ingest-1,lint-1,lint-1"})
    void find_exposesLinkingLintForAiReconciliation(String operationId, String lintId, String expected) {
        var contribution = WikiPageContribution.lint("page-1", operationId, null,
                1, "wiki/ws-1/page-1.json", lintId, java.time.Instant.now());
        when(repository.findByPageIdsAndWorkspaceId(List.of("page-1"), "ws-1"))
                .thenReturn(List.of(contribution));

        var response = controller.find("token",
                new InternalWikiContributionController.ContributionRequest(List.of("page-1"), "ws-1"));
        var row = new ObjectMapper().valueToTree(response.getBody()).get(0);

        assertThat(row.has("lint_operation_id")).isTrue();
        assertThat(row.get("lint_operation_id").asText(null)).isEqualTo(expected);
    }

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
