package fruition.shared.util;

import com.sun.net.httpserver.HttpServer;
import io.minio.credentials.IamAwsProvider;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MultipartStorageTest {
    private static final String[] ENV_KEYS = {"AWS_ACCESS_KEY_ID", "AWS_ACCESS_KEY", "AWS_SECRET_ACCESS_KEY",
            "AWS_SECRET_KEY", "AWS_SESSION_TOKEN", "AWS_WEB_IDENTITY_TOKEN_FILE", "AWS_ROLE_ARN"};

    @Test
    void awsModeStartsMultipartUploadWithWebIdentityWhenEnvironmentKeysAreAbsent() throws Exception {
        for (var key : new String[]{"AWS_ACCESS_KEY_ID", "AWS_ACCESS_KEY", "AWS_SECRET_ACCESS_KEY", "AWS_SECRET_KEY"}) {
            Assumptions.assumeTrue(System.getenv(key) == null);
        }
        var previous = new HashMap<String, String>();
        for (var key : ENV_KEYS) {
            previous.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
        var tokenFile = Files.createTempFile("irsa-multipart-", ".token");
        var authorization = new AtomicReference<String>();
        var sessionToken = new AtomicReference<String>();
        var requestPath = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String body;
            if (query != null && query.contains("Action=AssumeRoleWithWebIdentity")) {
                body = "<AssumeRoleWithWebIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">"
                        + "<AssumeRoleWithWebIdentityResult><Credentials>"
                        + "<AccessKeyId>irsa-access</AccessKeyId><SecretAccessKey>irsa-secret</SecretAccessKey>"
                        + "<SessionToken>irsa-session</SessionToken>"
                        + "<Expiration>" + Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS) + "</Expiration>"
                        + "</Credentials></AssumeRoleWithWebIdentityResult></AssumeRoleWithWebIdentityResponse>";
            } else {
                requestPath.set(exchange.getRequestURI().toString());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                sessionToken.set(exchange.getRequestHeaders().getFirst("X-Amz-Security-Token"));
                body = "<InitiateMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                        + "<Bucket>test-bucket</Bucket><Key>sources/documents/a/original</Key>"
                        + "<UploadId>upload-1</UploadId></InitiateMultipartUploadResult>";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            Files.writeString(tokenFile, "fixture-web-token");
            System.setProperty("AWS_WEB_IDENTITY_TOKEN_FILE", tokenFile.toString());
            System.setProperty("AWS_ROLE_ARN", "arn:aws:iam::123456789012:role/test-document");
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            var props = new StorageProperties();
            props.setEndpoint(endpoint); props.setBucket("test-bucket");
            props.setCredentialsMode("aws"); props.setRegion("ap-northeast-2");
            var multipart = new MultipartStorage(props, new IamAwsProvider(endpoint, null));
            assertEquals("upload-1", multipart.start("sources/documents/a/original"));
            assertTrue(requestPath.get().contains("uploads"));
            assertTrue(authorization.get().contains("irsa-access"));
            assertTrue(authorization.get().contains("ap-northeast-2"));
            assertEquals("irsa-session", sessionToken.get());
        } finally {
            server.stop(0);
            Files.deleteIfExists(tokenFile);
            previous.forEach((key, value) -> {
                if (value == null) System.clearProperty(key); else System.setProperty(key, value);
            });
        }
    }

    @Test
    void awsModeRequiresRegionAndRejectsUnknownMode() {
        var props = new StorageProperties();
        props.setEndpoint("http://127.0.0.1:1"); props.setCredentialsMode("aws");
        assertThrows(IllegalArgumentException.class, () -> new MultipartStorage(props));
        props.setCredentialsMode("typo"); props.setRegion("ap-northeast-2");
        assertThrows(IllegalArgumentException.class, () -> new MultipartStorage(props));
    }
}
