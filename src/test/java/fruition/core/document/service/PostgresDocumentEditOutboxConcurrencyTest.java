package fruition.core.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 실제 PostgreSQL과 Spring 트랜잭션 프록시로 여러 Pod의 publisher 경쟁을 검증한다. */
@SpringJUnitConfig(PostgresDocumentEditOutboxConcurrencyTest.Config.class)
class PostgresDocumentEditOutboxConcurrencyTest {
    private static final String TOPIC = "document.edit.event";

    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("podA") PostgresDocumentEditOutboxPublisher podA;
    @Autowired @Qualifier("podB") PostgresDocumentEditOutboxPublisher podB;
    @Autowired KafkaTemplate<String, String> kafka;

    @BeforeEach
    void prepare() {
        reset(kafka);
        jdbc.update("DELETE FROM document_edit_outbox");
        insert("event-1", "doc-1", 0);
        insert("event-2", "doc-2", 1);
        insert("event-3", "doc-3", 2);
    }

    @Test
    void concurrentPublishersSendEachEventExactlyOnce() throws Exception {
        var sending = new CountDownLatch(1);
        var ack = new CompletableFuture<SendResult<String, String>>();
        when(kafka.send(eq(TOPIC), anyString(), anyString())).thenAnswer(invocation -> {
            sending.countDown();
            return ack;
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(podA::publishPending);
            try {
                assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
                executor.submit(podB::publishPending).get(5, TimeUnit.SECONDS);
                verify(kafka, times(1)).send(eq(TOPIC), anyString(), anyString());
                assertThat(publishedIds()).isEmpty();
            } finally {
                ack.complete(null);
            }
            first.get(5, TimeUnit.SECONDS);
        }
        verify(kafka).send(eq(TOPIC), eq("doc-1"), anyString());
        verify(kafka).send(eq(TOPIC), eq("doc-2"), anyString());
        verify(kafka).send(eq(TOPIC), eq("doc-3"), anyString());
        verifyNoMoreInteractions(kafka);
        assertThat(publishedIds()).containsExactly("event-1", "event-2", "event-3");
    }

    @Test
    void brokerFailureKeepsSentRowsPublishedAndRetriesTheRest() {
        when(kafka.send(eq(TOPIC), eq("doc-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(kafka.send(eq(TOPIC), eq("doc-2"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(kafka.send(eq(TOPIC), eq("doc-3"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        podA.publishPending();
        assertThat(publishedIds()).containsExactly("event-1");
        verify(kafka, never()).send(eq(TOPIC), eq("doc-3"), anyString());

        podB.publishPending();
        assertThat(publishedIds()).containsExactly("event-1", "event-2", "event-3");
        verify(kafka, times(1)).send(eq(TOPIC), eq("doc-1"), anyString());
        verify(kafka, times(2)).send(eq(TOPIC), eq("doc-2"), anyString());
        verify(kafka, times(1)).send(eq(TOPIC), eq("doc-3"), anyString());
    }

    private void insert(String eventId, String documentId, int order) {
        jdbc.update("""
                INSERT INTO document_edit_outbox(event_id, document_id, workspace_id, revision, content_hash,
                                                 event_type, schema_version, created_at)
                VALUES (?, ?, 'ws-1', 2, 'hash-2', 'document.edit.saved.v1', 1, ?)
                """, eventId, documentId,
                Timestamp.from(Instant.parse("2026-10-06T00:00:00Z").plusSeconds(order)));
    }

    private List<String> publishedIds() {
        return jdbc.queryForList(
                "SELECT event_id FROM document_edit_outbox WHERE published ORDER BY event_id", String.class);
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean(initMethod = "start", destroyMethod = "stop")
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }

        @Bean
        DataSource dataSource(PostgreSQLContainer<?> postgres) {
            return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            var jdbc = new JdbcTemplate(dataSource);
            // V39 document_edit_outbox DDL에서 이 테스트에 필요한 부분만 옮긴다.
            jdbc.execute("""
                    CREATE TABLE document_edit_outbox (
                        event_id varchar(255) PRIMARY KEY,
                        document_id varchar(255) NOT NULL,
                        workspace_id varchar(255) NOT NULL,
                        revision bigint NOT NULL,
                        content_hash varchar(64) NOT NULL,
                        event_type varchar(255) NOT NULL,
                        schema_version integer NOT NULL,
                        created_at timestamp with time zone NOT NULL,
                        published boolean NOT NULL DEFAULT false,
                        published_at timestamp with time zone
                    )
                    """);
            jdbc.execute("CREATE INDEX idx_document_edit_outbox_pending "
                    + "ON document_edit_outbox(created_at, event_id) WHERE published = false");
            return jdbc;
        }

        /** 운영과 같이 JPA 트랜잭션 매니저가 JdbcTemplate 커넥션을 묶는지 함께 확인한다. */
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setManagedTypes(PersistenceManagedTypes.of());
            return factory;
        }

        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka() {
            return mock(KafkaTemplate.class);
        }

        @Bean
        PostgresDocumentEditOutboxPublisher podA(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka) {
            return publisher(jdbc, kafka);
        }

        @Bean
        PostgresDocumentEditOutboxPublisher podB(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka) {
            return publisher(jdbc, kafka);
        }

        private static PostgresDocumentEditOutboxPublisher publisher(
                JdbcTemplate jdbc, KafkaTemplate<String, String> kafka) {
            return new PostgresDocumentEditOutboxPublisher(jdbc, kafka, new ObjectMapper().findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS), TOPIC);
        }
    }
}
