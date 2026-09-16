package fruition.core.aitask.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.MessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiTaskKafkaConfigurationTest {

    @Test
    void resultErrorHandlerDoesNotRecoverOrAckAfterDefaultNineRetries() {
        var handler = AiTaskKafkaConfiguration.resultErrorHandler(0);
        var record = new ConsumerRecord<>("ai.task.event", 0, 0, "run-1", "payload");
        var container = mock(MessageListenerContainer.class);
        var consumer = mock(Consumer.class);

        for (int attempt = 0; attempt < 12; attempt++) {
            assertThat(handler.handleOne(new RuntimeException("temporary"), record, consumer, container))
                    .isFalse();
        }
    }
}
