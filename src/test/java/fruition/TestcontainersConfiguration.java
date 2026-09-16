package fruition;

import fruition.shared.util.StorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final String TEST_BUCKET = "fruition-test";

	@Bean
	@ServiceConnection
	PostgreSQLContainer<?> postgresContainer() {
		return new PostgreSQLContainer<>(DockerImageName.parse("postgres:latest"));
	}

	// query run 상태·authz projection 조회가 Redis를 쓰므로 통합 테스트에도 Redis 컨테이너를 띄운다.
	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
	}

	// 문서 생성 시 원본 Markdown을 object storage에 넣으므로 통합 테스트에도 MinIO 컨테이너를 띄운다.
	// MinIO는 @ServiceConnection 지원이 없어 app.storage.* 를 직접 덮어쓴다.
	@Bean
	MinIOContainer minioContainer() {
		return new MinIOContainer(DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
			.asCompatibleSubstituteFor("minio/minio"));
	}

	@Bean
	DynamicPropertyRegistrar storagePropertiesRegistrar(MinIOContainer minioContainer) {
		return registry -> {
			registry.add("app.storage.endpoint", minioContainer::getS3URL);
			registry.add("app.storage.access-key", minioContainer::getUserName);
			registry.add("app.storage.secret-key", minioContainer::getPassword);
			registry.add("app.storage.bucket", () -> TEST_BUCKET);
		};
	}

	// dev compose의 `mc mb --ignore-existing`과 같은 역할. 버킷이 없으면 putObject가 실패한다.
	@Bean
	InitializingBean testBucketInitializer(MinioClient minioClient, StorageProperties storageProperties) {
		return () -> {
			String bucket = storageProperties.getBucket();
			boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
			if (!exists) {
				minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
			}
		};
	}

}
