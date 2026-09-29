package fruition.shared.util;

import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetryingObjectPutTest {

    private static final byte[] BODY = "# 문서".getBytes(StandardCharsets.UTF_8);

    /** localhost는 ::1과 127.0.0.1 두 경로라 끊긴 경로를 OkHttp가 뒤로 미룬다. 경로 하나로 고정한다. */
    private static void start(MockWebServer server) throws Exception {
        server.start(InetAddress.getByName("127.0.0.1"), 0);
    }

    /** region을 고정해 PUT 앞에 GetBucketLocation 조회가 나가지 않게 한다. */
    private static MinioClient client(MockWebServer server) {
        return MinioClient.builder()
                .endpoint("http://127.0.0.1:" + server.getPort())
                .region("us-east-1")
                .credentials("access", "secret")
                .httpClient(MinioConfig.httpClient())
                .build();
    }

    @Test
    void retriesOnceWhenServerClosesConnectionBeforeResponse() throws Exception {
        try (var server = new MockWebServer()) {
            start(server);
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
            server.enqueue(new MockResponse().setResponseCode(200).addHeader("ETag", "\"etag\""));

            assertDoesNotThrow(() -> RetryingObjectPut.put(client(server), "bucket", "k", BODY, "text/markdown"));

            assertEquals(2, server.getRequestCount());
            server.takeRequest();
            var retried = server.takeRequest();
            assertEquals("PUT", retried.getMethod());
            assertEquals("/bucket/k", retried.getPath());
            assertArrayEquals(BODY, retried.getBody().readByteArray());
        }
    }

    @Test
    void failsWhenConnectionClosesTwice() throws Exception {
        try (var server = new MockWebServer()) {
            start(server);
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

            assertThrows(java.io.IOException.class,
                    () -> RetryingObjectPut.put(client(server), "bucket", "k", BODY, "text/markdown"));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    void doesNotRetryErrorResponses() throws Exception {
        try (var server = new MockWebServer()) {
            start(server);
            server.enqueue(new MockResponse().setResponseCode(403)
                    .addHeader("Content-Type", "application/xml")
                    .setBody("<Error><Code>AccessDenied</Code><Message>denied</Message></Error>"));
            server.enqueue(new MockResponse().setResponseCode(200));

            assertThrows(ErrorResponseException.class,
                    () -> RetryingObjectPut.put(client(server), "bucket", "k", BODY, "text/markdown"));
            assertEquals(1, server.getRequestCount());
        }
    }
}
