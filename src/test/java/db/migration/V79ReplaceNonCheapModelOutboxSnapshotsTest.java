package db.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** 저가 모델 정책: 미발행 outbox command가 뺀 모델·채팅 전용 claude로 실행되지 않도록 V79가 snapshot을 옮긴다. */
class V79ReplaceNonCheapModelOutboxSnapshotsTest {

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
    void replacesRemovedAndWorkspaceClaudeModelsButKeepsChatHaiku() throws SQLException {
        migrate("78");
        insert("ingest_sol", "document", "openai", "gpt-6-sol");
        insert("ingest_luna", "document", "openai", "gpt-6-luna");
        insert("lint_flash", "lint", "gemini", "gemini-3.8-flash");
        insert("lint_lite", "lint", "gemini", "gemini-3.5-flash-lite");
        insert("ingest_sonnet", "document", "claude", "claude-sonnet-5");
        insert("meeting_haiku", "meeting_notes", "claude", "claude-haiku-4-5-20251001");
        insert("query_opus", "query", "claude", "claude-opus-5-5");
        insert("query_haiku", "query", "claude", "claude-haiku-4-5-20251001");
        insert("agent_haiku", "agent", "claude", "claude-haiku-4-5-20251001");

        migrate(null);

        assertModel("ingest_sol", "openai", "gpt-6-luna");
        assertModel("ingest_luna", "openai", "gpt-6-luna");
        assertModel("lint_flash", "gemini", "gemini-3.5-flash-lite");
        assertModel("lint_lite", "gemini", "gemini-3.5-flash-lite");
        assertModel("ingest_sonnet", "gemini", "gemini-3.5-flash-lite");
        assertModel("meeting_haiku", "gemini", "gemini-3.5-flash-lite");
        assertModel("query_opus", "gemini", "gemini-3.5-flash-lite");
        assertModel("query_haiku", "claude", "claude-haiku-4-5-20251001");
        assertModel("agent_haiku", "claude", "claude-haiku-4-5-20251001");
    }

    private static void migrate(String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        configuration.load().migrate();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void insert(String runId, String kind, String provider, String model) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO ai_command_outbox(id, run_id, topic, message_key, payload, created_at)
                     VALUES (?, ?, 'ai.commands', ?, ?, now())
                     """)) {
            statement.setString(1, "outbox_" + runId);
            statement.setString(2, runId);
            statement.setString(3, runId);
            statement.setString(4, "{\"run_id\":\"%s\",\"kind\":\"%s\",\"provider\":\"%s\",\"model\":\"%s\"}"
                    .formatted(runId, kind, provider, model));
            statement.executeUpdate();
        }
    }

    private static void assertModel(String runId, String provider, String model) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT payload::jsonb ->> 'provider', payload::jsonb ->> 'model', payload::jsonb ->> 'run_id' "
                             + "FROM ai_command_outbox WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).as(runId + " provider").isEqualTo(provider);
                assertThat(row.getString(2)).as(runId + " model").isEqualTo(model);
                assertThat(row.getString(3)).as(runId + " 다른 필드 유지").isEqualTo(runId);
            }
        }
    }
}
