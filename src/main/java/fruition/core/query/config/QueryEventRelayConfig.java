package fruition.core.query.config;

import fruition.core.query.service.QueryEventBroker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** query 이벤트 pub/sub 수신 등록. 각 인스턴스가 방송을 받아 자기 SSE 구독자에게 전달한다. */
@Configuration
public class QueryEventRelayConfig {

    @Bean
    public RedisMessageListenerContainer queryEventListenerContainer(RedisConnectionFactory connectionFactory,
                                                                     QueryEventBroker queryEventBroker) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(queryEventBroker, new ChannelTopic(QueryEventBroker.CHANNEL));
        return container;
    }
}
