package fruition.shared.util;

import com.google.common.collect.HashMultimap;
import io.minio.*;
import io.minio.credentials.*;
import io.minio.messages.Part;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;

/** MinIO SDK의 S3 multipart 제어 API를 제한된 작업으로 노출한다. */
@Component
public class MultipartStorage extends MinioAsyncClient {
    private final StorageProperties properties;
    @Autowired
    public MultipartStorage(StorageProperties properties) {
        this(properties, new IamAwsProvider(null, null));
    }
    MultipartStorage(StorageProperties properties, Provider iamProvider) {
        super(MinioConfig.asyncClient(properties, iamProvider));
        this.properties = properties;
    }
    public String start(String key) throws Exception {
        var headers = HashMultimap.<String, String>create();
        headers.put("Content-Type", "application/pdf");
        return createMultipartUploadAsync(properties.getBucket(), properties.getRegion(), key, headers, null)
                .get().result().uploadId();
    }
    public List<Part> parts(String key, String uploadId) throws Exception {
        List<Part> parts = new ArrayList<>();
        int marker = 0;
        while (true) {
            var result = listPartsAsync(properties.getBucket(), properties.getRegion(), key, 1000,
                    marker, uploadId, null, null).get().result();
            parts.addAll(result.partList());
            if (!result.isTruncated()) return parts;
            int next = result.nextPartNumberMarker();
            if (next <= marker || parts.size() > 10000) throw new IllegalStateException("Invalid part listing");
            marker = next;
        }
    }
    public void finish(String key, String uploadId, List<Part> parts) throws Exception {
        // ListParts가 채운 Size/LastModified는 CompleteMultipartUpload 스키마에 없어 AWS S3가 MalformedXML로 거부한다.
        Part[] completion = parts.stream().map(part -> new Part(part.partNumber(), part.etag())).toArray(Part[]::new);
        completeMultipartUploadAsync(properties.getBucket(), properties.getRegion(), key, uploadId,
                completion, null, null).get();
    }
    public void abort(String key, String uploadId) throws Exception {
        abortMultipartUploadAsync(properties.getBucket(), properties.getRegion(), key, uploadId, null, null).get();
    }
}
