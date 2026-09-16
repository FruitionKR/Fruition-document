package fruition.core.document.service;

import fruition.core.document.domain.AiCommandOutbox;
import fruition.core.document.repository.AiCommandOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiCommandOutboxPublisherTest {

    @Mock AiCommandOutboxRepository repository;
    @Mock KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void publishPending_acknowledgedMessage_deletesOutbox() {
        AiCommandOutbox event = new AiCommandOutbox(
                "event-1", "run-1", "ai.ingest.command", "workspace-1", "{\"run_id\":\"run-1\"}");
        when(repository.lockPendingBatch()).thenReturn(List.of(event));
        when(kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        new AiCommandOutboxPublisher(repository, kafkaTemplate).publishPending();

        verify(repository).deleteById("event-1");
    }

    @Test
    void publishPending_brokerFailure_keepsOutboxForRetry() {
        AiCommandOutbox event = new AiCommandOutbox(
                "event-1", "run-1", "ai.ingest.command", "workspace-1", "{\"run_id\":\"run-1\"}");
        when(repository.lockPendingBatch()).thenReturn(List.of(event));
        when(kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        new AiCommandOutboxPublisher(repository, kafkaTemplate).publishPending();

        verify(repository, never()).deleteById("event-1");
    }
}
