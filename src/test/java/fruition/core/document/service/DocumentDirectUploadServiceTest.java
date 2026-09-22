package fruition.core.document.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.shared.util.StorageProperties;
import fruition.shared.util.MultipartStorage;
import io.minio.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    DocumentDirectUploadService uploads;
    @BeforeEach void setup() throws Exception {
        StorageProperties props = new StorageProperties();
        props.setEndpoint("https://s3.ap-northeast-2.amazonaws.com");
        props.setBucket("test-bucket");
        uploads = new DocumentDirectUploadService(storage, multipart, props, access, documents,
                "test-secret-for-document-upload-ticket-only");
        when(multipart.start(anyString())).thenReturn("upload-id");
    }
    DocumentDirectUploadService.StartResponse start(long size) throws Exception {
        return uploads.start("ws_test", "user_test", new DocumentDirectUploadService.StartRequest("large.pdf", size, null));
    }
    @Test void originalLargerThan2GiBUses64MiBParts() throws Exception {
        var response = start(3L * 1024 * 1024 * 1024);
        assertThat(response.part_size()).isEqualTo(64L * 1024 * 1024);
        assertThat(response.part_count()).isEqualTo(48);
        verify(multipart).start(startsWith("tmp/document-uploads/"));
        verify(access).requireMember("ws_test", "user_test");
    }
    @Test void partUrlsCannotEscapeDeclaredRange() throws Exception {
        var response = start(3L * 1024 * 1024 * 1024);
        assertThatThrownBy(() -> uploads.partUrls("ws_test", "user_test",
                new DocumentDirectUploadService.PartsRequest(response.ticket(), 48, 2)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> uploads.partUrls("ws_test", "user_test",
                new DocumentDirectUploadService.PartsRequest(response.ticket(), 0, 1)))
                .isInstanceOf(ResponseStatusException.class);
        verify(storage, never()).getPresignedObjectUrl(any());
    }
    @Test void malformedSizeAndFilenameAreRejectedBeforeSigning() throws Exception {
        for (long size : new long[]{0, -1, 5L * 1024 * 1024 * 1024 * 1024 + 1}) {
            assertThatThrownBy(() -> start(size)).isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(() -> uploads.start("ws_test", "user_test",
                new DocumentDirectUploadService.StartRequest("../large.pdf", 5, null)))
                .isInstanceOf(ResponseStatusException.class);
        verify(multipart, never()).start(anyString());
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
