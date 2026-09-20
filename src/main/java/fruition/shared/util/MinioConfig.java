package fruition.shared.util;

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
        var builder = MinioClient.builder().endpoint(props.getEndpoint());
        if ("aws".equals(props.getCredentialsMode())) {
            if (props.getRegion() == null || props.getRegion().isBlank()) {
                throw new IllegalArgumentException("AWS S3 region이 필요합니다.");
            }
            builder.region(props.getRegion()).credentialsProvider(
                    awsCredentialsProvider(new IamAwsProvider(null, null)));
        } else if ("local".equals(props.getCredentialsMode())) {
            builder.credentials(props.getAccessKey(), props.getSecretKey());
        } else {
            throw new IllegalArgumentException("지원하지 않는 S3 credentials mode입니다.");
        }
        return builder.build();
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
