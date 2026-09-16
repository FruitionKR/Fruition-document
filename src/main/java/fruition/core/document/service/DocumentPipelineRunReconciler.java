package fruition.core.document.service;

import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.PipelineRunStatusRequester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DocumentPipelineRunReconciler {

    private static final Logger log = LoggerFactory.getLogger(DocumentPipelineRunReconciler.class);

    private final DocumentRepository documentRepository;
    private final PipelineRunStatusRequester requester;
    private final DocumentService documentService;

    public DocumentPipelineRunReconciler(DocumentRepository documentRepository,
                                         PipelineRunStatusRequester requester,
                                         DocumentService documentService) {
        this.documentRepository = documentRepository;
        this.requester = requester;
        this.documentService = documentService;
    }

    @Scheduled(fixedDelayString = "${app.pipeline-run.poll-interval-ms:3000}")
    public void reconcile() {
        for (var document : documentRepository
                .findAllByStatusAndPipelineRunIdIsNotNull(DocumentStatus.processing)) {
            try {
                requester.find(document.getPipelineRunId()).ifPresent(run -> {
                    if (!document.getId().equals(run.documentId())) {
                        return;
                    }
                    if ("succeeded".equals(run.status()) || "failed".equals(run.status())) {
                        documentService.applyPipelineResult(
                                document.getId(), run.id(), run.status(), run.error());
                    }
                });
            } catch (RuntimeException e) {
                log.warn("[pipeline run 조회 실패] documentId={} runId={} error={}",
                        document.getId(), document.getPipelineRunId(), e.getMessage());
            }
        }
    }
}
