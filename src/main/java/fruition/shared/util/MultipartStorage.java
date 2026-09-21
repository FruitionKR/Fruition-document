package fruition.shared.util;

import com.google.common.collect.HashMultimap;
import io.minio.*;
import io.minio.credentials.*;
import io.minio.messages.Part;
import org.springframework.stereotype.Component;
import java.util.*;

/** MinIO SDK의 S3 multipart 제어 API를 제한된 작업으로 노출한다. */
@Component
public class MultipartStorage extends MinioAsyncClient {
    private final StorageProperties properties;
    public MultipartStorage(StorageProperties properties) {
        super(client(properties));
        this.properties = properties;
    }
    private static MinioAsyncClient client(StorageProperties p) {
        var builder = MinioAsyncClient.builder().endpoint(p.getEndpoint());
        if ("aws".equals(p.getCredentialsMode())) {
            builder.region(p.getRegion()).credentialsProvider(new ChainedProvider(
                    new AwsEnvironmentProvider(), new IamAwsProvider(null, null)));
        } else {
            builder.credentials(p.getAccessKey(), p.getSecretKey());
        }
        return builder.build();
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
        completeMultipartUploadAsync(properties.getBucket(), properties.getRegion(), key, uploadId,
                parts.toArray(Part[]::new), null, null).get();
    }
    public void abort(String key, String uploadId) throws Exception {
        abortMultipartUploadAsync(properties.getBucket(), properties.getRegion(), key, uploadId, null, null).get();
    }
}
