package fruition.core.document.service;

import fruition.TestcontainersConfiguration;
import fruition.core.document.domain.Document;
import fruition.core.document.repository.DocumentRepository;
import fruition.shared.idempotency.IdempotencyService;
import fruition.shared.logging.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/** 업로드 로그가 실제 커밋·롤백 결과와 맞는지 실제 Postgres·MinIO로 확인한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DocumentUploadLogIntegrationTest {
    @Autowired DocumentService documents;
    @Autowired StringRedisTemplate redis;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoSpyBean DocumentRepository documentRepository;

    private String userId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_log_" + suffix;
        workspaceId = "ws_log_" + suffix;
        redis.opsForValue().set("authz:role:v2:" + workspaceId + ":" + userId, "{\"role\":\"OWNER\",\"permissions\":[]}");
    }

    private MockMultipartFile markdown(String name) {
        return new MockMultipartFile("file", name, "text/markdown", "# 비밀 본문".getBytes());
    }

    private static List<String> matching(LogCapture capture, String prefix) {
        return capture.messages().stream().filter(m -> m.startsWith(prefix)).toList();
    }

    @Test
    void successLogsCompletionOnceAndReplayDoesNotLogNewCompletion() {
        String key = "idem-" + UUID.randomUUID();
        try (var capture = new LogCapture(DocumentService.class); var idempotency = new LogCapture(IdempotencyService.class)) {
            String id = documents.upload(workspaceId, userId, key, null, markdown("a.md")).id();
            documents.upload(workspaceId, userId, key, null, markdown("a.md"));

            assertThat(matching(capture, "[문서 업로드 완료]")).hasSize(1)
                    .allMatch(m -> m.contains("operation=document_upload stage=completed") && m.contains("documentId=" + id) && !m.contains("비밀"));
            // 단계 로그는 콘솔 전용이다. 전송 대상은 완료 한 줄뿐이다.
            assertThat(capture.messages().stream().filter(m -> m.contains("operation=document_upload"))).hasSize(1);
            assertThat(idempotency.messages()).anyMatch(m -> m.startsWith("[멱등 재요청 재생] operation=idempotent_replay stage=replayed"));
        }
    }

    @Test
    void invalidPdfIsRejectedWithStageCodeAndKoreanCause() {
        var notPdf = new MockMultipartFile("file", "a.pdf", "application/pdf", "not a pdf".getBytes());
        try (var capture = new LogCapture(DocumentService.class)) {
            assertThatThrownBy(() -> documents.upload(workspaceId, userId, "idem-" + UUID.randomUUID(), null, notPdf));
            assertThat(matching(capture, "[문서 업로드 거부]")).hasSize(1).allMatch(m -> m.contains("stage=validation")
                    && m.contains("errorCode=INVALID_PDF") && m.contains("원인=파일 내용이 PDF 형식이 아닙니다."));
            assertThat(matching(capture, "[문서 업로드 완료]")).isEmpty();
        }
    }

    @Test
    void databaseFailureRollsBackWithoutSuccessAndReportsCleanup() {
        Mockito.doThrow(new IllegalStateException("db down")).when(documentRepository).save(any(Document.class));
        try (var capture = new LogCapture(DocumentService.class)) {
            assertThatThrownBy(() -> documents.upload(workspaceId, userId, "idem-" + UUID.randomUUID(), null, markdown("db.md")));
            assertThat(matching(capture, "[문서 업로드 완료]")).isEmpty();
            assertThat(matching(capture, "[문서 DB 저장 완료]")).isEmpty();
            assertThat(matching(capture, "[문서 등록 실패]")).hasSize(1).allMatch(m -> m.contains("stage=db_register")
                    && m.contains("errorCode=DOCUMENT_REGISTER_FAILED") && m.contains("partial=true") && m.contains("원인=")
                    && m.contains("documentId=doc_"));
            // 롤백 정리와 요청 실패 정리가 겹쳐도 원본 정리는 한 번만 남는다.
            assertThat(matching(capture, "[문서 원본 정리 완료]")).hasSize(1).allMatch(m -> m.contains("trigger=transaction_rollback"));
        } finally {
            Mockito.reset(documentRepository);
        }
    }

    @Test
    void quotaRejectionRollsBackWithLimitCodeAndWithoutRegistrationFailure() {
        jdbc.update("""
                INSERT INTO document_assets (id, workspace_id, uploaded_by, original_filename, content_type, byte_size,
                                             width, height, content_hash, storage_key, created_at)
                VALUES (?, ?, ?, 'seed.png', 'image/png', ?, 1, 1, ?, ?, now())
                """, UUID.randomUUID(), workspaceId, userId, 5L * 1024 * 1024 * 1024, "0".repeat(64), "seed/" + UUID.randomUUID());
        try (var capture = new LogCapture(DocumentService.class)) {
            assertThatThrownBy(() -> documents.upload(workspaceId, userId, "idem-" + UUID.randomUUID(), null, markdown("q.md")));
            assertThat(matching(capture, "[문서 업로드 거부]")).hasSize(1)
                    .allMatch(m -> m.contains("stage=limit") && m.contains("errorCode=STORAGE_QUOTA_EXCEEDED"));
            assertThat(matching(capture, "[문서 등록 실패]")).isEmpty();
            assertThat(matching(capture, "[문서 업로드 완료]")).isEmpty();
        }
    }

    @Test
    void creditRejectionIsNotReportedAsRegistrationFailure() {
        Mockito.doThrow(new fruition.core.usage.service.CreditService.InsufficientCreditException("크레딧 부족"))
                .when(documentRepository).save(any(Document.class));
        try (var capture = new LogCapture(DocumentService.class)) {
            assertThatThrownBy(() -> documents.createMarkdown(workspaceId, userId, "idem-" + UUID.randomUUID(),
                    new fruition.core.document.dto.MarkdownDocumentCreateRequest("c.md", "# 본문", null)));
            assertThat(matching(capture, "[문서 등록 실패]")).isEmpty();
            assertThat(matching(capture, "[문서 원본 정리 완료]")).hasSize(1);
        } finally {
            Mockito.reset(documentRepository);
        }
    }

    @Autowired FolderService folders;
    @Autowired DocumentPlacementService placement;

    @Test
    void folderAndMoveLogsOnlyRealChangesAndNotReplays() {
        String key = "idem-" + UUID.randomUUID();
        try (var folderLog = new LogCapture(FolderService.class); var moveLog = new LogCapture(DocumentPlacementService.class)) {
            var a = folders.create(workspaceId, userId, key, new fruition.core.document.dto.FolderCreateRequest("A", null));
            folders.create(workspaceId, userId, key, new fruition.core.document.dto.FolderCreateRequest("A", null));
            var b = folders.create(workspaceId, userId, "idem-" + UUID.randomUUID(), new fruition.core.document.dto.FolderCreateRequest("B", null));
            folders.rename(workspaceId, userId, a.id(), "idem-" + UUID.randomUUID(), new fruition.core.document.dto.FolderRenameRequest("A2", 1L));
            folders.move(workspaceId, userId, b.id(), "idem-" + UUID.randomUUID(), new fruition.core.document.dto.FolderPositionRequest(a.id(), null, 1L));
            var doc = documents.upload(workspaceId, userId, "idem-" + UUID.randomUUID(), null, markdown("m.md"));
            placement.move(workspaceId, userId, doc.id(), "idem-" + UUID.randomUUID(),
                    new fruition.core.document.dto.DocumentPositionRequest(null, 0, doc.currentVersion()));
            placement.move(workspaceId, userId, doc.id(), "idem-" + UUID.randomUUID(),
                    new fruition.core.document.dto.DocumentPositionRequest(a.id(), null, doc.currentVersion() + 1));

            assertThat(matching(folderLog, "[폴더 생성 완료]")).hasSize(2);
            assertThat(matching(folderLog, "[폴더 이름 변경 완료]")).hasSize(1);
            assertThat(matching(folderLog, "[폴더 이동 완료]")).singleElement().asString().contains("toFolderId=" + a.id());
            // 같은 폴더 안 순서 변경은 남기지 않고, 폴더가 바뀐 이동만 남긴다.
            assertThat(matching(moveLog, "[문서 이동 완료]")).singleElement().asString().contains("toFolderId=" + a.id());
        }
    }

    @Test
    void storageFailureIsLoggedAtStorageStage() {
        var broken = new MockMultipartFile("file", "s.pdf", "application/pdf", "%PDF-1.7".getBytes()) {
            private int reads;
            @Override public java.io.InputStream getInputStream() throws java.io.IOException {
                // 앞의 시그니처 확인·해시 계산은 통과시키고 저장 단계 읽기에서만 실패시킨다.
                if (++reads <= 2) return super.getInputStream();
                throw new java.io.IOException("storage read broken");
            }
        };
        try (var capture = new LogCapture(DocumentService.class)) {
            assertThatThrownBy(() -> documents.upload(workspaceId, userId, "idem-" + UUID.randomUUID(), null, broken));
            assertThat(matching(capture, "[문서 업로드 실패]")).hasSize(1).allMatch(m -> m.contains("stage=storage")
                    && m.contains("errorCode=STORAGE_WRITE_FAILED") && m.contains("retryable=true") && !m.contains("storage read broken"));
            assertThat(matching(capture, "[문서 업로드 완료]")).isEmpty();
        }
    }
}
