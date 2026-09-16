package fruition.core.document.service;

import fruition.core.document.domain.AiCommandOutbox;
import fruition.core.document.repository.AiCommandOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** DB에 커밋된 AI command를 Kafka에 at-least-once로 발행한다. */
@Component
public class AiCommandOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(AiCommandOutboxPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final AiCommandOutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public AiCommandOutboxPublisher(AiCommandOutboxRepository repository,
                                    KafkaTemplate<String, String> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${app.ai-command.outbox-poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        List<AiCommandOutbox> events;
        try {
            // Kafka ACK 후 삭제가 커밋될 때까지 락을 유지해 다른 Pod의 경쟁 발행을 막는다.
            events = repository.lockPendingBatch();
        } catch (Exception e) {
            log.warn("[AI command outbox 조회 실패] error={}", e.getMessage());
            return;
        }
        for (AiCommandOutbox event : events) {
            MDC.put("flowId", event.getRunId());
            try {
                log.debug("[AI command 발행 시작] topic={} messageKey={} runId={}",
                        event.getTopic(), event.getMessageKey(), event.getRunId());
                var result = kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                repository.deleteById(event.getId());
                int partition = result != null ? result.getRecordMetadata().partition() : -1;
                long offset = result != null ? result.getRecordMetadata().offset() : -1;
                log.info("[AI command 발행 완료] topic={} messageKey={} runId={} partition={} offset={}",
                        event.getTopic(), event.getMessageKey(), event.getRunId(), partition, offset);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[AI command 발행 실패] topic={} messageKey={} runId={} retryable=true errorType={} error={}",
                        event.getTopic(), event.getMessageKey(), event.getRunId(),
                        e.getClass().getSimpleName(), e.getMessage());
                return;
            } finally {
                MDC.remove("flowId");
            }
        }
    }
}
