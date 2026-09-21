package fruition.shared.util;

import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.credentials.AwsEnvironmentProvider;
import io.minio.credentials.ChainedProvider;
import io.minio.credentials.IamAwsProvider;
import io.minio.credentials.Provider;
import java.security.ProviderException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Bean
    public MinioClient minioClient(StorageProperties props) {
        validate(props);
        var builder = MinioClient.builder().endpoint(props.getEndpoint());
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
        var builder = MinioAsyncClient.builder().endpoint(props.getEndpoint());
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
