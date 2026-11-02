package fruition.core.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.service.DocumentAssetContentService;
import fruition.core.document.service.DocumentService;
import fruition.shared.security.JwtTokenProvider;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 사용자별 업로드 한도(용량 5GB·하루 200개·PDF 100MB·요청당 개수)를 실제 Postgres·MinIO와 API로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UploadLimitIntegrationTest {
    static final long QUOTA = 5L * 1024 * 1024 * 1024;
    static final String QUOTA_BODY = """
            {"error":{"code":"STORAGE_QUOTA_EXCEEDED","message":"내 저장 공간(5GB)이 부족합니다. 문서나 녹음을 지운 뒤 다시 시도해 주세요. 휴지통 문서는 영구 삭제될 때까지 용량에 포함됩니다."}}
            """;
    static final String DAILY_BODY = """
            {"error":{"code":"UPLOAD_DAILY_LIMIT_EXCEEDED","message":"하루에 올릴 수 있는 파일 수(200개)를 모두 사용했습니다. 내일 다시 시도해 주세요."}}
            """;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtTokenProvider jwt;
    @Autowired UploadLimitService limits;
    @Autowired DocumentService documents;
    @Autowired DocumentAssetContentService assetContent;
    @Autowired MinioClient minio;
    @Autowired StorageProperties storage;

    private final HttpClient http = HttpClient.newHttpClient();
    private String userId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_quota_" + suffix;
        workspaceId = "ws_quota_" + suffix;
        redis.opsForValue().set("authz:role:v2:" + workspaceId + ":" + userId, "{\"role\":\"OWNER\",\"permissions\":[]}");
    }

    // ---------- 저장 용량

    @Test
    void documentUpload_allowsExactlyQuota_thenRejectsWith413() throws Exception {
        seedUsage(QUOTA - 10);
        upload("a.md", "0123456789".getBytes()).andExpect(status().isCreated());
        assertThat(limits.usedBytes(userId)).isEqualTo(QUOTA);

        upload("b.md", "x".getBytes()).andExpect(status().isPayloadTooLarge())
                .andExpect(content().json(QUOTA_BODY, true));
        assertThat(documentCount()).isEqualTo(1);
    }

    @Test
    void usage_countsDocumentsAssetsRecordingsAndTrashedDocuments() throws Exception {
        upload("a.md", "12345".getBytes()).andExpect(status().isCreated());
        jdbc.update("UPDATE documents SET deleted_at = now() WHERE user_id = ?", userId);  // 휴지통도 포함
        seedUsage(100);
        jdbc.update("""
                INSERT INTO meetings (id, workspace_id, created_by, display_name, source, status, created_at, updated_at,
                                      recording_key, recording_bytes)
                VALUES (?, ?, ?, '회의', 'live', 'open', now(), now(), 'meetings/x/recording.wav', 1000)
                """, "mtg_" + UUID.randomUUID(), workspaceId, userId);
        jdbc.update("""
                INSERT INTO meetings (id, workspace_id, created_by, display_name, source, status, created_at, updated_at,
                                      recording_bytes)
                VALUES (?, ?, ?, '회의', 'live', 'open', now(), now(), 999999)
                """, "mtg_" + UUID.randomUUID(), workspaceId, userId);  // 원본 키가 없으면 제외
        assertThat(limits.usedBytes(userId)).isEqualTo(5 + 100 + 1000);
    }

    @Test
    void concurrentUploads_doNotJointlyExceedQuota() throws Exception {
        seedUsage(QUOTA - 30);
        List<Throwable> failures = race(8, index -> documents.upload(workspaceId, userId, "idem-" + UUID.randomUUID(),
                null, new MockMultipartFile("file", "c" + index + ".md", "text/markdown", "0123456789".getBytes())));
        assertThat(failures).hasSize(5).allSatisfy(error -> assertThat(error)
                .isInstanceOfSatisfying(UploadLimitException.class,
                        e -> assertThat(e.getCode()).isEqualTo("STORAGE_QUOTA_EXCEEDED")));
        assertThat(documentCount()).isEqualTo(3);
        assertThat(limits.usedBytes(userId)).isEqualTo(QUOTA);
    }

    @Test
    void idempotentReplay_isNotRejectedOrCountedAgain() throws Exception {
        seedUsage(QUOTA - 10);
        String key = "idem-" + UUID.randomUUID();
        var file = new MockMultipartFile("file", "a.md", "text/markdown", "0123456789".getBytes());
        documents.upload(workspaceId, userId, key, null, file);
        documents.upload(workspaceId, userId, key, null, file);
        assertThat(documentCount()).isEqualTo(1);
        assertThat(limits.uploadsToday(userId)).isEqualTo(1);
    }

    @Test
    void imageAttachments_areCountedAndRejectedOverQuota() throws Exception {
        String documentId = documents.createMarkdown(workspaceId, userId, "create-" + UUID.randomUUID(),
                new MarkdownDocumentCreateRequest("이미지 문서", "# 본문", null)).id();
        byte[] png = png();
        seedUsage(QUOTA - limits.usedBytes(userId) - png.length);
        saveImage(documentId, png);
        assertThat(limits.usedBytes(userId)).isEqualTo(QUOTA);
        assertThat(limits.uploadsToday(userId)).isEqualTo(1);

        int objectsBefore = assetObjects();
        assertThatThrownBy(() -> saveImage(documentId, png(7)))
                .isInstanceOfSatisfying(UploadLimitException.class,
                        e -> assertThat(e.getCode()).isEqualTo("STORAGE_QUOTA_EXCEEDED"));
        assertThat(assetObjects()).isEqualTo(objectsBefore);  // 거절된 저장의 객체는 보상 삭제된다
    }

    // ---------- 하루 개수

    @Test
    void dailyLimit_countsOnlyTodayInSeoul_andRejectsWith429() throws Exception {
        seedEvents(199, "0 seconds");
        // 서울 기준 어제 마지막 순간 기록은 오늘 개수에 들어가지 않는다.
        jdbc.update("""
                INSERT INTO user_upload_events (user_id, workspace_id, kind, byte_size, created_at)
                VALUES (?, ?, 'document', 1,
                        (date_trunc('day', now() AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul') - interval '1 second')
                """, userId, workspaceId);
        upload("200.md", "x".getBytes()).andExpect(status().isCreated());
        upload("201.md", "x".getBytes()).andExpect(status().isTooManyRequests())
                .andExpect(content().json(DAILY_BODY, true));
    }

    @Test
    void concurrentUploads_doNotJointlyExceedDailyLimit() throws Exception {
        seedEvents(198, "0 seconds");
        List<Throwable> failures = race(6, index -> documents.upload(workspaceId, userId, "idem-" + UUID.randomUUID(),
                null, new MockMultipartFile("file", "d" + index + ".md", "text/markdown", "x".getBytes())));
        assertThat(failures).hasSize(4).allSatisfy(error -> assertThat(error)
                .isInstanceOfSatisfying(UploadLimitException.class,
                        e -> assertThat(e.getCode()).isEqualTo("UPLOAD_DAILY_LIMIT_EXCEEDED")));
        assertThat(limits.uploadsToday(userId)).isEqualTo(200);
    }

    // ---------- 요청당 개수·PDF 크기

    @Test
    void documentUpload_withTwoFileParts_isRejectedWith400() throws Exception {
        mockMvc.perform(multipart("/api/workspaces/" + workspaceId + "/documents")
                        .file(new MockMultipartFile("file", "a.md", "text/markdown", "a".getBytes()))
                        .file(new MockMultipartFile("file", "b.md", "text/markdown", "b".getBytes()))
                        .header("Authorization", bearer()).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"error":{"code":"TOO_MANY_FILES","message":"파일은 한 번에 1개만 올릴 수 있습니다."}}
                        """, true));
        assertThat(documentCount()).isZero();
    }

    @Test
    void directPdfStart_overCap_isRejectedWith413_andQuotaIsPrechecked() throws Exception {
        startPdf(100L * 1024 * 1024).andExpect(status().isOk());
        startPdf(100L * 1024 * 1024 + 1).andExpect(status().isPayloadTooLarge()).andExpect(content().json("""
                {"error":{"code":"FILE_TOO_LARGE","message":"PDF 파일은 100MB 이하로 올려 주세요."}}
                """, true));
        seedUsage(QUOTA - 10);
        startPdf(11).andExpect(status().isPayloadTooLarge()).andExpect(content().json(QUOTA_BODY, true));
    }

    // ---------- 회의 녹음

    @Test
    void meetingRecording_isPrecheckedAtStart_andEnforcedAtComplete() throws Exception {
        String meetingId = createLiveMeeting();
        byte[] audio = "wav-bytes".getBytes();
        String ticket = json(startRecording(meetingId, audio.length), 200).path("ticket").asText();
        put(recordingPartUrl(meetingId, ticket), audio);
        // 시작 뒤 다른 업로드로 용량이 찼다: 확정 때 잠금 아래에서 다시 확인해 거절하고 조립한 객체를 지운다.
        seedUsage(QUOTA - audio.length + 1);
        recordingPost(meetingId, "complete", "{\"ticket\":\"" + ticket + "\"}")
                .andExpect(status().isPayloadTooLarge()).andExpect(content().json(QUOTA_BODY, true));
        assertThat(jdbc.queryForObject("SELECT recording_key FROM meetings WHERE id = ?", String.class, meetingId)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_upload_events WHERE user_id = ?", Integer.class, userId))
                .isZero();

        startRecording(meetingId, audio.length).andExpect(status().isPayloadTooLarge())
                .andExpect(content().json(QUOTA_BODY, true));
    }

    @Test
    void meetingRecording_completesAndIsCountedWithinQuota() throws Exception {
        String meetingId = createLiveMeeting();
        byte[] audio = "wav-bytes".getBytes();
        seedUsage(QUOTA - audio.length);
        String ticket = json(startRecording(meetingId, audio.length), 200).path("ticket").asText();
        put(recordingPartUrl(meetingId, ticket), audio);
        recordingPost(meetingId, "complete", "{\"ticket\":\"" + ticket + "\"}").andExpect(status().isOk());
        assertThat(limits.usedBytes(userId)).isEqualTo(QUOTA);
        assertThat(limits.uploadsToday(userId)).isEqualTo(1);
        String key = jdbc.queryForObject("SELECT recording_key FROM meetings WHERE id = ?", String.class, meetingId);
        assertThat(minio.statObject(StatObjectArgs.builder().bucket(storage.getBucket()).object(key).build()).size())
                .isEqualTo(audio.length);
    }

    // ---------- 사용량 조회

    @Test
    void storageUsage_returnsCallersTotalAcrossWorkspaces() throws Exception {
        upload("a.md", "12345".getBytes()).andExpect(status().isCreated());
        jdbc.update("""
                INSERT INTO document_assets (id, workspace_id, uploaded_by, original_filename, content_type, byte_size,
                                             width, height, content_hash, storage_key, created_at)
                VALUES (?, ?, ?, 'seed.png', 'image/png', 100, 1, 1, ?, ?, now())
                """, UUID.randomUUID(), "ws_other_" + UUID.randomUUID(), userId, "0".repeat(64), "seed/" + UUID.randomUUID());

        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/storage").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"used_bytes\":105,\"limit_bytes\":" + QUOTA + ",\"scope\":\"user\"}", true));
        assertThat(limits.usedBytes(userId)).isEqualTo(105);
    }

    @Test
    void storageUsage_forNonMember_is404() throws Exception {
        String stranger = "user_stranger_" + UUID.randomUUID();
        redis.opsForValue().set("authz:role:v2:" + workspaceId + ":" + stranger, "{\"role\":\"NONE\",\"permissions\":[]}");
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/storage")
                        .header("Authorization", "Bearer " + jwt.generateAccessToken(stranger, stranger + "@example.com")))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code")
                        .value("WORKSPACE_NOT_FOUND"));
    }

    // ---------- helpers

    interface Upload { Object run(int index) throws Exception; }

    private List<Throwable> race(int threads, Upload upload) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit((Callable<Object>) () -> {
                    ready.await();
                    return upload.run(index);
                }));
            }
            ready.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }

    private void seedUsage(long bytes) {
        jdbc.update("""
                INSERT INTO document_assets (id, workspace_id, uploaded_by, original_filename, content_type, byte_size,
                                             width, height, content_hash, storage_key, created_at)
                VALUES (?, ?, ?, 'seed.png', 'image/png', ?, 1, 1, ?, ?, now())
                """, UUID.randomUUID(), workspaceId, userId, bytes, "0".repeat(64), "seed/" + UUID.randomUUID());
    }

    private void seedEvents(int count, String age) {
        jdbc.update("""
                INSERT INTO user_upload_events (user_id, workspace_id, kind, byte_size, created_at)
                SELECT ?, ?, 'document', 1, now() - CAST(? AS interval) FROM generate_series(1, ?)
                """, userId, workspaceId, age, count);
    }

    private int documentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM documents WHERE user_id = ?", Integer.class, userId);
    }

    private int assetObjects() {
        int count = 0;
        for (var ignored : minio.listObjects(io.minio.ListObjectsArgs.builder().bucket(storage.getBucket())
                .prefix("assets/" + workspaceId + "/").recursive(true).build())) {
            count++;
        }
        return count;
    }

    private void saveImage(String documentId, byte[] image) {
        long base = jdbc.queryForObject("SELECT revision FROM document_edit_states WHERE document_id = ?",
                Long.class, documentId);
        UUID placeholder = UUID.randomUUID();
        String metadata = "{\"markdown\":\"![그림](attachment://" + placeholder + ")\",\"base_version\":" + base + "}";
        MultiValueMap<String, MultipartFile> parts = new LinkedMultiValueMap<>();
        parts.add("attachment_" + placeholder,
                new MockMultipartFile("attachment_" + placeholder, "image.png", "image/png", image));
        assetContent.save(workspaceId, userId, documentId, metadata, parts, null);
    }

    private static byte[] png() throws Exception {
        return png(1);
    }

    private static byte[] png(int rgb) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, rgb);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private ResultActions upload(String filename, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart("/api/workspaces/" + workspaceId + "/documents")
                .file(new MockMultipartFile("file", filename, "text/markdown", bytes))
                .header("Authorization", bearer()).header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    private ResultActions startPdf(long size) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + workspaceId + "/documents/uploads")
                .header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"filename\":\"a.pdf\",\"size\":" + size + "}"));
    }

    private String createLiveMeeting() throws Exception {
        return json(mockMvc.perform(post(meetings()).header("Authorization", bearer())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"display_name\":\"회의\",\"source\":\"live\"}")), 201).path("meeting_id").asText();
    }

    private ResultActions startRecording(String meetingId, long size) throws Exception {
        return mockMvc.perform(post(meetings() + "/" + meetingId + "/recording/uploads").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"content_type\":\"audio/wav\",\"size\":" + size + "}"));
    }

    private ResultActions recordingPost(String meetingId, String action, String body) throws Exception {
        return mockMvc.perform(post(meetings() + "/" + meetingId + "/recording/uploads/" + action)
                .header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String recordingPartUrl(String meetingId, String ticket) throws Exception {
        return json(recordingPost(meetingId, "parts", "{\"ticket\":\"" + ticket + "\",\"first_part\":1,\"count\":1}"), 200)
                .path("parts").get(0).path("url").asText();
    }

    private void put(String url, byte[] bytes) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private JsonNode json(ResultActions actions, int status) throws Exception {
        return objectMapper.readTree(actions.andExpect(status().is(status)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private String meetings() {
        return "/api/workspaces/" + workspaceId + "/meetings";
    }

    private String bearer() {
        return "Bearer " + jwt.generateAccessToken(userId, userId + "@example.com");
    }
}
