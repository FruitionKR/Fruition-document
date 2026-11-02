package fruition.core.document.service;

import fruition.TestcontainersConfiguration;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.upload.UploadLimitException;
import fruition.core.upload.UploadLimitService;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 업로드 한도의 사용자 잠금이 object storage 쓰기 동안 잡혀 있지 않은지, 저장 뒤 한도 재확인이 실패하면
 * 방금 저장한 객체를 지우는지, 이미 있던 asset 행은 개수 한도에 세지 않는지 실제 Postgres·MinIO로 확인한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DocumentUploadLimitLockIntegrationTest {
    static final long QUOTA = 5L * 1024 * 1024 * 1024;
    static final int DAILY_LIMIT = 200;

    @Autowired DocumentService documents;
    @Autowired UploadLimitService limits;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired MinioClient minio;
    @Autowired StorageProperties storage;
    @Autowired TransactionTemplate transaction;

    private String userId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_lock_" + suffix;
        workspaceId = "ws_lock_" + suffix;
        redis.opsForValue().set("authz:role:v2:" + workspaceId + ":" + userId, "{\"role\":\"OWNER\",\"permissions\":[]}");
    }

    @Test
    void userLockIsNotHeldWhileOriginalIsWrittenToStorage() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SlowOriginal slow = new SlowOriginal("slow.pdf", 10, key -> {
            writing.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            putObject(key, 10);
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> stalled = pool.submit(() -> documents.upload(workspaceId, userId, "idem-" + UUID.randomUUID(),
                    null, slow));
            assertThat(writing.await(30, TimeUnit.SECONDS)).isTrue();

            assertThat(userLockIsFree()).isTrue();
            // 같은 사용자의 다른 업로드는 느린 저장을 기다리지 않고 끝난다.
            Future<?> other = pool.submit(() -> documents.upload(workspaceId, userId, "idem-" + UUID.randomUUID(),
                    null, new MockMultipartFile("file", "fast.md", "text/markdown", "0123456789".getBytes())));
            other.get(10, TimeUnit.SECONDS);

            release.countDown();
            stalled.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(documentCount()).isEqualTo(2);
        assertThat(limits.uploadsToday(userId)).isEqualTo(2);
        assertThat(limits.usedBytes(userId)).isEqualTo(20);
    }

    @Test
    void quotaFilledDuringStorageWrite_rejectsAfterStore_andDeletesStoredObject() {
        seedUsage(QUOTA - 10);
        AtomicReference<String> storedKey = new AtomicReference<>();
        SlowOriginal original = new SlowOriginal("late.pdf", 10, key -> {
            putObject(key, 10);
            storedKey.set(key);
            seedUsage(1);  // 저장하는 사이 다른 업로드가 먼저 커밋해 용량이 찼다
        });

        assertThatThrownBy(() -> documents.upload(workspaceId, userId, "idem-" + UUID.randomUUID(), null, original))
                .isInstanceOfSatisfying(UploadLimitException.class,
                        e -> assertThat(e.getCode()).isEqualTo("STORAGE_QUOTA_EXCEEDED"));

        assertThat(storedKey.get()).isNotNull();
        assertThat(objectExists(storedKey.get())).isFalse();
        assertThat(documentCount()).isZero();
        assertThat(limits.uploadsToday(userId)).isZero();
    }

    @Test
    void overQuotaUpload_isRejectedBeforeWritingToStorage() {
        seedUsage(QUOTA - 5);
        AtomicReference<String> storedKey = new AtomicReference<>();
        SlowOriginal original = new SlowOriginal("big.pdf", 10, storedKey::set);

        assertThatThrownBy(() -> documents.upload(workspaceId, userId, "idem-" + UUID.randomUUID(), null, original))
                .isInstanceOfSatisfying(UploadLimitException.class,
                        e -> assertThat(e.getCode()).isEqualTo("STORAGE_QUOTA_EXCEEDED"));
        assertThat(storedKey.get()).isNull();
    }

    @Test
    void contentSave_countsOnlyNewlyInsertedAssetsAgainstDailyLimit() {
        String documentId = documents.createMarkdown(workspaceId, userId, "create-" + UUID.randomUUID(),
                new MarkdownDocumentCreateRequest("이미지 문서", "# 본문", null)).id();
        long base = jdbc.queryForObject("SELECT revision FROM document_edit_states WHERE document_id = ?",
                Long.class, documentId);
        UUID assetId = UUID.randomUUID();
        String key = "assets/" + workspaceId + "/" + assetId;
        // 같은 요청의 재전송이 먼저 asset 행을 커밋했다. 이번 저장에서는 새 업로드가 아니다.
        jdbc.update("""
                INSERT INTO document_assets (id, workspace_id, uploaded_by, original_filename, content_type, byte_size,
                                             width, height, content_hash, storage_key, created_at)
                VALUES (?, ?, ?, 'image.png', 'image/png', 4, 1, 1, ?, ?, now())
                """, assetId, workspaceId, userId, "0".repeat(64), key);
        seedEvents(DAILY_LIMIT);
        var stored = new DocumentAssetStorageCoordinator.StoredAsset(assetId, key,
                new DocumentAssetValidator.ValidatedAsset("image.png", "image/png", new byte[4], 1, 1, "0".repeat(64)),
                false);

        var saved = documents.saveContentWithAssets(workspaceId, userId, documentId,
                "# 본문\n\n![그림](/api/workspaces/" + workspaceId + "/assets/" + assetId + "/content)",
                base, "write-" + UUID.randomUUID(), Map.of(assetId, stored), null);

        assertThat(saved.changed()).isTrue();
        assertThat(limits.uploadsToday(userId)).isEqualTo(DAILY_LIMIT);
    }

    // ---------- helpers

    interface Store { void write(String key) throws Exception; }

    /** 직접 업로드처럼 이미 올라간 원본을 복사하는 경로. 복사(저장) 시점을 테스트가 붙잡는다. */
    private record SlowOriginal(String filename, long size, Store store) implements StoredOriginal {
        public String getName() { return "file"; }
        public String getOriginalFilename() { return filename; }
        public String getContentType() { return "application/pdf"; }
        public boolean isEmpty() { return false; }
        public long getSize() { return size; }
        public byte[] getBytes() { return new byte[(int) size]; }
        public InputStream getInputStream() { return new ByteArrayInputStream(getBytes()); }
        public void transferTo(File dest) { throw new UnsupportedOperationException(); }
        public String storageFingerprint() { return UUID.randomUUID().toString().replace("-", "").repeat(2); }
        public void copyTo(String targetKey) throws Exception { store.write(targetKey); }
    }

    private boolean userLockIsFree() {
        return Boolean.TRUE.equals(transaction.execute(status -> jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class, "upload-limit:" + userId)));
    }

    private void putObject(String key, int size) throws Exception {
        minio.putObject(PutObjectArgs.builder().bucket(storage.getBucket()).object(key)
                .stream(new ByteArrayInputStream(new byte[size]), size, -1).contentType("application/pdf").build());
    }

    private boolean objectExists(String key) {
        try {
            minio.statObject(StatObjectArgs.builder().bucket(storage.getBucket()).object(key).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void seedUsage(long bytes) {
        jdbc.update("""
                INSERT INTO document_assets (id, workspace_id, uploaded_by, original_filename, content_type, byte_size,
                                             width, height, content_hash, storage_key, created_at)
                VALUES (?, ?, ?, 'seed.png', 'image/png', ?, 1, 1, ?, ?, now())
                """, UUID.randomUUID(), workspaceId, userId, bytes, "0".repeat(64), "seed/" + UUID.randomUUID());
    }

    private void seedEvents(int count) {
        jdbc.update("""
                INSERT INTO user_upload_events (user_id, workspace_id, kind, byte_size, created_at)
                SELECT ?, ?, 'document', 1, now() FROM generate_series(1, ?)
                """, userId, workspaceId, count);
    }

    private int documentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM documents WHERE user_id = ?", Integer.class, userId);
    }
}
