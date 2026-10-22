package fruition.core.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fruition.TestcontainersConfiguration;
import fruition.core.analytics.AnalyticsRecorder;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentConvertQueue;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.document.repository.DocumentConvertBatchStore;
import fruition.core.document.repository.DocumentConvertQueueRepository;
import fruition.core.document.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"ANALYTICS_ENABLED=true", "POSTHOG_PROJECT_TOKEN=", "app.analytics.relay-delay-ms=3600000"})
@Import(TestcontainersConfiguration.class)
class DocumentConvertFailureIntegrationTest {
    @Autowired DocumentService service;
    @Autowired DocumentRepository documents;
    @Autowired DocumentConvertQueueRepository queues;
    @Autowired DocumentConvertBatchStore batches;
    @Autowired AiCommandOutboxWriter tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper mapper;

    @Autowired io.minio.MinioClient minio;
    @Autowired fruition.shared.util.StorageProperties storage;

    @Test
    void oversizedOutOfOrderBatchesKeepEveryPageInOneDocument() throws Exception {
        Conversion conversion = conversion();
        tx.executeWithoutResult(status -> {
            tasks.join(conversion.run());
            jdbc.update("INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at) VALUES (?, ?, 'placeholder', 1, now(), now())",
                    conversion.document(), Document.CONVERT_PLACEHOLDER_MARKDOWN);
            var queue = queues.findById(conversion.queue()).orElseThrow();
            queue.dispatchedUpTo(20);
            batches.insertDispatched(queue.getId(), 10, 10, "{}");
        });
        String first = "<!-- page 1 -->\n" + "앞쪽 본문\n".repeat(450000);
        String second = "<!-- page 1 -->\n뒤쪽 마지막 본문";
        putResult(conversion, 0, first);
        putResult(conversion, 10, second);
        service.applyConvertBatch(success(conversion, 10));
        service.applyConvertBatch(success(conversion, 0));
        service.applyConvertBatch(success(conversion, 0));
        String expected = first.stripTrailing() + "\n\n<!-- page 11 -->\n뒤쪽 마지막 본문";
        assertThat(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(5 * 1024 * 1024);
        assertThat(jdbc.queryForObject("SELECT markdown FROM document_edit_states WHERE document_id=?",
                String.class, conversion.document())).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT revision FROM document_edit_states WHERE document_id=?",
                Long.class, conversion.document())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT byte_size FROM documents WHERE id=?", Long.class, conversion.document()))
                .isEqualTo(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThat(documents.findConvertedParts("workspace", conversion.document())).isEmpty();
        try (var stream = minio.getObject(io.minio.GetObjectArgs.builder().bucket(storage.getBucket())
                .object("sources/documents/" + conversion.document() + "/original").build())) {
            assertThat(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(expected);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_convert_queue WHERE id=?", Long.class, conversion.queue())).isZero();
    }

    private void putResult(Conversion conversion, int start, String markdown) throws Exception {
        byte[] bytes = markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        minio.putObject(io.minio.PutObjectArgs.builder().bucket(storage.getBucket())
                .object("tmp/convert/workspace/" + conversion.document() + "/" + start + ".md")
                .stream(new java.io.ByteArrayInputStream(bytes), bytes.length, -1).build());
    }

    private ObjectNode success(Conversion conversion, int start) {
        var event = mapper.createObjectNode().put("run_id", conversion.run() + ":" + start)
                .put("kind", "convert_batch").put("status", "succeeded");
        event.putObject("payload").put("page_start", start + 1).put("page_end", start + 10)
                .put("total_pages", 20).put("result_key", "tmp/convert/workspace/" + conversion.document() + "/" + start + ".md");
        return event;
    }

    record Conversion(String source, String document, String user, String run, long queue) {}

    private Conversion conversion() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String source = "doc_src_" + suffix, document = "doc_cv_" + suffix, user = "user_" + suffix;
        String run = "convert:" + document;
        return tx.execute(status -> {
            documents.saveAndFlush(new Document(source, "workspace", user, source + ".pdf",
                    "application/pdf", 10, "test/source.pdf", null, "upload"));
            tasks.begin(run, "workspace", user, "convert");
            Document placeholder = new Document(document, "workspace", user, document + ".md",
                    "text/markdown", 10, "test/placeholder.md", null, "convert");
            placeholder.initializeConvertPlaceholder(source, null, "test-hash", 10, 0);
            placeholder.markPipelineStarted(run, Instant.now());
            documents.saveAndFlush(placeholder);
            var queue = queues.saveAndFlush(new DocumentConvertQueue(document, source));
            batches.insertDispatched(queue.getId(), 0, 10, "{}");
            return new Conversion(source, document, user, run, queue.getId());
        });
    }

    private ObjectNode failure(Conversion conversion) {
        return mapper.createObjectNode().put("run_id", conversion.run() + ":0")
                .put("kind", "convert_batch").put("status", "failed").put("error", "input_rejected");
    }

    @Test
    void failedBatchCommitsDocumentTaskCleanupCreditsAndOneEventBeforeRejectingLateWrites() throws Exception {
        Conversion conversion = conversion();
        assertThat(jdbc.queryForObject("SELECT reserved FROM credit_accounts WHERE user_id=?",
                Long.class, conversion.user())).isPositive();

        service.applyConvertBatch(failure(conversion));
        String firstPayload = jdbc.queryForObject("SELECT payload::text FROM analytics_outbox WHERE event_id=?",
                String.class, AnalyticsRecorder.eventId("pdf_convert_completed", conversion.run()));
        service.applyConvertBatch(failure(conversion));

        assertThat(jdbc.queryForObject("SELECT status FROM documents WHERE id=?", String.class, conversion.document()))
                .isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task_runs WHERE id=?", String.class, conversion.run()))
                .isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_convert_queue WHERE id=?", Long.class, conversion.queue())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_convert_batches WHERE queue_id=?", Long.class, conversion.queue())).isZero();
        assertThat(jdbc.queryForObject("SELECT reserved FROM credit_accounts WHERE user_id=?", Long.class, conversion.user())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries WHERE run_id=? AND type='release'",
                Long.class, conversion.run())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_collect_queue WHERE run_id=?", Long.class, conversion.run())).isEqualTo(1);
        var payloads = jdbc.queryForList("SELECT payload::text FROM analytics_outbox WHERE event_id=?",
                String.class, AnalyticsRecorder.eventId("pdf_convert_completed", conversion.run()));
        assertThat(payloads).containsExactly(firstPayload);
        var properties = mapper.readTree(firstPayload).path("properties");
        assertThat(properties.path("status").asText()).isEqualTo("failed");
        assertThat(properties.path("failure_reason").asText()).isEqualTo("validation");
        assertThat(properties.path("document_id").asText()).isEqualTo(conversion.source());
        assertThat(properties.path("conversion_id").asText()).isEqualTo(conversion.run());
        // The DB guard must still reject writes after terminal failure; no trigger bypass.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.ai_task_run_id', ?, true)", String.class, conversion.run());
            jdbc.update("UPDATE documents SET status='completed' WHERE id=?", conversion.document());
        })).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void outerRollbackRestoresProductQueueAndReservationAndRemovesAnalytics() {
        Conversion conversion = conversion();
        tx.executeWithoutResult(status -> {
            service.applyConvertBatch(failure(conversion));
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT status FROM documents WHERE id=?", String.class, conversion.document()))
                .isEqualTo("processing");
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task_runs WHERE id=?", String.class, conversion.run()))
                .isEqualTo("running");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_convert_queue WHERE id=?", Long.class, conversion.queue())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_convert_batches WHERE queue_id=?", Long.class, conversion.queue())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reserved FROM credit_accounts WHERE user_id=?", Long.class, conversion.user())).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries WHERE run_id=? AND type='release'",
                Long.class, conversion.run())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_collect_queue WHERE run_id=?", Long.class, conversion.run())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analytics_outbox WHERE event_id=?", Long.class,
                AnalyticsRecorder.eventId("pdf_convert_completed", conversion.run()))).isZero();
    }
}
