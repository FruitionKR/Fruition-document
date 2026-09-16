package fruition.core.document.service;

import fruition.core.document.domain.AiCommandOutbox;
import fruition.core.document.repository.AiCommandOutboxRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 실제 PostgreSQL과 Spring 트랜잭션 프록시로 Pod 간 경쟁·ACK 이후 롤백을 검증한다. */
@SpringJUnitConfig(AiCommandOutboxConcurrencyTest.Config.class)
class AiCommandOutboxConcurrencyTest {
    @Autowired AiCommandOutboxRepository repository;
    @Autowired AiCommandOutboxPublisher publisher;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JpaTransactionManager transactionManager;

    @BeforeEach
    void prepare() {
        reset(kafka);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.deleteAllInBatch();
            repository.save(new AiCommandOutbox("event-1", "run-1", "ai.ingest.command", "doc-1", "{}"));
        });
    }

    @Test
    void concurrentPublisherSkipsRowUntilAckAndCommit() throws Exception {
        var sending = new CountDownLatch(1);
        var ack = new CompletableFuture<SendResult<String, String>>();
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            sending.countDown();
            return ack;
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(publisher::publishPending);
            try {
                assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
                executor.submit(publisher::publishPending).get(5, TimeUnit.SECONDS);
                verify(kafka, times(1)).send(anyString(), anyString(), anyString());
                assertThat(repository.count()).isEqualTo(1);
            } finally {
                ack.complete(null);
            }
            first.get(5, TimeUnit.SECONDS);
        }
        assertThat(repository.count()).isZero();
    }

    @Test
    void brokerFailureReleasesLockAndRetainsEventForRetry() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                .thenReturn(CompletableFuture.completedFuture(null));
        publisher.publishPending();
        assertThat(repository.count()).isEqualTo(1);
        publisher.publishPending();
        assertThat(repository.count()).isZero();
        verify(kafka, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    void rollbackAfterAckRedeliversSameEvent() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publishPending();
            status.setRollbackOnly();
        });
        assertThat(repository.count()).isEqualTo(1);
        publisher.publishPending();
        assertThat(repository.count()).isZero();
        verify(kafka, times(2)).send("ai.ingest.command", "doc-1", "{}");
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean(initMethod = "start", destroyMethod = "stop")
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(PostgreSQLContainer<?> postgres) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setManagedTypes(PersistenceManagedTypes.of(AiCommandOutbox.class.getName()));
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }

        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        AiCommandOutboxRepository repository(EntityManagerFactory factory) {
            return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory))
                    .getRepository(AiCommandOutboxRepository.class);
        }

        @Bean
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka() {
            return mock(KafkaTemplate.class);
        }

        @Bean
        AiCommandOutboxPublisher publisher(AiCommandOutboxRepository repository, KafkaTemplate<String, String> kafka) {
            return new AiCommandOutboxPublisher(repository, kafka);
        }
    }
}
