package fruition.core.document.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.upload.UploadLimitException;
import fruition.core.upload.UploadLimitService;
import fruition.shared.util.StorageProperties;
import fruition.shared.util.MultipartStorage;
import io.minio.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentDirectUploadServiceTest {
    MinioClient storage = mock(MinioClient.class);
    MultipartStorage multipart = mock(MultipartStorage.class);
    WorkspaceAccessGuard access = mock(WorkspaceAccessGuard.class);
    DocumentService documents = mock(DocumentService.class);
    DocumentAccessPolicy accessPolicy = mock(DocumentAccessPolicy.class);
    fruition.core.imports.ImportSessionStore importSessions = mock(fruition.core.imports.ImportSessionStore.class);
    static final java.util.UUID IMPORT_ID = java.util.UUID.fromString("11111111-2222-3333-4444-555555555555");
    static final long MB = 1024L * 1024;
    static final String SECRET = "test-secret-for-document-upload-ticket-only";
    StorageProperties props = new StorageProperties();
    DocumentDirectUploadService uploads;
    @BeforeEach void setup() throws Exception {
        props.setEndpoint("https://s3.ap-northeast-2.amazonaws.com");
        props.setBucket("test-bucket");
        uploads = service(100 * MB);
        when(multipart.start(anyString(), anyString())).thenReturn("upload-id");
        when(importSessions.resolve(anyString(), anyString(), isNull())).thenReturn(null);
        when(importSessions.resolve("ws_test", "user_test", IMPORT_ID.toString())).thenReturn(
                new fruition.core.imports.ImportSessionStore.OpenSession(IMPORT_ID, "ws_test", "user_test"));
    }
    DocumentDirectUploadService service(long maxPdfBytes) throws Exception {
        return service(new UploadLimitService(mock(JdbcTemplate.class), true, maxPdfBytes, 5L * 1024 * MB));
    }
    DocumentDirectUploadService service(UploadLimitService limits) throws Exception {
        return new DocumentDirectUploadService(storage, multipart, props, access, documents, limits, importSessions, accessPolicy, SECRET);
    }
    DocumentDirectUploadService.StartResponse start(long size) throws Exception {
        return uploads.start("ws_test", "user_test", new DocumentDirectUploadService.StartRequest("large.pdf", size, null));
    }
    @Test void pdfAtCapUses64MiBParts() throws Exception {
        var response = start(100 * MB);
        assertThat(response.part_size()).isEqualTo(64L * 1024 * 1024);
        assertThat(response.part_count()).isEqualTo(2);
        verify(multipart).start(startsWith("tmp/document-uploads/ws_test/user_test/"), eq("application/pdf"));
        verify(access).requireMember("ws_test", "user_test");
    }
    @Test void partUrlsCannotEscapeDeclaredRange() throws Exception {
        var response = start(100 * MB);
        assertThatThrownBy(() -> uploads.partUrls("ws_test", "user_test",
                new DocumentDirectUploadService.PartsRequest(response.ticket(), 2, 2)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> uploads.partUrls("ws_test", "user_test",
                new DocumentDirectUploadService.PartsRequest(response.ticket(), 0, 1)))
                .isInstanceOf(ResponseStatusException.class);
        verify(storage, never()).getPresignedObjectUrl(any());
    }
    @Test void malformedSizeAndFilenameAreRejectedBeforeSigning() throws Exception {
        for (long size : new long[]{0, -1}) {
            assertThatThrownBy(() -> start(size)).isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(() -> start(100 * MB + 1)).isInstanceOfSatisfying(UploadLimitException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            assertThat(error.getCode()).isEqualTo("FILE_TOO_LARGE");
            assertThat(error.getMessage()).isEqualTo("PDF 파일은 100MB 이하로 올려 주세요.");
        });
        assertThatThrownBy(() -> uploads.start("ws_test", "user_test",
                new DocumentDirectUploadService.StartRequest("../large.pdf", 5, null)))
                .isInstanceOf(ResponseStatusException.class);
        verify(multipart, never()).start(anyString(), anyString());
    }
    @Test void quotaPrecheckRejectsBeforeStartingMultipart() throws Exception {
        UploadLimitService limits = mock(UploadLimitService.class);
        doThrow(new UploadLimitException(HttpStatus.PAYLOAD_TOO_LARGE, "STORAGE_QUOTA_EXCEEDED", "full"))
                .when(limits).precheck("user_test", 5);
        assertThatThrownBy(() -> service(limits).start("ws_test", "user_test",
                new DocumentDirectUploadService.StartRequest("a.pdf", 5, null)))
                .isInstanceOf(UploadLimitException.class).hasMessage("full");
        verify(multipart, never()).start(anyString(), anyString());
    }
    @Test void placementIsCheckedBeforeStartingMultipart() throws Exception {
        java.util.UUID folder = java.util.UUID.randomUUID();
        doThrow(new fruition.core.document.exception.DocumentWriteForbiddenException("이 폴더에 넣거나 꺼낼 권한이 없습니다."))
                .when(accessPolicy).requirePlacement("ws_test", folder, "user_test");
        assertThatThrownBy(() -> uploads.start("ws_test", "user_test",
                new DocumentDirectUploadService.StartRequest("a.pdf", 5, folder)))
                .isInstanceOf(fruition.core.document.exception.DocumentWriteForbiddenException.class);
        verify(multipart, never()).start(anyString(), anyString());
    }
    @Test void assembledObjectOverCurrentCapIsRejectedEvenWithOlderTicket() throws Exception {
        // 한도를 낮추기 전에 발급된 ticket(150MB)으로 확정하면 조립된 실제 크기로 거절한다.
        var ticket = new DocumentDirectUploadService.CompleteRequest(service(200 * MB).start("ws_test", "user_test",
                new DocumentDirectUploadService.StartRequest("big.pdf", 150 * MB, null)).ticket());
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(150 * MB);
        when(stat.contentType()).thenReturn("application/pdf");
        when(storage.statObject(any())).thenReturn(stat);
        assertThatThrownBy(() -> uploads.complete("ws_test", "user_test", "key", ticket))
                .isInstanceOfSatisfying(UploadLimitException.class,
                        error -> assertThat(error.getCode()).isEqualTo("FILE_TOO_LARGE"));
        verifyNoInteractions(documents);
    }
    @Test void forgedTicketDoesNotTouchStorage() throws Exception {
        assertThatThrownBy(() -> uploads.complete("ws_test", "user_test", "key",
                new DocumentDirectUploadService.CompleteRequest("tampered"))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(documents);
        verify(storage, never()).statObject(any());
    }
    @Test void validlySignedTicketWithForeignKeyIsRejected() throws Exception {
        // 서명 키가 새도 발급 형식이 아닌 키는 쓸 수 없다.
        var signing = io.jsonwebtoken.security.Keys.hmacShaKeyFor(java.security.MessageDigest.getInstance("SHA-256")
                .digest((SECRET + ":document-upload-ticket:v1").getBytes(StandardCharsets.UTF_8)));
        String uuid = java.util.UUID.randomUUID().toString();
        for (String key : new String[]{"documents/doc_other/original.pdf", "tmp/document-uploads/../x",
                "tmp/document-uploads/ws_test/user_test/not-a-uuid",
                // 서버 발급 형식이지만 다른 사용자·워크스페이스의 임시 객체
                "tmp/document-uploads/ws_test/user_other/" + uuid, "tmp/document-uploads/ws_other/user_test/" + uuid,
                "tmp/document-uploads/" + uuid}) {
            String forged = io.jsonwebtoken.Jwts.builder().subject("user_test").audience().add("document-upload-ticket").and()
                    .claim("workspace", "ws_test").claim("key", key).claim("size", 5).claim("upload", "u")
                    .claim("partSize", 64 * MB).claim("filename", "a.pdf").claim("folder", "")
                    .expiration(new java.util.Date(System.currentTimeMillis() + 60_000)).signWith(signing).compact();
            assertThatThrownBy(() -> uploads.complete("ws_test", "user_test", "key",
                    new DocumentDirectUploadService.CompleteRequest(forged)))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                            .isIn(HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN));
            assertThatThrownBy(() -> uploads.partUrls("ws_test", "user_test",
                    new DocumentDirectUploadService.PartsRequest(forged, 1, 1))).isInstanceOf(ResponseStatusException.class);
        }
        verify(storage, never()).statObject(any());
        verify(storage, never()).getPresignedObjectUrl(any());
        verifyNoInteractions(documents);
    }
    @Test void anotherUserOrWorkspaceCannotCompleteTicket() throws Exception {
        var ticket = new DocumentDirectUploadService.CompleteRequest(start(5).ticket());
        assertThatThrownBy(() -> uploads.complete("ws_other", "user_test", "key", ticket))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> uploads.complete("ws_test", "user_other", "key", ticket))
                .isInstanceOf(ResponseStatusException.class);
        verify(storage, never()).statObject(any());
    }
    @Test void revocationIsCheckedAgainOnCompletion() throws Exception {
        var ticket = new DocumentDirectUploadService.CompleteRequest(start(5).ticket());
        doThrow(new IllegalStateException("revoked")).when(access).requireMember("ws_test", "user_test");
        assertThatThrownBy(() -> uploads.complete("ws_test", "user_test", "key", ticket)).hasMessage("revoked");
        verify(storage, never()).statObject(any());
    }
    @Test void differentStoredSizeIsRejected() throws Exception {
        var ticket = new DocumentDirectUploadService.CompleteRequest(start(5).ticket());
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(10L);
        when(storage.statObject(any())).thenReturn(stat);
        assertThatThrownBy(() -> uploads.complete("ws_test", "user_test", "key", ticket))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(documents);
    }
    @Test void completionPinsVersionAndReusesExistingIdempotentRegistration() throws Exception {
        var ticket = new DocumentDirectUploadService.CompleteRequest(start(5).ticket());
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(5L);
        when(stat.contentType()).thenReturn("application/pdf");
        when(stat.versionId()).thenReturn("immutable-version");
        when(stat.etag()).thenReturn("etag");
        when(storage.statObject(any())).thenReturn(stat);
        when(storage.getObject(any())).thenAnswer(inv -> {
            GetObjectArgs args = inv.getArgument(0);
            assertThat(args.versionId()).isEqualTo("immutable-version");
            assertThat(args.matchETag()).isEqualTo("etag");
            return new GetObjectResponse(new okhttp3.Headers.Builder().build(), "test-bucket", "region", "key",
                    new ByteArrayInputStream("%PDF-".getBytes(StandardCharsets.US_ASCII)));
        });
        uploads.complete("ws_test", "user_test", "same-idempotency-key", ticket);
        verify(documents).upload(eq("ws_test"), eq("user_test"), eq("same-idempotency-key"), isNull(),
                argThat(file -> file.getSize() == 5 && file.getContentType().equals("application/pdf")));
    }

    StatObjectResponse storedPdf() throws Exception {
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(5L);
        when(stat.contentType()).thenReturn("application/pdf");
        when(stat.etag()).thenReturn("etag");
        when(storage.statObject(any())).thenReturn(stat);
        when(storage.getObject(any())).thenAnswer(inv -> new GetObjectResponse(new okhttp3.Headers.Builder().build(),
                "test-bucket", "region", "key", new ByteArrayInputStream("%PDF-".getBytes(StandardCharsets.US_ASCII))));
        return stat;
    }
    DocumentDirectUploadService.CompleteRequest importTicket() throws Exception {
        return new DocumentDirectUploadService.CompleteRequest(uploads.start("ws_test", "user_test",
                new DocumentDirectUploadService.StartRequest("a.pdf", 5, null), IMPORT_ID.toString()).ticket());
    }
    @Test void invalidImportSessionIsRejectedBeforeStartingMultipart() throws Exception {
        when(importSessions.resolve("ws_test", "user_test", "closed")).thenThrow(
                fruition.core.imports.DocumentImportException.invalid());
        assertThatThrownBy(() -> uploads.start("ws_test", "user_test",
                new DocumentDirectUploadService.StartRequest("a.pdf", 5, null), "closed"))
                .isInstanceOfSatisfying(fruition.core.imports.DocumentImportException.class,
                        error -> assertThat(error.getCode()).isEqualTo("IMPORT_INVALID"));
        verify(multipart, never()).start(anyString(), anyString());
    }
    @Test void importTicketCompletesAsImportedDocument() throws Exception {
        var ticket = importTicket();
        storedPdf();
        uploads.complete("ws_test", "user_test", "import-key", ticket, IMPORT_ID.toString());
        verify(documents).uploadImported(eq("ws_test"), eq("user_test"), eq("import-key"), isNull(),
                argThat(file -> file.getSize() == 5), eq(IMPORT_ID.toString()));
        verify(documents, never()).upload(any(), any(), any(), any(), any());
    }
    @Test void importTicketAndHeaderMustMatch() throws Exception {
        var importTicket = importTicket();
        var plainTicket = new DocumentDirectUploadService.CompleteRequest(start(5).ticket());
        // 시작 때 묶은 세션 없이 확정하거나, 일반 ticket에 세션을 붙여 확정하면 IMPORT_INVALID다.
        for (var call : java.util.List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                () -> uploads.complete("ws_test", "user_test", "k", importTicket, null),
                () -> uploads.complete("ws_test", "user_test", "k", plainTicket, IMPORT_ID.toString()))) {
            assertThatThrownBy(call).isInstanceOfSatisfying(fruition.core.imports.DocumentImportException.class,
                    error -> assertThat(error.getCode()).isEqualTo("IMPORT_INVALID"));
        }
        verify(storage, never()).statObject(any());
        verifyNoInteractions(documents);
    }
}
