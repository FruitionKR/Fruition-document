package fruition.core.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fruition.TestcontainersConfiguration;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentConvertQueue;
import fruition.core.document.dto.DocumentLifecycleRequest;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.document.repository.DocumentConvertBatchStore;
import fruition.core.document.repository.DocumentConvertQueueRepository;
import fruition.core.document.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 변환 대기열 행 잠금 순서와 대기 한도. 결과 반영은 대기열 행 → 문서 행 순서로 잠근다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DocumentConvertLockOrderIntegrationTest {
    @Autowired DocumentService service;
    @Autowired DocumentRepository documents;
    @Autowired DocumentConvertQueueRepository queues;
    @Autowired DocumentConvertBatchStore batches;
    @Autowired AiCommandOutboxWriter tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper mapper;
    @Autowired StringRedisTemplate redis;
    @Autowired io.minio.MinioClient minio;
    @Autowired fruition.shared.util.StorageProperties storage;

    @Test
    void deleteWaitsForConvertMergeInsteadOfDeadlocking() throws Exception {
        Conversion conversion = conversion();
        redis.opsForValue().set("authz:role:v2:" + conversion.workspace() + ":" + conversion.user(),
                "{\"role\":\"OWNER\",\"permissions\":[]}");
        long version = jdbc.queryForObject("SELECT current_version FROM documents WHERE id=?", Long.class, conversion.document());
        var queueLocked = new CountDownLatch(1);
        var updateDocument = new CountDownLatch(1);
        var mergePid = new CompletableFuture<Integer>();
        // 결과 반영과 같은 순서: 대기열 행을 잠근 채 문서 행을 바꾼다.
        var merge = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM document_convert_queue WHERE document_id=? FOR UPDATE", Long.class, conversion.document());
            mergePid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            queueLocked.countDown();
            await(updateDocument);
            jdbc.update("UPDATE documents SET updated_at = now() WHERE id=?", conversion.document());
        }));
        await(queueLocked);
        var delete = CompletableFuture.runAsync(() -> service.delete(conversion.workspace(), conversion.user(),
                conversion.document(), "delete-" + conversion.document(), new DocumentLifecycleRequest(version)));
        waitUntilBlockedBy(mergePid.get());
        updateDocument.countDown();

        merge.get(30, TimeUnit.SECONDS);
        delete.get(30, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM documents WHERE id=?", Boolean.class,
                conversion.document())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_convert_queue WHERE id=?", Long.class,
                conversion.queue())).isZero();
    }

    @Test
    void convertEventGivesUpWaitingForQueueLockAndSucceedsWhenRedelivered() throws Exception {
        Conversion conversion = conversion();
        putResult(conversion);
        var queueLocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM document_convert_queue WHERE document_id=? FOR UPDATE", Long.class, conversion.document());
            queueLocked.countDown();
            await(release);
        }));
        await(queueLocked);

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> service.applyConvertBatch(success(conversion)))
                .isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt)).isLessThan(15);
        // 기다리다 포기한 이벤트는 아무것도 바꾸지 않아 다시 소비하면 그대로 반영된다.
        assertThat(jdbc.queryForObject("SELECT status FROM document_convert_batches WHERE queue_id=? AND start_page=0",
                String.class, conversion.queue())).isEqualTo(DocumentConvertBatchStore.DISPATCHED);
        release.countDown();
        holder.get(30, TimeUnit.SECONDS);

        service.applyConvertBatch(success(conversion));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_convert_queue WHERE id=?", Long.class,
                conversion.queue())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task_runs WHERE id=?", String.class, conversion.run()))
                .isEqualTo("completed");
    }

    private void waitUntilBlockedBy(int pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                Long.class, pid) == 0) {
            assertThat(System.nanoTime()).as("삭제가 대기열 잠금을 기다리지 않았다").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void putResult(Conversion conversion) throws Exception {
        byte[] bytes = "<!-- page 1 -->\n본문".getBytes(StandardCharsets.UTF_8);
        minio.putObject(io.minio.PutObjectArgs.builder().bucket(storage.getBucket()).object(resultKey(conversion))
                .stream(new java.io.ByteArrayInputStream(bytes), bytes.length, -1).build());
    }

    private static String resultKey(Conversion conversion) {
        return "tmp/convert/" + conversion.workspace() + "/" + conversion.document() + "/0.md";
    }

    private ObjectNode success(Conversion conversion) {
        var event = mapper.createObjectNode().put("run_id", conversion.run() + ":0")
                .put("kind", "convert_batch").put("status", "succeeded");
        event.putObject("payload").put("page_start", 1).put("page_end", 10)
                .put("total_pages", 10).put("result_key", resultKey(conversion));
        return event;
    }

    record Conversion(String workspace, String document, String user, String run, long queue) {}

    private Conversion conversion() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String workspace = "ws_" + suffix, source = "doc_src_" + suffix, document = "doc_cv_" + suffix, user = "user_" + suffix;
        String run = "convert:" + document;
        return tx.execute(status -> {
            documents.saveAndFlush(new Document(source, workspace, user, source + ".pdf",
                    "application/pdf", 10, "test/source.pdf", null, "upload"));
            tasks.begin(run, workspace, user, "convert");
            Document placeholder = new Document(document, workspace, user, document + ".md",
                    "text/markdown", 10, "test/placeholder.md", null, "convert");
            placeholder.initializeConvertPlaceholder(source, null, "test-hash", 10, 0);
            placeholder.markPipelineStarted(run, Instant.now());
            documents.saveAndFlush(placeholder);
            jdbc.update("INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at) "
                    + "VALUES (?, ?, 'placeholder', 1, now(), now())", document, Document.CONVERT_PLACEHOLDER_MARKDOWN);
            var queue = queues.saveAndFlush(new DocumentConvertQueue(document, source));
            batches.insertDispatched(queue.getId(), 0, 10, "{}");
            return new Conversion(workspace, document, user, run, queue.getId());
        });
    }
}
