package fruition.shared.util;

import com.sun.net.httpserver.HttpServer;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.credentials.Credentials;
import io.minio.credentials.IamAwsProvider;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MinioConfigTest {
    @Test
    void missingEnvironmentKeysUseWebIdentityAndRefreshRotatedToken() throws Exception {
        var keys = new String[]{"AWS_ACCESS_KEY_ID", "AWS_ACCESS_KEY", "AWS_SECRET_ACCESS_KEY",
                "AWS_SECRET_KEY", "AWS_SESSION_TOKEN", "AWS_WEB_IDENTITY_TOKEN_FILE", "AWS_ROLE_ARN"};
        for (var key : new String[]{"AWS_ACCESS_KEY_ID", "AWS_ACCESS_KEY", "AWS_SECRET_ACCESS_KEY", "AWS_SECRET_KEY"}) {
            org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv(key) == null);
        }
        var previous = new HashMap<String, String>();
        for (var key : keys) {
            previous.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
        var tokenFile = Files.createTempFile("irsa-test-", ".token");
        var stsCalls = new AtomicInteger();
        var stsQuery = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var sessionToken = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String body;
            if (query != null && query.contains("Action=AssumeRoleWithWebIdentity")) {
                stsQuery.set(query);
                int attempt = stsCalls.incrementAndGet();
                body = "<AssumeRoleWithWebIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">"
                        + "<AssumeRoleWithWebIdentityResult><Credentials>"
                        + "<AccessKeyId>irsa-access-" + attempt + "</AccessKeyId>"
                        + "<SecretAccessKey>irsa-secret</SecretAccessKey>"
                        + "<SessionToken>irsa-session-" + attempt + "</SessionToken>"
                        + "<Expiration>" + Instant.now().plusSeconds(attempt == 1 ? 5 : 3600)
                                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS) + "</Expiration>"
                        + "</Credentials></AssumeRoleWithWebIdentityResult></AssumeRoleWithWebIdentityResponse>";
            } else {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                sessionToken.set(exchange.getRequestHeaders().getFirst("X-Amz-Security-Token"));
                body = "content";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            Files.writeString(tokenFile, "fixture-web-token-1");
            System.setProperty("AWS_WEB_IDENTITY_TOKEN_FILE", tokenFile.toString());
            System.setProperty("AWS_ROLE_ARN", "arn:aws:iam::123456789012:role/test-document");
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            var provider = MinioConfig.awsCredentialsProvider(new IamAwsProvider(endpoint, null));
            assertEquals("irsa-access-1", provider.fetch().accessKey());
            assertTrue(stsQuery.get().contains("WebIdentityToken=fixture-web-token-1"));
            assertTrue(stsQuery.get().contains("RoleArn=arn:aws:iam::123456789012:role/test-document"));
            Files.writeString(tokenFile, "fixture-web-token-2");
            var client = MinioClient.builder().endpoint(endpoint).region("ap-northeast-2")
                    .credentialsProvider(provider).build();
            try (var response = client.getObject(GetObjectArgs.builder().bucket("test-bucket").object("sources/a").build())) {
                assertEquals("content", new String(response.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertEquals(2, stsCalls.get());
            assertTrue(stsQuery.get().contains("WebIdentityToken=fixture-web-token-2"));
            assertTrue(authorization.get().contains("irsa-access-2"));
            assertEquals("irsa-session-2", sessionToken.get());
        } finally {
            server.stop(0);
            Files.deleteIfExists(tokenFile);
            previous.forEach((key, value) -> {
                if (value == null) System.clearProperty(key); else System.setProperty(key, value);
            });
        }
    }

    @Test
    void emptyEnvironmentKeysAlsoFallBackToIam() {
        String access = System.getProperty("AWS_ACCESS_KEY_ID");
        String secret = System.getProperty("AWS_SECRET_ACCESS_KEY");
        try {
            System.setProperty("AWS_ACCESS_KEY_ID", "");
            System.setProperty("AWS_SECRET_ACCESS_KEY", "");
            var expected = new Credentials("temporary-access", "temporary-secret", "temporary-session", null);
            assertSame(expected, MinioConfig.awsCredentialsProvider(() -> expected).fetch());
        } finally {
            if (access == null) System.clearProperty("AWS_ACCESS_KEY_ID"); else System.setProperty("AWS_ACCESS_KEY_ID", access);
            if (secret == null) System.clearProperty("AWS_SECRET_ACCESS_KEY"); else System.setProperty("AWS_SECRET_ACCESS_KEY", secret);
        }
    }

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
