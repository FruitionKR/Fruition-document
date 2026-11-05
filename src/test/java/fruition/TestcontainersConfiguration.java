package fruition;

import fruition.shared.util.StorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

// 컨테이너를 Spring @Bean으로 두면 테스트 컨텍스트마다(캐시 최대 32개) 3개씩 새로 떠서 로컬 Docker VM 메모리를 다 쓴다.
// 그래서 JVM 전체에서 한 벌만 static으로 띄우고, 컨텍스트에는 접속 정보만 넘긴다. 종료는 Ryuk가 JVM 종료 때 맡는다.
// 컨테이너를 Bean으로 노출하지 않는 이유: 컨텍스트 캐시에서 밀려난 컨텍스트가 닫힐 때 Spring이 공유 컨테이너를 멈추기 때문이다.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final String TEST_BUCKET = "fruition-test";

	// MinIO는 공식 커뮤니티 이미지(minio/minio, quay.io/minio/minio) 배포를 중단해 기존 태그를 받을 수 없다.
	// 원본을 소스에서 빌드한 Chainguard 이미지를 digest로 고정해 CI가 매번 같은 이미지를 받게 한다.
	public static final String MINIO_IMAGE =
		"cgr.dev/chainguard/minio@sha256:6a1d0b45c8669726bba580ced0bfa4cb9fdeed1ed636dfabd81d1577beb6937b";

	// 캐시된 컨텍스트(최대 32개)가 한 DB를 나눠 쓰므로 기본 연결 수(100)를 넘지 않게 늘린다.
	// 컨텍스트마다 쉬는 연결은 application-test.properties의 Hikari 설정으로 비운다.
	public static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>(DockerImageName.parse("postgres:latest"))
			.withCommand("postgres", "-c", "max_connections=300");

	// query run 상태·authz projection 조회가 Redis를 쓰므로 통합 테스트에도 Redis 컨테이너를 띄운다.
	static final GenericContainer<?> REDIS =
		new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

	// 문서 생성 시 원본 Markdown을 object storage에 넣으므로 통합 테스트에도 MinIO 컨테이너를 띄운다.
	static final MinIOContainer MINIO =
		new MinIOContainer(DockerImageName.parse(MINIO_IMAGE).asCompatibleSubstituteFor("minio/minio"));

	static {
		Startables.deepStart(POSTGRES, REDIS, MINIO).join();
	}

	/**
	 * 캐시된 컨텍스트의 스케줄러는 다른 테스트가 도는 동안에도 공유 DB의 행을 선점한다. 스케줄러가 쓰는 행의 진행을 단정하는 테스트는
	 * {@code test.isolated-database}로 자기 DB를 받는다. 이름이 곧 컨텍스트 캐시 키의 일부라 다른 테스트와 컨텍스트도 나누지 않는다.
	 */
	@Bean
	JdbcConnectionDetails postgresConnectionDetails(@Value("${test.isolated-database:}") String isolatedDatabase) {
		String jdbcUrl = isolatedDatabase.isBlank() ? POSTGRES.getJdbcUrl() : createDatabase(isolatedDatabase);
		return new JdbcConnectionDetails() {
			@Override
			public String getUsername() {
				return POSTGRES.getUsername();
			}

			@Override
			public String getPassword() {
				return POSTGRES.getPassword();
			}

			@Override
			public String getJdbcUrl() {
				return jdbcUrl;
			}
		};
	}

	private static String createDatabase(String name) {
		if (!name.matches("[a-z][a-z0-9_]*")) {
			throw new IllegalArgumentException("test.isolated-database: " + name);
		}
		try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				Statement statement = connection.createStatement()) {
			try (ResultSet exists = statement.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + name + "'")) {
				if (!exists.next()) {
					statement.execute("CREATE DATABASE " + name);
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("격리 DB를 만들지 못했습니다: " + name, e);
		}
		return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + name;
	}

	@Bean
	RedisConnectionDetails redisConnectionDetails() {
		return new RedisConnectionDetails() {
			@Override
			public Standalone getStandalone() {
				return Standalone.of(REDIS.getHost(), REDIS.getMappedPort(6379));
			}
		};
	}

	// MinIO는 @ServiceConnection 지원이 없어 app.storage.* 를 직접 덮어쓴다.
	@Bean
	DynamicPropertyRegistrar storagePropertiesRegistrar() {
		return registry -> {
			registry.add("app.storage.endpoint", MINIO::getS3URL);
			registry.add("app.storage.access-key", MINIO::getUserName);
			registry.add("app.storage.secret-key", MINIO::getPassword);
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
