package fruition.core.document.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.shared.util.*;
import io.minio.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentMultipartStorageIntegrationTest {
    @Test void multipartRoundTripAndStorageSideCopyPreserveBytes() throws Exception {
        try (var container = new MinIOContainer(DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
                .asCompatibleSubstituteFor("minio/minio"))) {
            container.start();
            var props = new StorageProperties();
            props.setEndpoint(container.getS3URL()); props.setBucket("multipart-test");
            props.setAccessKey(container.getUserName()); props.setSecretKey(container.getPassword());
            props.setRegion("us-east-1");
            MinioClient storage = new MinioConfig().minioClient(props);
            storage.makeBucket(MakeBucketArgs.builder().bucket(props.getBucket()).build());
            var multipart = new MultipartStorage(props);
            var documents = mock(DocumentService.class);
            var service = new DocumentDirectUploadService(storage, multipart, props, mock(WorkspaceAccessGuard.class), documents,
                    "test-only-direct-upload-secret");
            long size = 65L * 1024 * 1024;
            var start = service.start("ws_test", "user_test", new DocumentDirectUploadService.StartRequest("large.pdf", size, null));
            var urls = service.partUrls("ws_test", "user_test", new DocumentDirectUploadService.PartsRequest(start.ticket(), 1, 2));
            var http = HttpClient.newHttpClient();
            for (var part : urls.parts()) {
                int length = (int) Math.min(start.part_size(), size - (part.part_number() - 1L) * start.part_size());
                byte[] content = new byte[length];
                if (part.part_number() == 1) System.arraycopy("%PDF-".getBytes(), 0, content, 0, 5);
                var response = http.send(HttpRequest.newBuilder(URI.create(part.url()))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            }
            when(documents.upload(anyString(), anyString(), anyString(), any(), any())).thenAnswer(inv -> {
                StoredOriginal file = inv.getArgument(4);
                assertThat(file.getSize()).isEqualTo(size);
                file.copyTo("sources/documents/doc_test/original");
                assertThat(file.storageFingerprint()).hasSize(64);
                return null;
            });
            service.complete("ws_test", "user_test", "same-key", new DocumentDirectUploadService.CompleteRequest(start.ticket()));
            assertThat(storage.statObject(StatObjectArgs.builder().bucket(props.getBucket())
                    .object("sources/documents/doc_test/original").build()).size()).isEqualTo(size);
            // Complete 응답 유실 뒤에도 NoSuchUpload로 실패하지 않고 등록을 멱등하게 재시도한다.
            service.complete("ws_test", "user_test", "same-key", new DocumentDirectUploadService.CompleteRequest(start.ticket()));
            verify(documents, times(2)).upload(eq("ws_test"), eq("user_test"), eq("same-key"), isNull(), any());
        }
    }
}
