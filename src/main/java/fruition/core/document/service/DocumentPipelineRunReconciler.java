package fruition.core.document.service;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.PipelineRunStatusRequester;
import org.slf4j.Logger;
import fruition.shared.logging.RepeatedFailureLogger;
import fruition.shared.scheduling.SchedulerLease;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class DocumentPipelineRunReconciler {

    private static final Logger log = LoggerFactory.getLogger(DocumentPipelineRunReconciler.class);
    private final RepeatedFailureLogger failures = new RepeatedFailureLogger(log);

    private final DocumentRepository documentRepository;
    private final PipelineRunStatusRequester requester;
    private final DocumentService documentService;
    private final SchedulerLease lease;
    private final Duration hold;

    public DocumentPipelineRunReconciler(DocumentRepository documentRepository,
                                         PipelineRunStatusRequester requester,
                                         DocumentService documentService,
                                         SchedulerLease lease,
                                         @Value("${app.pipeline-run.poll-interval-ms:3000}") long intervalMs) {
        this.documentRepository = documentRepository;
        this.requester = requester;
        this.documentService = documentService;
        this.lease = lease;
        this.hold = SchedulerLease.forInterval(intervalMs);
    }

    /**
     * 처리 중 문서가 있을 때만 점유를 시도하고, 점유한 Pod만 AI에 묻는다. 결과는 여러 Pod가 물어도 같고
     * AI 상태 조회만 Pod 수만큼 반복됐다. 일이 없을 때는 점유 쿼리도 보내지 않아 놀고 있는 서버의 쿼리 수가 그대로다.
     */
    @Scheduled(fixedDelayString = "${app.pipeline-run.poll-interval-ms:3000}")
    public void runScheduled() {
        List<Document> documents = processing();
        if (!documents.isEmpty() && lease.tryAcquire("pipeline_run_reconcile", hold)) {
            reconcile(documents);
        }
    }

    public void reconcile() {
        reconcile(processing());
    }

    private List<Document> processing() {
        return documentRepository.findAllByStatusAndPipelineRunIdIsNotNull(DocumentStatus.processing);
    }

    private void reconcile(List<Document> documents) {
        for (var document : documents) {
            // 변환 placeholder의 runId는 로컬 값이라 AI pipeline run API에 없다.
            if (document.getPipelineRunId().startsWith("convert:")) {
                continue;
            }
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
                failures.recovered(document.getPipelineRunId());
            } catch (RuntimeException e) {
                failures.warn(document.getPipelineRunId(), "[pipeline run 조회 실패] documentId="
                        + document.getId() + " runId=" + document.getPipelineRunId(), e);
            }
        }
    }
}
