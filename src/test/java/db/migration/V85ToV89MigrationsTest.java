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
 * 사용 한도·조회 인덱스 migration(V85~V89)이 빈 DB에 끝까지 적용되고, CONCURRENTLY 인덱스가 모두 VALID이며,
 * 다시 migrate해도 아무것도 실행하지 않는지 확인한다. CONCURRENTLY가 Flyway 잠금 트랜잭션을 기다리며
 * 멈추지 않는지도 시간 제한으로 함께 본다. 새 조회 인덱스는 실제 조회 모양으로 planner가 고르는지 EXPLAIN으로 본다.
 */
class V85ToV89MigrationsTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:latest"));

    private static final List<String> NEW_INDEXES = List.of(
            "idx_ai_task_runs_user_active",
            "idx_usage_charges_run",
            "idx_user_upload_events_user_created",
            "idx_documents_user_bytes",
            "idx_document_assets_uploader_bytes",
            "idx_meetings_creator_recording_bytes",
            "idx_chat_messages_session",
            "idx_cmr_message",
            "idx_cmrp_message",
            "idx_chat_sessions_actor",
            "idx_ai_operation_logs_active");

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

        assertThat(first.targetSchemaVersion).isEqualTo("89");
        assertThat(first.migrations.stream().map(m -> m.version).toList())
                .contains("84", "85", "86", "87", "88", "89");
        assertThat(validIndexes()).containsAll(NEW_INDEXES);
        assertThat(invalidIndexes()).isEmpty();
        assertThat(columnExists("documents", "error_code")).isTrue();
        assertThat(currentLockTimeout()).isEqualTo("0");

        MigrateResult second = flyway().migrate();

        assertThat(second.migrationsExecuted).isZero();
        assertThat(flyway().validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void plannerUsesNewIndexesForChatAndOperationPollingQueries() throws SQLException {
        // 다른 테스트의 "빈 DB에서 처음 migrate" 전제를 깨지 않게 별도 DB를 쓴다.
        try (Connection admin = connection(); Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE planner_check");
        }
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/planner_check");
        FlywayDefaults.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO chat_sessions (id, workspace_id, user_id, created_at, last_message_at)
                    SELECT 's' || g, 'ws' || (g % 50), 'u' || (g % 7), now(), now() - g * interval '1 minute'
                    FROM generate_series(1, 5000) g
                    """);
            statement.execute("""
                    INSERT INTO chat_messages (id, session_id, content, created_at, pair_id, role, status)
                    SELECT 'm' || g, 's' || (g % 5000 + 1), 'x', now() - g * interval '1 second', 'p' || (g / 2),
                           CASE WHEN g % 2 = 0 THEN 'user' ELSE 'assistant' END, 'completed'
                    FROM generate_series(1, 50000) g
                    """);
            statement.execute("""
                    INSERT INTO ai_operation_logs (operation_id, workspace_id, user_id, operation_type, status, created_at)
                    SELECT 'op' || g, 'ws1', 'u1', 'ingest',
                           CASE WHEN g % 1000 = 0 THEN 'processing' ELSE 'succeeded' END, now() - g * interval '1 second'
                    FROM generate_series(1, 50000) g
                    """);
            statement.execute("ANALYZE chat_sessions, chat_messages, ai_operation_logs");

            assertThat(plan(statement, """
                    SELECT * FROM chat_messages WHERE session_id = 's42' ORDER BY created_at DESC LIMIT 40
                    """)).contains("idx_chat_messages_session");
            assertThat(plan(statement, """
                    SELECT * FROM chat_sessions WHERE workspace_id = 'ws3' AND user_id = 'u3'
                    ORDER BY last_message_at DESC
                    """)).contains("idx_chat_sessions_actor");
            assertThat(plan(statement, """
                    SELECT * FROM chat_message_references WHERE chat_message_id = 'm1'
                    """)).contains("idx_cmr_message");
            assertThat(plan(statement, """
                    SELECT * FROM chat_message_related_pages WHERE chat_message_id = 'm1'
                    """)).contains("idx_cmrp_message");
            // OperationLogRepository.findPage에 진행 중 상태만 넘긴 polling 조회와 같은 모양.
            assertThat(plan(statement, """
                    SELECT * FROM ai_operation_logs l
                    WHERE l.workspace_id = 'ws1'
                      AND l.operation_type IN ('ingest', 'lint')
                      AND l.status IN ('processing', 'applying')
                      AND (l.status <> 'succeeded' OR l.changed_resource_count > 0)
                      AND (l.operation_type <> 'document_edit' OR l.status = 'succeeded')
                      AND (l.created_at < '9999-12-31T23:59:59Z'
                           OR (l.created_at = '9999-12-31T23:59:59Z' AND l.operation_id < ''))
                    ORDER BY l.created_at DESC, l.operation_id DESC
                    LIMIT 21
                    """)).contains("idx_ai_operation_logs_active");
        }
    }

    private static String plan(Statement statement, String sql) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (ResultSet rs = statement.executeQuery("EXPLAIN " + sql)) {
            while (rs.next()) {
                plan.append(rs.getString(1)).append('\n');
            }
        }
        return plan.toString();
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
