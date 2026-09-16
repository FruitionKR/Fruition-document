package fruition.core.document.service;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.PipelineRunStatusRequester;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentPipelineRunReconcilerTest {

    @Mock DocumentRepository documentRepository;
    @Mock PipelineRunStatusRequester requester;
    @Mock DocumentService documentService;

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

        new DocumentPipelineRunReconciler(documentRepository, requester, documentService).reconcile();

        verify(documentService).applyPipelineResult("doc-1", "run-1", "succeeded", null);
    }
}
