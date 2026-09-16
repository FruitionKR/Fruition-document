package fruition.core.document.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class PostgresDocumentEditOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(PostgresDocumentEditOutboxPublisher.class);
    private static final int BATCH_SIZE = 100;
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String eventTopic;

    public PostgresDocumentEditOutboxPublisher(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${app.document-edit.event-topic}") String eventTopic
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.eventTopic = eventTopic;
    }

    @Scheduled(fixedDelayString = "${app.document-edit.outbox-poll-interval-ms:1000}")
    public void publishPending() {
        List<Event> events;
        try {
            events = jdbcTemplate.query("""
                    SELECT event_id, document_id, workspace_id, revision, content_hash,
                           event_type, schema_version, created_at
                    FROM document_edit_outbox
                    WHERE published = false
                    ORDER BY created_at, event_id
                    LIMIT ?
                    """, (rs, rowNum) -> new Event(
                    rs.getString("event_id"), rs.getString("event_type"),
                    rs.getInt("schema_version"), rs.getString("document_id"),
                    rs.getString("workspace_id"), rs.getLong("revision"),
                    rs.getString("content_hash"), rs.getTimestamp("created_at").toInstant()), BATCH_SIZE);
        } catch (Exception exception) {
            log.error("[문서 편집 outbox pending 조회 실패] eventId=unknown documentId=unknown", exception);
            return;
        }
        for (Event event : events) {
            MDC.put("flowId", event.eventId());
            try {
                log.debug("[문서 편집 event 발행 시작] topic={} eventId={} documentId={} revision={}",
                        eventTopic, event.eventId(), event.documentId(), event.revision());
                String payload = objectMapper.writeValueAsString(event);
                var result = kafkaTemplate.send(eventTopic, event.documentId(), payload)
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                jdbcTemplate.update("""
                        UPDATE document_edit_outbox
                        SET published = true, published_at = ?
                        WHERE event_id = ? AND published = false
                        """, Timestamp.from(Instant.now()), event.eventId());
                int partition = result != null ? result.getRecordMetadata().partition() : -1;
                long offset = result != null ? result.getRecordMetadata().offset() : -1;
                log.info("[문서 편집 event 발행 완료] topic={} eventId={} documentId={} revision={} partition={} offset={}",
                        eventTopic, event.eventId(), event.documentId(), event.revision(), partition, offset);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                log.error("[문서 편집 event 발행 중단] eventId={} documentId={}",
                        event.eventId(), event.documentId(), exception);
                return;
            } catch (Exception exception) {
                log.error("[문서 편집 event 발행 또는 published 표시 실패] eventId={} documentId={}",
                        event.eventId(), event.documentId(), exception);
                return;
            } finally {
                MDC.remove("flowId");
            }
        }
    }

    record Event(
            @JsonProperty("event_id") String eventId,
            @JsonProperty("event_type") String eventType,
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("workspace_id") String workspaceId,
            long revision,
            @JsonProperty("content_hash") String contentHash,
            @JsonProperty("created_at") Instant createdAt
    ) {}
}
