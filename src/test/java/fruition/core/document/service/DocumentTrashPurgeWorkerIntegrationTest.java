package fruition.core.document.service;

import fruition.TestcontainersConfiguration;
import fruition.core.purge.DataPurgeService;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 휴지통 보관 기간이 지난 문서·폴더만 DB와 저장소에서 지워지는지 실제 Postgres와 MinIO로 확인한다. */
@SpringBootTest(properties = "app.document-trash.purge-delay-ms=3600000")
@Import(TestcontainersConfiguration.class)
class DocumentTrashPurgeWorkerIntegrationTest {

    @Autowired DocumentTrashPurgeWorker worker;
    @Autowired DocumentService documentService;
    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate namedJdbc;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired MinioClient minio;
    @Autowired StorageProperties storage;
    @Autowired StringRedisTemplate redis;

    final String workspace = "ws_" + UUID.randomUUID();
    final String user = "user_" + UUID.randomUUID();
    /** 앱의 스케줄 실행은 실제 현재 시각을 쓴다. 미래 시각을 기준으로 삼아 테스트 행을 그 실행과 겹치지 않게 한다. */
    final Instant now = Instant.now().plus(Duration.ofDays(365));

    @Test
    void purgesOnlyExpiredTrashAndHandsOrphanedAssetsToCleanup() throws Exception {
        UUID folder = insertFolder(now.minus(Duration.ofDays(31)));
        String expired = insertDocument(folder, now.minus(Duration.ofDays(31)));
        String recent = insertDocument(null, now.minus(Duration.ofDays(29)));
        String active = insertDocument(null, null);
        UUID onlyExpired = insertAsset(expired);
        UUID shared = insertAsset(expired);
        jdbc.update("INSERT INTO document_asset_references(document_id, asset_id, created_at) VALUES (?, ?, now())",
                active, shared);

        int expiredBefore = expiredCount();
        assertThat(worker.purge(now)).isEqualTo(expiredBefore);

        assertThat(exists("documents", expired)).isFalse();
        assertThat(exists("folders", folder.toString())).isFalse();
        assertThat(exists("documents", recent)).isTrue();
        assertThat(exists("documents", active)).isTrue();
        assertThatThrownBy(() -> stat(sourceKey(expired))).isNotNull();
        stat(sourceKey(recent));
        assertThat(unreferencedSince(onlyExpired)).isNotNull();
        assertThat(unreferencedSince(shared)).isNull();

        assertThat(worker.purge(now)).isZero();
    }

    @Test
    void failedObjectDeletionKeepsRowsForNextRun() throws Exception {
        String expired = insertDocument(null, now.minus(Duration.ofDays(31)));
        StorageProperties missingBucket = new StorageProperties();
        missingBucket.setBucket("missing-" + UUID.randomUUID().toString().substring(0, 8));
        DocumentTrashPurgeWorker failing = new DocumentTrashPurgeWorker(namedJdbc, transactionTemplate,
                new DataPurgeService(jdbc, transactionTemplate, minio, missingBucket), Duration.ofDays(30));

        failing.purge(now);
        assertThat(exists("documents", expired)).isTrue();
        stat(sourceKey(expired));

        worker.purge(now);
        assertThat(exists("documents", expired)).isFalse();
        assertThatThrownBy(() -> stat(sourceKey(expired))).isNotNull();
    }

    @Test
    void concurrentRunsDeleteEachDocumentOnce() {
        for (int i = 0; i < 5; i++) {
            insertDocument(null, now.minus(Duration.ofDays(40)));
        }
        int expired = expiredCount();
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> worker.purge(now));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> worker.purge(now));

        assertThat(first.join() + second.join()).isEqualTo(expired);
        assertThat(expiredCount()).isZero();
    }

    @Test
    void trashListShowsPurgeTime() {
        Instant deletedAt = now.minus(Duration.ofDays(3));
        insertDocument(null, deletedAt);
        redis.opsForValue().set("authz:role:" + workspace + ":" + user, "OWNER");

        assertThat(documentService.trash(workspace, user).documents())
                .singleElement()
                .satisfies(item -> assertThat(item.purgeAt()).isEqualTo(item.deletedAt().plus(Duration.ofDays(30))));
    }

    /** 다른 테스트가 남긴 휴지통 문서도 기준 시각으로는 만료이므로 함께 센다. */
    private int expiredCount() {
        return jdbc.queryForObject("SELECT count(*) FROM documents WHERE deleted_at <= ?", Integer.class,
                Timestamp.from(now.minus(Duration.ofDays(30))));
    }

    private UUID insertFolder(Instant deletedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO folders(id, workspace_id, parent_folder_id, name, sort_order, current_version,
                    created_at, updated_at, deleted_at)
                VALUES (?, ?, NULL, ?, 0, 1, now(), now(), ?)
                """, id, workspace, "폴더-" + id, timestamp(deletedAt));
        return id;
    }

    private String insertDocument(UUID folder, Instant deletedAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO documents(
                    id, byte_size, content_hash, filename, display_name, normalized_filename,
                    mime_type, source_uri, status, uploaded_at, updated_at, user_id, workspace_id,
                    current_content_hash, current_version, document_role, sort_order, folder_id, deleted_at, deleted_by
                ) VALUES (?, 1, 'hash', ?, 'a', ?, 'application/pdf', ?, 'completed', now(), now(), ?, ?,
                          'hash', 1, 'ORIGINAL', 0, ?, ?, ?)
                """, id, id + ".pdf", id + ".pdf", "s3://" + storage.getBucket() + "/" + sourceKey(id), user, workspace,
                folder, timestamp(deletedAt), deletedAt == null ? null : user);
        try {
            minio.putObject(PutObjectArgs.builder().bucket(storage.getBucket()).object(sourceKey(id))
                    .stream(new ByteArrayInputStream(new byte[]{1}), 1, -1).build());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    private UUID insertAsset(String documentId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document_assets(id, workspace_id, uploaded_by, original_filename, content_type, byte_size,
                    width, height, content_hash, storage_key, created_at)
                VALUES (?, ?, ?, 'a.png', 'image/png', 1, 1, 1, 'hash', ?, now())
                """, id, workspace, user, "assets/" + workspace + "/" + id + "/content");
        jdbc.update("INSERT INTO document_asset_references(document_id, asset_id, created_at) VALUES (?, ?, now())",
                documentId, id);
        return id;
    }

    private boolean exists(String table, String id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id::text = ?", Integer.class, id) == 1;
    }

    private Timestamp unreferencedSince(UUID assetId) {
        return jdbc.queryForObject("SELECT unreferenced_since FROM document_assets WHERE id = ?", Timestamp.class, assetId);
    }

    private void stat(String key) throws Exception {
        minio.statObject(StatObjectArgs.builder().bucket(storage.getBucket()).object(key).build());
    }

    private static String sourceKey(String documentId) {
        return "sources/documents/" + documentId + "/original";
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
