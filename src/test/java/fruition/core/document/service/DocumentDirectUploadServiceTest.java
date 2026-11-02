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
    static final long MB = 1024L * 1024;
    static final String SECRET = "test-secret-for-document-upload-ticket-only";
    StorageProperties props = new StorageProperties();
    DocumentDirectUploadService uploads;
    @BeforeEach void setup() throws Exception {
        props.setEndpoint("https://s3.ap-northeast-2.amazonaws.com");
        props.setBucket("test-bucket");
        uploads = service(100 * MB);
        when(multipart.start(anyString(), anyString())).thenReturn("upload-id");
    }
    DocumentDirectUploadService service(long maxPdfBytes) throws Exception {
        return service(new UploadLimitService(mock(JdbcTemplate.class), true, maxPdfBytes, 5L * 1024 * MB));
    }
    DocumentDirectUploadService service(UploadLimitService limits) throws Exception {
        return new DocumentDirectUploadService(storage, multipart, props, access, documents, limits, SECRET);
    }
    DocumentDirectUploadService.StartResponse start(long size) throws Exception {
        return uploads.start("ws_test", "user_test", new DocumentDirectUploadService.StartRequest("large.pdf", size, null));
    }
    @Test void pdfAtCapUses64MiBParts() throws Exception {
        var response = start(100 * MB);
        assertThat(response.part_size()).isEqualTo(64L * 1024 * 1024);
        assertThat(response.part_count()).isEqualTo(2);
        verify(multipart).start(startsWith("tmp/document-uploads/"), eq("application/pdf"));
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
}
