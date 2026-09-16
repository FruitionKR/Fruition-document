package fruition.core.document.service;

import fruition.core.document.exception.DocumentAssetStorageException;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import io.minio.GetObjectArgs;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

@Component
public class MinioDocumentAssetObjectStorage implements DocumentAssetObjectStorage {

    private final MinioClient minioClient;
    private final StorageProperties storageProperties;

    public MinioDocumentAssetObjectStorage(MinioClient minioClient, StorageProperties storageProperties) {
        this.minioClient = minioClient;
        this.storageProperties = storageProperties;
    }

    @Override
    public InputStream get(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(storageProperties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw new DocumentAssetStorageException("이미지 asset을 조회하지 못했습니다.", exception);
        }
    }

    @Override
    public void put(String objectKey, String contentType, byte[] bytes) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(storageProperties.getBucket())
                    .object(objectKey)
                    .stream(input, bytes.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception exception) {
            throw new DocumentAssetStorageException("이미지 asset을 저장하지 못했습니다.", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(storageProperties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw new DocumentAssetStorageException("이미지 asset 보상 삭제에 실패했습니다.", exception);
        }
    }
}
