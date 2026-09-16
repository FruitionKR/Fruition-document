package fruition.core.document.service;

import fruition.core.document.domain.Document;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.shared.util.StorageProperties;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentEditStateInitializerTest {

    @Mock DocumentEditStateRepository editStateRepository;
    @Mock MinioClient minioClient;
    @Mock StorageProperties storageProperties;

    @Test
    void editableMarkdownWithoutState_isLoadedFromOriginalStorage() throws Exception {
        byte[] markdown = "# 기존 문서\n".getBytes(StandardCharsets.UTF_8);
        Document document = new Document(
                "doc_markdown",
                "ws_1",
                "user_1",
                "기존.md",
                "text/markdown",
                markdown.length,
                "sources/documents/doc_markdown/original",
                "legacy-hash"
        );
        when(editStateRepository.existsById(document.getId())).thenReturn(false);
        when(storageProperties.getBucket()).thenReturn("documents");
        when(minioClient.getObject(any())).thenReturn(new GetObjectResponse(
                Headers.of(),
                "documents",
                "us-east-1",
                document.getSourceUri(),
                new ByteArrayInputStream(markdown)
        ));

        new DocumentEditStateInitializer(editStateRepository, minioClient, storageProperties)
                .initializeIfNeeded(document);

        verify(editStateRepository).insertIfAbsent(
                eq(document.getId()),
                eq("# 기존 문서\n"),
                eq("3350cfe0c286a74ff91ff31b8e2bd6488c52b1076327376429c56c0e39319c61"),
                eq(1L),
                any(),
                any()
        );
    }

    @Test
    void originalDocument_doesNotCreateEditState() throws Exception {
        Document document = new Document(
                "doc_pdf",
                "ws_1",
                "user_1",
                "원본.pdf",
                "application/pdf",
                100,
                "sources/documents/doc_pdf/original",
                "legacy-hash"
        );

        new DocumentEditStateInitializer(editStateRepository, minioClient, storageProperties)
                .initializeIfNeeded(document);

        verify(editStateRepository, never()).insertIfAbsent(
                anyString(),
                anyString(),
                anyString(),
                eq(1L),
                any(),
                any()
        );
        verify(minioClient, never()).getObject(any());
    }

    @Test
    void editableDocumentWithBlankSourceUri_doesNotReadOriginalStorage() throws Exception {
        Document document = new Document(
                "doc_blank_source",
                "ws_1",
                "user_1",
                "빈 경로.md",
                "text/markdown",
                100,
                "   ",
                "legacy-hash"
        );

        new DocumentEditStateInitializer(editStateRepository, minioClient, storageProperties)
                .initializeIfNeeded(document);

        verify(minioClient, never()).getObject(any());
        verify(editStateRepository, never()).existsById(anyString());
        verify(editStateRepository, never()).insertIfAbsent(
                anyString(), anyString(), anyString(), eq(1L), any(), any());
    }
}
