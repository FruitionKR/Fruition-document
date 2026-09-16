package fruition.core.document.service;

import java.io.InputStream;

public interface DocumentAssetObjectStorage {
    void put(String objectKey, String contentType, byte[] bytes);
    InputStream get(String objectKey);
    void delete(String objectKey);
}
