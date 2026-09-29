package fruition.shared.util;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 메모리에 있는 bytes를 같은 key로 PUT한다. 풀에서 꺼낸 유휴 연결을 S3·NAT가 이미 끊어
 * 응답 전에 EOF가 나면 {@link IOException}이 올라온다. 같은 key·같은 내용이라 반복해도 결과가
 * 같으므로 그 경우에만 새 연결로 1회 재시도한다. 4xx/5xx 응답(ErrorResponseException)과
 * 인터럽트로 끊긴 요청(InterruptedIOException)은 재시도하지 않는다.
 */
public final class RetryingObjectPut {

    private static final Logger log = LoggerFactory.getLogger(RetryingObjectPut.class);

    private RetryingObjectPut() {}

    public static void put(MinioClient client, String bucket, String key, byte[] bytes, String contentType)
            throws Exception {
        try {
            putOnce(client, bucket, key, bytes, contentType);
        } catch (InterruptedIOException interrupted) {
            throw interrupted;  // 종료·취소로 끊긴 요청은 다시 보내지 않는다
        } catch (IOException first) {
            log.warn("[S3 PUT 연결 오류, 1회 재시도] key={} cause={}", key, first.toString());
            putOnce(client, bucket, key, bytes, contentType);
        }
    }

    private static void putOnce(MinioClient client, String bucket, String key, byte[] bytes, String contentType)
            throws Exception {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(in, bytes.length, -1)
                    .contentType(contentType)
                    .build());
        }
    }
}
