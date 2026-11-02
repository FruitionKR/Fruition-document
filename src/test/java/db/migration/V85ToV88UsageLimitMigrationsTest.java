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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용 한도 migration(V85~V88)이 빈 DB에 끝까지 적용되고, CONCURRENTLY 인덱스가 모두 VALID이며,
 * 다시 migrate해도 아무것도 실행하지 않는지 확인한다. CONCURRENTLY가 Flyway 잠금 트랜잭션을 기다리며
 * 멈추지 않는지도 시간 제한으로 함께 본다.
 */
class V85ToV88UsageLimitMigrationsTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:latest"));

    private static final List<String> NEW_INDEXES = List.of(
            "idx_ai_task_runs_user_active",
            "idx_usage_charges_run",
            "idx_user_upload_events_user_created",
            "idx_documents_user_bytes",
            "idx_document_assets_uploader_bytes",
            "idx_meetings_creator_recording_bytes");

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
    void appliesContiguouslyWithValidIndexesAndRerunIsNoOp() throws SQLException {
        MigrateResult first = flyway().migrate();

        assertThat(first.targetSchemaVersion).isEqualTo("88");
        assertThat(first.migrations.stream().map(m -> m.version).toList())
                .contains("84", "85", "86", "87", "88");
        assertThat(validIndexes()).containsAll(NEW_INDEXES);
        assertThat(invalidIndexes()).isEmpty();
        assertThat(columnExists("documents", "error_code")).isTrue();
        assertThat(currentLockTimeout()).isEqualTo("0");

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

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static List<String> validIndexes() throws SQLException {
        return indexes(true);
    }

    private static List<String> invalidIndexes() throws SQLException {
        return indexes(false);
    }

    private static List<String> indexes(boolean valid) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT c.relname, i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                     """)) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                if (rs.getBoolean(2) == valid) {
                    names.add(rs.getString(1));
                }
            }
            return names;
        }
    }

    private static boolean columnExists(String table, String column) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = '"
                     + table + "' AND column_name = '" + column + "'")) {
            return rs.next();
        }
    }

    private static String currentLockTimeout() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW lock_timeout")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
