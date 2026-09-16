package fruition.core.document.service;

import fruition.core.document.domain.DocumentConvertQueue;
import fruition.core.document.repository.DocumentConvertQueueRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** PDF → Markdown 변환 대기열 worker. {@link DocumentProcessingWorker}와 같은 방식으로 순차 처리한다. */
@Component
public class DocumentConvertWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentConvertWorker.class);

    private final DocumentConvertQueueRepository queueRepository;
    private final DocumentService documentService;
    private final TransactionTemplate transactionTemplate;

    public DocumentConvertWorker(DocumentConvertQueueRepository queueRepository,
                                 DocumentService documentService,
                                 TransactionTemplate transactionTemplate) {
        this.queueRepository = queueRepository;
        this.documentService = documentService;
        this.transactionTemplate = transactionTemplate;
    }

    @PostConstruct
    public void resetStuckItems() {
        transactionTemplate.execute(status -> {
            queueRepository.findAllByStatus("processing").forEach(item -> {
                log.warn("[변환 큐 리셋] document_id={} (서버 재시작으로 인한 stuck 복구)", item.getDocumentId());
                item.setStatus("pending");
                queueRepository.save(item);
            });
            return null;
        });
    }

    @Scheduled(fixedDelay = 2000)
    public void processNext() {
        DocumentConvertQueue picked = transactionTemplate.execute(status ->
                queueRepository.findFirstByStatusOrderByCreatedAtAsc("pending")
                        .map(item -> {
                            log.info("[문서 변환 큐 선택] documentId={} status=pending->processing",
                                    item.getDocumentId());
                            item.setStatus("processing");
                            queueRepository.save(item);
                            return item;
                        })
                        .orElse(null)
        );

        if (picked == null) return;

        try {
            documentService.doConvert(picked.getId(), picked.getDocumentId(), picked.getSourceDocumentId());
        } finally {
            transactionTemplate.execute(status -> {
                queueRepository.deleteById(picked.getId());
                log.info("[문서 변환 큐 삭제] documentId={}", picked.getDocumentId());
                return null;
            });
        }
    }
}
