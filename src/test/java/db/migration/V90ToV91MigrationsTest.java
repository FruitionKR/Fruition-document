package db.migration;

import fruition.core.config.FlywayDefaults;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가져오기 세션 migration(V90 테이블·컬럼, V91 CONCURRENTLY 인덱스)이 빈 DB에 끝까지 적용되고 인덱스가 VALID이며,
 * 세션 lock_timeout을 되돌리고, 다시 migrate해도 아무것도 실행하지 않는지 확인한다.
 */
class V90ToV91MigrationsTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:latest"));

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void appliesOnFreshDatabaseWithValidIndexAndRerunIsNoOp() throws SQLException {
        MigrateResult first = flyway().migrate();

        assertThat(first.migrations.stream().map(m -> m.version).toList()).contains("90", "91");
        assertThat(scalar("SELECT to_regclass('document_imports') IS NOT NULL")).isEqualTo("t");
        assertThat(scalar("SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'documents' AND column_name = 'import_id'")).isEqualTo("uuid");
        assertThat(scalar("SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                + "WHERE c.relname = 'idx_documents_import'")).isEqualTo("t");
        assertThat(scalar("SHOW lock_timeout")).isEqualTo("0");

        MigrateResult second = flyway().migrate();

        assertThat(second.migrationsExecuted).isZero();
        assertThat(flyway().validateWithResult().validationSuccessful).isTrue();
    }

    private static Flyway flyway() {
        return FlywayDefaults.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
    }

    private static String scalar(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
