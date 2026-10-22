package fruition.core.document.service;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.exception.DocumentUploadException;
import fruition.core.document.exception.InvalidMarkdownContentException;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.shared.util.StorageProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;

@Component
public class DocumentEditStateInitializer {

    private final DocumentEditStateRepository editStateRepository;
    private final MinioClient minioClient;
    private final StorageProperties storageProperties;

    public DocumentEditStateInitializer(
            DocumentEditStateRepository editStateRepository,
            MinioClient minioClient,
            StorageProperties storageProperties
    ) {
        this.editStateRepository = editStateRepository;
        this.minioClient = minioClient;
        this.storageProperties = storageProperties;
    }

    @Transactional
    public void initializeIfNeeded(Document document) {
        if (document.getDocumentRole() != DocumentRole.EDITABLE
                || document.getSourceUri() == null
                || document.getSourceUri().isBlank()
                || editStateRepository.existsById(document.getId())) {
            return;
        }

        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(storageProperties.getBucket())
                        .object(document.getSourceUri())
                        .build()
        )) {
            byte[] bytes = inputStream.readAllBytes();
            DocumentEditingRules.MarkdownContent content = DocumentEditingRules.storedMarkdown(bytes);
            Instant now = Instant.now();
            editStateRepository.insertIfAbsent(
                    document.getId(), content.markdown(), content.contentHash(), 1, now, now);
        } catch (InvalidMarkdownContentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DocumentUploadException("기존 Markdown 편집 상태를 생성하지 못했습니다.", exception);
        }
    }
}
