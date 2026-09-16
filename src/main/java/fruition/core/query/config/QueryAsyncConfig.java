package fruition.core.query.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.util.concurrent.Executor;

@Configuration
public class QueryAsyncConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean("queryRunExecutor")
    public Executor queryRunExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("query-run-");
        executor.initialize();
        return executor;
    }
}
