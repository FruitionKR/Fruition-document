package fruition.core.document.service;

import fruition.TestcontainersConfiguration;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.exception.DocumentUploadException;
import fruition.core.document.repository.DocumentRepository;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * 새 문서 원본 저장(MinIO)은 멱등 선점 뒤, 실행 트랜잭션을 열기 전에 한다. 저장이 느려도 그동안 DB 연결을 쥐지 않는다.
 * 실행이 실패하면 미리 저장한 원본은 지운다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SourceStoreOutsideTransactionIntegrationTest {

    @Autowired DocumentService documentService;
    @Autowired StringRedisTemplate redis;
    @Autowired StorageProperties storage;
    @MockitoSpyBean MinioClient minioClient;
    @MockitoSpyBean DocumentRepository documentRepository;

    @Test
    void markdownCreateAndUploadStoreSourceOutsideTransaction() throws Exception {
        List<Boolean> inTransaction = new ArrayList<>();
        recordPuts(inTransaction, new ArrayList<>());
        String workspaceId = "ws_src_tx_" + UUID.randomUUID();
        String userId = owner(workspaceId);

        documentService.createMarkdown(workspaceId, userId, "md-" + UUID.randomUUID(),
                new MarkdownDocumentCreateRequest("노트", "# 노트", null));
        documentService.upload(workspaceId, userId, "up-" + UUID.randomUUID(), null, markdownFile());

        assertThat(inTransaction).containsExactly(false, false);
    }

    @Test
    void storedSourceIsDeletedWhenExecutionFails() throws Exception {
        List<String> keys = new ArrayList<>();
        recordPuts(new ArrayList<>(), keys);
        String workspaceId = "ws_src_tx_" + UUID.randomUUID();
        String userId = owner(workspaceId);
        // 원본 저장 뒤 실행 트랜잭션 안의 첫 조회(같은 폴더 이름 목록)를 실패시킨다.
        doThrow(new IllegalStateException("boom"))
                .when(documentRepository).findActiveSiblingNames(eq(workspaceId), any());

        assertThatThrownBy(() -> documentService.upload(workspaceId, userId, "up-" + UUID.randomUUID(), null,
                markdownFile())).isInstanceOf(DocumentUploadException.class);

        assertThat(keys).hasSize(1);
        assertThatThrownBy(() -> minioClient.statObject(StatObjectArgs.builder()
                .bucket(storage.getBucket()).object(keys.getFirst()).build()))
                .isInstanceOf(io.minio.errors.ErrorResponseException.class);
    }

    private void recordPuts(List<Boolean> inTransaction, List<String> keys) throws Exception {
        doAnswer(invocation -> {
            inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            keys.add(invocation.<PutObjectArgs>getArgument(0).object());
            return invocation.callRealMethod();
        }).when(minioClient).putObject(any(PutObjectArgs.class));
    }

    private static MockMultipartFile markdownFile() {
        return new MockMultipartFile("file", "up.md", "text/markdown", "# 업로드".getBytes(StandardCharsets.UTF_8));
    }

    private String owner(String workspaceId) {
        String userId = "user_src_tx_" + UUID.randomUUID();
        redis.opsForValue().set("authz:role:v2:" + workspaceId + ":" + userId,
                "{\"role\":\"OWNER\",\"permissions\":[]}");
        return userId;
    }
}
