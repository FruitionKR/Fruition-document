package fruition.core.document.service;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.PipelineRunStatusRequester;
import fruition.shared.scheduling.SchedulerLease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentPipelineRunReconcilerTest {

    @Test
    void reconcile_waitsForIndexThenCompletesBeforeQualityReview() throws Exception {
        Document document = new Document("doc-1", "ws-1", "user-1", "paper.md", "text/markdown", 1, "source", "hash");
        document.markPipelineStarted("run-1", java.time.Instant.now());
        when(documentRepository.findAllByStatusAndPipelineRunIdIsNotNull(DocumentStatus.processing)).thenReturn(List.of(document));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        when(requester.find("run-1")).thenReturn(Optional.of(new PipelineRunStatusRequester.PipelineRunStatus(
                "run-1", "doc-1", "ws-1", "user-1", "document", "succeeded", null,
                json.readTree("{\"post_ingest\":{\"status\":\"running\"}}"))));
        reconciler().reconcile();
        verify(documentService).applyPipelineResult("doc-1", "run-1", "indexing", null);
        when(requester.find("run-1")).thenReturn(Optional.of(new PipelineRunStatusRequester.PipelineRunStatus(
                "run-1", "doc-1", "ws-1", "user-1", "document", "succeeded", null,
                json.readTree("""
                        {"post_ingest":{"status":"running","embedding":
                          {"target_count":2,"embedded_count":1,"skipped_count":1,"failed_count":0}}}
                        """))));
        reconciler().reconcile();
        verify(documentService).applyPipelineResult("doc-1", "run-1", "succeeded", null);
    }

    @Test
    void readinessDistinguishesQualityReviewFromIncompleteOrFailedIndex() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String status : List.of("ready", "needs_review")) {
            var ready = new PipelineRunStatusRequester.PipelineRunStatus("run", "doc", "ws", "user", "document", "succeeded", null,
                    json.readTree("""
                            {"post_ingest":{"status":"%s","result":{"embedding":
                              {"target_count":1,"embedded_count":1,"skipped_count":0,"failed_count":0}}}}
                            """.formatted(status)));
            org.assertj.core.api.Assertions.assertThat(ready.documentStatus()).isEqualTo("succeeded");
        }
        for (String embedding : List.of("null", "{}",
                "{\"target_count\":2,\"embedded_count\":1,\"skipped_count\":0,\"failed_count\":1}",
                "{\"target_count\":2,\"embedded_count\":1,\"skipped_count\":0,\"failed_count\":0}")) {
            var failed = new PipelineRunStatusRequester.PipelineRunStatus("run", "doc", "ws", "user", "document", "succeeded", null,
                    json.readTree("{\"post_ingest\":{\"status\":\"needs_review\",\"result\":{\"embedding\":" + embedding + "}}}"));
            org.assertj.core.api.Assertions.assertThat(failed.documentStatus()).isEqualTo("failed");
            org.assertj.core.api.Assertions.assertThat(failed.documentError()).isEqualTo("wiki_index_preparation_failed");
        }
    }

    @Mock DocumentRepository documentRepository;
    @Mock PipelineRunStatusRequester requester;
    @Mock DocumentService documentService;
    @Mock SchedulerLease lease;

    DocumentPipelineRunReconciler reconciler() {
        return new DocumentPipelineRunReconciler(documentRepository, requester, documentService, lease, 3000);
    }

    @Test
    void scheduled_noProcessingDocuments_sendsNoLeaseQuery() {
        when(documentRepository.findAllByStatusAndPipelineRunIdIsNotNull(DocumentStatus.processing)).thenReturn(List.of());

        reconciler().runScheduled();

        verifyNoInteractions(lease, requester);
    }

    @Test
    void scheduled_otherPodHoldsLease_skipsAiLookup() {
        Document document = new Document(
                "doc-3", "ws-1", "user-1", "c.md", "text/markdown", 1,
                "sources/documents/doc-3/original", "hash");
        document.markPipelineStarted("run-3", java.time.Instant.now());
        when(documentRepository.findAllByStatusAndPipelineRunIdIsNotNull(DocumentStatus.processing))
                .thenReturn(List.of(document));
        when(lease.tryAcquire(anyString(), any())).thenReturn(false);

        reconciler().runScheduled();

        verifyNoInteractions(requester, documentService);
    }

    @Test
    void reconcile_succeededRun_appliesMatchingResult() {
        Document document = new Document(
                "doc-1", "ws-1", "user-1", "a.md", "text/markdown", 1,
                "sources/documents/doc-1/original", "hash");
        document.markPipelineStarted("run-1", java.time.Instant.now());
        when(documentRepository.findAllByStatusAndPipelineRunIdIsNotNull(DocumentStatus.processing))
                .thenReturn(List.of(document));
        when(requester.find("run-1")).thenReturn(Optional.of(
                new PipelineRunStatusRequester.PipelineRunStatus(
                        "run-1", "doc-1", "ws-1", "user-1", "document", "succeeded", null, null)));

        reconciler().reconcile();

        verify(documentService).applyPipelineResult("doc-1", "run-1", "succeeded", null);
    }

    @Test
    void reconcile_convertRunId_skipsAiLookup() {
        Document document = new Document(
                "doc-2", "ws-1", "user-1", "b.pdf", "application/pdf", 1,
                "sources/documents/doc-2/original", "hash");
        document.markPipelineStarted("convert:doc-2", java.time.Instant.now());
        when(documentRepository.findAllByStatusAndPipelineRunIdIsNotNull(DocumentStatus.processing))
                .thenReturn(List.of(document));

        reconciler().reconcile();

        verifyNoInteractions(requester, documentService);
    }
}
