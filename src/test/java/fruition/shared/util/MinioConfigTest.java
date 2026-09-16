package fruition.shared.util;

import com.sun.net.httpserver.HttpServer;
import io.minio.GetObjectArgs;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MinioConfigTest {
    @Test
    void localAndAwsTemporaryCredentialsSignActualRequests() throws Exception {
        var authorization = new AtomicReference<String>();
        var token = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            token.set(exchange.getRequestHeaders().getFirst("X-Amz-Security-Token"));
            byte[] data = (exchange.getRequestURI().getQuery() != null
                    ? "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"></LocationConstraint>"
                    : "content").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        try {
            var props = new StorageProperties();
            props.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
            props.setAccessKey("local-access"); props.setSecretKey("local-secret");
            var config = new MinioConfig();
            try (var response = config.minioClient(props).getObject(GetObjectArgs.builder().bucket("test-bucket").object("sources/documents/a").build())) {
                assertEquals("content", new String(response.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertTrue(authorization.get().contains("local-access")); assertNull(token.get());
            System.setProperty("AWS_ACCESS_KEY_ID", "temporary-access");
            System.setProperty("AWS_SECRET_ACCESS_KEY", "temporary-secret");
            System.setProperty("AWS_SESSION_TOKEN", "temporary-session");
            props.setCredentialsMode("aws"); props.setRegion("ap-northeast-2");
            try (var response = config.minioClient(props).getObject(GetObjectArgs.builder().bucket("test-bucket").object("wiki/a").build())) {
                assertEquals("content", new String(response.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertTrue(authorization.get().contains("temporary-access"));
            assertTrue(authorization.get().contains("ap-northeast-2"));
            assertEquals("temporary-session", token.get());
            props.setRegion(null);
            assertThrows(IllegalArgumentException.class, () -> config.minioClient(props));
            props.setCredentialsMode("typo");
            assertThrows(IllegalArgumentException.class, () -> config.minioClient(props));
        } finally {
            server.stop(0);
            System.clearProperty("AWS_ACCESS_KEY_ID"); System.clearProperty("AWS_SECRET_ACCESS_KEY"); System.clearProperty("AWS_SESSION_TOKEN");
        }
    }
}
