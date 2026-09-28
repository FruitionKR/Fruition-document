package fruition.shared.util;

import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.credentials.AwsEnvironmentProvider;
import io.minio.credentials.ChainedProvider;
import io.minio.credentials.IamAwsProvider;
import io.minio.credentials.Provider;
import java.security.ProviderException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    /**
     * 풀에 둔 유휴 연결의 최대 보관 시간. OkHttp 기본 5분은 S3·NAT가 먼저 끊은 연결을 다시 꺼내
     * 응답 전에 EOF(unexpected end of stream)를 내는 원인이 됐다. 짧게 잡아 오래 쉰 연결은 버린다.
     */
    static final long IDLE_CONNECTION_SECONDS = 30;
    private static final int MAX_IDLE_CONNECTIONS = 5;

    /** 동기·비동기 MinIO 클라이언트가 함께 쓰는 HTTP 클라이언트. */
    static OkHttpClient httpClient() {
        return new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(MAX_IDLE_CONNECTIONS, IDLE_CONNECTION_SECONDS, TimeUnit.SECONDS))
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofMinutes(5))
                .writeTimeout(Duration.ofMinutes(5))
                .build();
    }

    @Bean
    public MinioClient minioClient(StorageProperties props) {
        validate(props);
        var builder = MinioClient.builder().endpoint(props.getEndpoint()).httpClient(httpClient());
        if ("aws".equals(props.getCredentialsMode())) {
            builder.region(props.getRegion()).credentialsProvider(
                    awsCredentialsProvider(new IamAwsProvider(null, null)));
        } else {
            builder.credentials(props.getAccessKey(), props.getSecretKey());
        }
        return builder.build();
    }

    /** multipart 제어용 비동기 클라이언트. {@link #minioClient}와 같은 mode·region 검증과 자격 증명 체인을 쓴다. */
    static MinioAsyncClient asyncClient(StorageProperties props, Provider iamProvider) {
        validate(props);
        var builder = MinioAsyncClient.builder().endpoint(props.getEndpoint()).httpClient(httpClient());
        if ("aws".equals(props.getCredentialsMode())) {
            builder.region(props.getRegion()).credentialsProvider(awsCredentialsProvider(iamProvider));
        } else {
            builder.credentials(props.getAccessKey(), props.getSecretKey());
        }
        return builder.build();
    }

    private static void validate(StorageProperties props) {
        if ("aws".equals(props.getCredentialsMode())) {
            if (props.getRegion() == null || props.getRegion().isBlank()) {
                throw new IllegalArgumentException("AWS S3 region이 필요합니다.");
            }
        } else if (!"local".equals(props.getCredentialsMode())) {
            throw new IllegalArgumentException("지원하지 않는 S3 credentials mode입니다.");
        }
    }

    static Provider awsCredentialsProvider(Provider iamProvider) {
        var environment = new AwsEnvironmentProvider();
        return new ChainedProvider(() -> {
            try {
                return environment.fetch();
            } catch (NullPointerException | IllegalArgumentException unavailable) {
                // MinIO 8.5.7 throws these for absent/empty environment keys, but
                // ChainedProvider only advances on ProviderException. EKS supplies
                // a web identity token, so it must reach IamAwsProvider.
                throw new ProviderException("AWS environment credentials are unavailable", unavailable);
            }
        }, iamProvider);
    }
}
