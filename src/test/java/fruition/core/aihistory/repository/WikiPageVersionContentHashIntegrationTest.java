package fruition.core.aihistory.repository;

import fruition.TestcontainersConfiguration;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.dto.OperationResultRequest;
import fruition.core.aihistory.service.LintOperationApplier;
import fruition.core.aihistory.service.LineCounter;
import fruition.core.wiki.domain.WikiPageVersion;
import fruition.core.wiki.domain.WikiPageVersionId;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WikiPageVersionContentHashIntegrationTest {

    private static final String PAGE_ID = "hash-width-page";
    private static final String CONTENT_HASH =
            "sha256:9d564752bf9b42a1f90fb7548ac6c6bd653f619ceeccd7f950bd5d60eaf2033a";

    @Autowired WikiPageVersionRepository versionRepository;
    @Autowired OperationLogRepository operationLogRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void lintApplierPersistsPrefixedSha256HashThroughRepository() {
        var operationChangeRepository = mock(fruition.core.aihistory.repository.OperationChangeRepository.class);
        var wikiStateRequester = mock(PipelineWikiStateRequester.class);
        var contributionRepository = mock(WikiPageContributionRepository.class);
        var lineCounter = mock(LineCounter.class);
        var applier = new LintOperationApplier(operationLogRepository, operationChangeRepository,
                wikiStateRequester, versionRepository, contributionRepository, lineCounter);
        OperationLog operation = OperationLog.processing(
                "op_hash_width", "ws_1", "user_1", OperationType.lint, null, Instant.now());
        when(wikiStateRequester.lookup(List.of(PAGE_ID), "ws_1")).thenReturn(List.of(
                new PipelineWikiStateRequester.WikiPageSnapshot(
                        PAGE_ID, "concept", "제목", "title", "ws_1", "active")));
        when(contributionRepository.countByIdPageIdAndActiveTrue(PAGE_ID)).thenReturn(0L);
        when(lineCounter.count(PAGE_ID, null, null, 1L, "# 본문"))
                .thenReturn(LineCounter.LineCount.none());

        transactionTemplate.executeWithoutResult(status -> {
            operationLogRepository.save(operation);
            applier.apply(
                    "op_hash_width",
                    new OperationResultRequest(
                            "op_hash_width", "lint", "succeeded", "ws_1", "user_1", null,
                            "완료", List.of(), List.of()),
                    List.of(new LintOperationApplier.LoadedPage(
                            PAGE_ID, "wiki/ws_1/pages/hash-width-page/ops/op_hash_width.md",
                            "# 본문", CONTENT_HASH)),
                    "payload-hash", Instant.now());
        });

        WikiPageVersion saved = versionRepository.findById(new WikiPageVersionId(PAGE_ID, 1L))
                .orElseThrow();
        assertThat(saved.getContentHash()).isEqualTo(CONTENT_HASH);
        assertThat(CONTENT_HASH).hasSize(71);
        Integer maxLength = jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'wiki_page_versions'
                  AND column_name = 'content_hash'
                """, Integer.class);
        assertThat(maxLength).isEqualTo(71);
    }
}
