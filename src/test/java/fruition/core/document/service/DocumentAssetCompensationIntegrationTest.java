package fruition.core.document.service;

import fruition.TestcontainersConfiguration;
import fruition.core.document.dto.DocumentContentSaveResponse;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.exception.DocumentAssetStorageException;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.shared.util.StorageProperties;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 충돌한 이미지 저장의 보상 처리(#39). asset 키가 (문서, base revision, placeholder)로 결정되므로
 * 옛 base와 기존 placeholder로 다시 저장하면 이미 커밋된 객체를 덮어쓰고 409 뒤 보상으로 지워,
 * 그 객체를 참조하던 복제본의 이미지가 깨졌다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DocumentAssetCompensationIntegrationTest {

    @Autowired DocumentAssetContentService contentService;
    @Autowired DocumentService documentService;
    @Autowired DocumentAssetObjectStorage objectStorage;
    @Autowired DocumentAssetStorageCoordinator coordinator;
    @Autowired MinioClient minioClient;
    @Autowired StorageProperties storageProperties;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    private String workspaceId;
    private String authorId;
    private String duplicatorId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        workspaceId = "ws_asset_" + suffix;
        authorId = "user_author_" + suffix;
        duplicatorId = "user_dup_" + suffix;
        seedRole(authorId, "MEMBER");
        seedRole(duplicatorId, "MEMBER");
    }

    @Test
    void staleSaveWithCommittedPlaceholderKeepsImageReferencedByDuplicate() throws Exception {
        String documentId = createDocument();
        long base = revision(documentId);
        UUID placeholder = UUID.randomUUID();
        byte[] original = png(Color.RED);
        DocumentContentSaveResponse first = save(documentId, base, placeholder, original);
        UUID assetId = first.attachments().getFirst().assetId();
        String storageKey = jdbcTemplate.queryForObject(
                "SELECT storage_key FROM document_assets WHERE id = ?", String.class, assetId);
        String duplicateId = documentService.duplicate(workspaceId, duplicatorId, documentId,
                "dup-" + UUID.randomUUID()).id();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_asset_references WHERE asset_id = ? AND document_id = ?",
                Integer.class, assetId, duplicateId)).isEqualTo(1);

        assertThatThrownBy(() -> save(documentId, base, placeholder, png(Color.BLUE)))
                .isInstanceOf(DocumentVersionConflictException.class);

        try (InputStream content = objectStorage.get(storageKey)) {
            assertThat(content.readAllBytes()).isEqualTo(original);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_assets WHERE id = ?", Integer.class, assetId)).isEqualTo(1);
    }

    /**
     * 같은 asset ID로 겹친 두 저장: 둘 다 행이 커밋되기 전에 객체를 쓰고, 이긴 쪽이 커밋한 뒤 진 쪽이 409로
     * 보상한다. key가 결정적이던 때는 진 쪽 put이 이긴 쪽 객체를 덮어쓰고 보상이 그 객체를 지웠다.
     */
    @Test
    void interleavedSaveLoserCompensationKeepsWinnerObject() throws Exception {
        String documentId = createDocument();
        long base = revision(documentId);
        UUID placeholder = UUID.randomUUID();
        byte[] winnerBytes = png(Color.RED);
        byte[] loserBytes = png(Color.BLUE);
        var winner = coordinator.storeAll(workspaceId, documentId, base, Map.of(placeholder, validated(winnerBytes)));
        var loser = coordinator.storeAll(workspaceId, documentId, base, Map.of(placeholder, validated(loserBytes)));
        UUID assetId = winner.get(placeholder).assetId();
        String markdown = "![그림](/api/workspaces/" + workspaceId + "/assets/" + assetId + "/content)";

        documentService.saveContentWithAssets(workspaceId, authorId, documentId, markdown, base,
                "write-winner-" + UUID.randomUUID(), winner, null);
        assertThatThrownBy(() -> documentService.saveContentWithAssets(workspaceId, authorId, documentId,
                markdown + "\n늦은 저장", base, "write-loser-" + UUID.randomUUID(), loser, null))
                .isInstanceOf(DocumentVersionConflictException.class);
        coordinator.compensate(loser.values());

        String committedKey = jdbcTemplate.queryForObject(
                "SELECT storage_key FROM document_assets WHERE id = ?", String.class, assetId);
        assertThat(committedKey).isEqualTo(winner.get(placeholder).objectKey());
        try (InputStream content = objectStorage.get(committedKey)) {
            assertThat(content.readAllBytes()).isEqualTo(winnerBytes);
        }
        assertThatThrownBy(() -> objectStorage.get(loser.get(placeholder).objectKey()))
                .isInstanceOf(DocumentAssetStorageException.class);
    }

    @Test
    void interleavedIdenticalRetryKeepsCommittedKeyAndCleansUnusedAttempt() throws Exception {
        String documentId = createDocument();
        long base = revision(documentId);
        UUID placeholder = UUID.randomUUID();
        byte[] bytes = png(Color.RED);
        var first = coordinator.storeAll(workspaceId, documentId, base, Map.of(placeholder, validated(bytes)));
        var retry = coordinator.storeAll(workspaceId, documentId, base, Map.of(placeholder, validated(bytes)));
        UUID assetId = first.get(placeholder).assetId();
        String markdown = "![그림](/api/workspaces/" + workspaceId + "/assets/" + assetId + "/content)";
        String writeId = "same-write-" + UUID.randomUUID();
        documentService.saveContentWithAssets(workspaceId, authorId, documentId, markdown, base, writeId, first, null);
        var replay = documentService.saveContentWithAssets(workspaceId, authorId, documentId, markdown, base, writeId, retry, null);
        coordinator.compensate(retry.values());
        assertThat(replay.currentVersion()).isEqualTo(base + 1);
        assertThat(jdbcTemplate.queryForObject("SELECT storage_key FROM document_assets WHERE id = ?",
                String.class, assetId)).isEqualTo(first.get(placeholder).objectKey());
        try (InputStream content = objectStorage.get(first.get(placeholder).objectKey())) {
            assertThat(content.readAllBytes()).isEqualTo(bytes);
        }
        assertThatThrownBy(() -> objectStorage.get(retry.get(placeholder).objectKey()))
                .isInstanceOf(DocumentAssetStorageException.class);
    }

    /** 대조군: 충돌한 저장이 새로 쓴 객체는 여전히 보상으로 지운다. */
    @Test
    void staleSaveWithNewPlaceholderCompensatesItsOwnObject() throws Exception {
        String documentId = createDocument();
        long base = revision(documentId);
        save(documentId, base, UUID.randomUUID(), png(Color.RED));
        UUID newPlaceholder = UUID.randomUUID();

        assertThatThrownBy(() -> save(documentId, base, newPlaceholder, png(Color.GREEN)))
                .isInstanceOf(DocumentVersionConflictException.class);

        String seed = workspaceId + "\0" + documentId + "\0" + base + "\0" + newPlaceholder;
        UUID staleAssetId = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
        assertThat(minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(storageProperties.getBucket())
                .prefix("assets/" + workspaceId + "/" + staleAssetId + "/")
                .recursive(true)
                .build()).iterator().hasNext()).isFalse();
    }

    private String createDocument() {
        return documentService.createMarkdown(workspaceId, authorId, "create-" + UUID.randomUUID(),
                new MarkdownDocumentCreateRequest("이미지 문서", "# 본문", null)).id();
    }

    private long revision(String documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT revision FROM document_edit_states WHERE document_id = ?", Long.class, documentId);
    }

    private DocumentContentSaveResponse save(String documentId, long base, UUID placeholder, byte[] image) {
        String metadata = "{\"markdown\":\"![그림](attachment://" + placeholder + ")\",\"base_version\":" + base + "}";
        MultiValueMap<String, MultipartFile> parts = new LinkedMultiValueMap<>();
        parts.add("attachment_" + placeholder, new MockMultipartFile(
                "attachment_" + placeholder, "image.png", "image/png", image));
        return contentService.save(workspaceId, authorId, documentId, metadata, parts, null);
    }

    private void seedRole(String userId, String role) {
        redisTemplate.opsForValue().set("authz:role:v2:" + workspaceId + ":" + userId,
                "{\"role\":\"" + role + "\",\"permissions\":[]}");
    }

    private static DocumentAssetValidator.ValidatedAsset validated(byte[] image) {
        return new DocumentAssetValidator.ValidatedAsset("image.png", "image/png", image, 2, 2, "a".repeat(64));
    }

    private static byte[] png(Color color) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
