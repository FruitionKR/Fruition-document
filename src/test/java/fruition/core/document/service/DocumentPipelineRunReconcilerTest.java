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
