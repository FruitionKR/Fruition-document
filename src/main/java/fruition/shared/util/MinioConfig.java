package fruition.shared.util;

import io.minio.MinioClient;
import io.minio.credentials.AwsEnvironmentProvider;
import io.minio.credentials.ChainedProvider;
import io.minio.credentials.IamAwsProvider;
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
            builder.region(props.getRegion()).credentialsProvider(new ChainedProvider(
                    new AwsEnvironmentProvider(), new IamAwsProvider(null, null)));
        } else if ("local".equals(props.getCredentialsMode())) {
            builder.credentials(props.getAccessKey(), props.getSecretKey());
        } else {
            throw new IllegalArgumentException("지원하지 않는 S3 credentials mode입니다.");
        }
        return builder.build();
    }
}
