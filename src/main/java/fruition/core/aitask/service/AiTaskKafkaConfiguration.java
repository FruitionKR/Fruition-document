package fruition.core.aitask.service;

import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** AI 결과는 core 반영이 끝날 때까지 offset을 확정하지 않는다. */
@Configuration
public class AiTaskKafkaConfiguration {

    private static final long RETRY_INTERVAL_MILLIS = 1_000;

    @Bean("aiTaskResultKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<Object, Object> aiTaskResultKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(resultErrorHandler(RETRY_INTERVAL_MILLIS));
        return factory;
    }

    static DefaultErrorHandler resultErrorHandler(long intervalMillis) {
        return new DefaultErrorHandler(
                new FixedBackOff(intervalMillis, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
