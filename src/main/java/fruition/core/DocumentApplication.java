package fruition.core;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 문서·채팅·Wiki·query를 담당하는 document 앱. 공유 모듈(fruition.shared)을 함께 스캔한다.
 * JPA 배선(@EntityScan·@EnableJpaRepositories)은 slice 테스트에 끌려가지 않도록 {@link fruition.core.config.JpaConfig}에 둔다.
 */
@SpringBootApplication(scanBasePackages = {"fruition.core", "fruition.shared"})
@EnableScheduling
public class DocumentApplication {

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--migrate-only")) {
            Flyway.configure()
                    .dataSource("jdbc:postgresql://" + requiredEnv("POSTGRES_HOST") + ":"
                                    + requiredEnv("POSTGRES_PORT") + "/" + requiredEnv("CORE_DB_NAME")
                                    + "?sslmode=" + System.getenv().getOrDefault("PGSSLMODE", "prefer"),
                            requiredEnv("CORE_DB_MIGRATION_USER"), requiredEnv("CORE_DB_MIGRATION_PASSWORD"))
                    .locations("classpath:db/migration")
                    .load().migrate();
            return;
        }
        SpringApplication.run(DocumentApplication.class, args);
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("필수 migration 설정 누락: " + key);
        }
        return value;
    }

}
