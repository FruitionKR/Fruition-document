package fruition.core.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class PostgresDocumentEditOutboxPublisherTest {

    @Mock JdbcTemplate jdbcTemplate;
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void publishPending_sendsSchemaPayloadWithDocumentKeyAndMarksPublished() throws Exception {
        PostgresDocumentEditOutboxPublisher.Event event = event("event-1", "doc-1", 2);
        PostgresDocumentEditOutboxPublisher.Event next = event("event-2", "doc-2", 3);
        when(jdbcTemplate.query(anyString(), anyRowMapper(), eq(100))).thenReturn(List.of(event, next));
        when(kafkaTemplate.send(eq("document.edit.event"), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        new PostgresDocumentEditOutboxPublisher(jdbcTemplate, kafkaTemplate, objectMapper,
                "document.edit.event").publishPending();

        InOrder order = inOrder(kafkaTemplate, jdbcTemplate);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        order.verify(kafkaTemplate).send(eq("document.edit.event"), eq("doc-1"), payloadCaptor.capture());
        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "event_id", "event_type", "schema_version", "document_id", "workspace_id",
                "revision", "content_hash", "created_at");
        assertThat(payload.get("event_id").asText()).isEqualTo("event-1");
        assertThat(payload.get("event_type").asText()).isEqualTo("document.edit.saved.v1");
        assertThat(payload.get("schema_version").asInt()).isEqualTo(1);
        assertThat(payload.get("document_id").asText()).isEqualTo("doc-1");
        assertThat(payload.get("workspace_id").asText()).isEqualTo("ws-1");
        assertThat(payload.get("revision").asLong()).isEqualTo(2);
        assertThat(payload.get("content_hash").asText()).isEqualTo("hash-2");
        assertThat(payload.get("created_at").asText()).isEqualTo("2026-08-14T00:00:00Z");
        order.verify(jdbcTemplate).update(anyString(), any(), eq("event-1"));
        order.verify(kafkaTemplate).send(eq("document.edit.event"), eq("doc-2"), anyString());
        order.verify(jdbcTemplate).update(anyString(), any(), eq("event-2"));
    }

    @Test
    void publishPending_stopsAtFirstFailureAndLeavesRowsPending() throws Exception {
        PostgresDocumentEditOutboxPublisher.Event first = event("event-1", "doc-1", 2);
        PostgresDocumentEditOutboxPublisher.Event second = event("event-2", "doc-2", 3);
        when(jdbcTemplate.query(anyString(), anyRowMapper(), eq(100))).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(eq("document.edit.event"), eq("doc-1"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        new PostgresDocumentEditOutboxPublisher(jdbcTemplate, kafkaTemplate, objectMapper,
                "document.edit.event").publishPending();

        verify(kafkaTemplate, never()).send(eq("document.edit.event"), eq("doc-2"), anyString());
        verify(jdbcTemplate, never()).update(anyString(), any(), anyString());
    }

    @Test
    void publishPending_markFailureIsRetriedOnNextCycle() throws Exception {
        PostgresDocumentEditOutboxPublisher.Event event = event("event-1", "doc-1", 2);
        when(jdbcTemplate.query(anyString(), anyRowMapper(), eq(100))).thenReturn(List.of(event));
        when(kafkaTemplate.send(eq("document.edit.event"), eq("doc-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(jdbcTemplate.update(anyString(), any(), eq("event-1")))
                .thenThrow(new RuntimeException("mark failed"))
                .thenReturn(1);

        PostgresDocumentEditOutboxPublisher publisher = new PostgresDocumentEditOutboxPublisher(
                jdbcTemplate, kafkaTemplate, objectMapper, "document.edit.event");
        publisher.publishPending();
        publisher.publishPending();

        verify(kafkaTemplate, org.mockito.Mockito.times(2))
                .send(eq("document.edit.event"), eq("doc-1"), anyString());
    }

    @SuppressWarnings("unchecked")
    private RowMapper<PostgresDocumentEditOutboxPublisher.Event> anyRowMapper() {
        return (RowMapper<PostgresDocumentEditOutboxPublisher.Event>) any(RowMapper.class);
    }

    private PostgresDocumentEditOutboxPublisher.Event event(String id, String documentId, long revision) {
        return new PostgresDocumentEditOutboxPublisher.Event(
                id, "document.edit.saved.v1", 1, documentId, "ws-1", revision,
                "hash-" + revision, Instant.parse("2026-08-14T00:00:00Z"));
    }
}
