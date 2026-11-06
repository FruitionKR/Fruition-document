package fruition.core.aitask.service;

import fruition.TestcontainersConfiguration;
import fruition.core.document.service.DocumentCopyScrubber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** scripts/sql/close-stuck-runs*.sql을 실제 Postgres에서 돌린다. 건수를 단정하므로 다른 컨텍스트와 DB를 나눈다. */
@SpringBootTest(properties = "test.isolated-database=close_stuck_runs_test")
@Import(TestcontainersConfiguration.class)
class CloseStuckRunsSqlIntegrationTest {

    static final Path PREVIEW = Path.of("scripts/sql/close-stuck-runs-preview.sql");
    static final Path APPLY = Path.of("scripts/sql/close-stuck-runs.sql");

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired DocumentCopyScrubber scrubber;
    @Autowired TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ai_task_changes");
        jdbc.update("DELETE FROM usage_collect_queue");
        jdbc.update("DELETE FROM agent_apply_projections");
        jdbc.update("DELETE FROM ai_command_outbox");
        jdbc.update("DELETE FROM ai_task_runs");
    }

    @Test
    void previewCountsByKindAndChangesNothing() throws Exception {
        String convert = run("convert", "running", 8);
        run("skill_author", "running", 8);
        run("skill_author", "running", 9);

        assertThat(preview()).containsExactly(Map.entry("convert", 1L), Map.entry("skill_author", 2L));

        assertThat(status(convert)).isEqualTo("running");
    }

    @Test
    void applyClosesOnlyStuckRunsAndQueuesTheirUsage() throws Exception {
        String convert = run("convert", "running", 8);
        String skill = run("skill_author", "running", 8);
        List<String> untouched = List.of(
                run("agent", "running", 30), run("query", "running", 30), run("document", "running", 30),
                run("convert", "running", 6), run("skill_author", "running", 6),
                run("convert", "completed", 30), run("convert", "cancel_requested", 30), run("skill_author", "rolling_back", 30));
        String claimed = run("convert", "running", 8);
        jdbc.update("UPDATE ai_task_runs SET resume_claimed_by = 'pod', resume_claimed_until = now() + interval '1 minute' WHERE id = ?", claimed);
        String withOutbox = run("skill_author", "running", 8);
        jdbc.update("INSERT INTO ai_command_outbox(id, run_id, topic, message_key, payload, created_at) VALUES (?, ?, 't', 'k', '{}', now())",
                UUID.randomUUID().toString(), withOutbox);
        String withProjection = run("convert", "running", 8);
        jdbc.update("INSERT INTO agent_apply_projections(run_id, workspace_id, user_id, status) VALUES (?, 'ws', 'user', 'queued')", withProjection);
        String document = insertDocument();
        String queued = "convert:" + document;
        insertRun(queued, "convert", "running", 8);
        jdbc.update("INSERT INTO document_convert_queue(document_id, source_document_id, status, created_at) VALUES (?, ?, 'processing', now())", document, document);
        Map<String, String> before = statuses();

        assertThat(preview()).containsExactly(Map.entry("convert", 1L), Map.entry("skill_author", 1L));
        apply(2);

        assertThat(jdbc.queryForMap("SELECT status, error_code FROM ai_task_runs WHERE id = ?", convert))
                .containsEntry("status", "failed").containsEntry("error_code", "stale_run_closed");
        assertThat(status(skill)).isEqualTo("failed");
        assertThat(jdbc.queryForList("SELECT run_id FROM usage_collect_queue", String.class)).containsExactlyInAnyOrder(convert, skill);
        for (String id : untouched) assertThat(status(id)).as(id).isEqualTo(before.get(id));
        for (String id : List.of(claimed, withOutbox, withProjection, queued)) assertThat(status(id)).as(id).isEqualTo("running");
    }

    @Test
    void countMismatchRollsBackEverything() throws Exception {
        String convert = run("convert", "running", 8);
        run("skill_author", "running", 8);

        assertThatThrownBy(() -> apply(1)).hasMessageContaining("expected 1 runs, found 2");
        assertThatThrownBy(() -> apply(3)).hasMessageContaining("expected 3 runs, found 2");

        assertThat(status(convert)).isEqualTo("running");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_collect_queue", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE status = 'failed'", Integer.class)).isZero();
    }

    @Test
    void rerunFindsNothingAndTheOldExpectationIsRefused() throws Exception {
        String convert = run("convert", "running", 8);
        apply(1);
        String closedAt = jdbc.queryForObject("SELECT updated_at::text FROM ai_task_runs WHERE id = ?", String.class, convert);

        assertThat(preview()).isEmpty();
        assertThatThrownBy(() -> apply(1)).hasMessageContaining("found 0");
        apply(0);

        assertThat(jdbc.queryForObject("SELECT updated_at::text FROM ai_task_runs WHERE id = ?", String.class, convert)).isEqualTo(closedAt);
    }

    @Test
    void afterClosingScrubOrphansDeletesChangesOfMissingDocumentsOnly() throws Exception {
        String missing = "doc_" + UUID.randomUUID();
        String live = insertDocument();
        String stuck = run("convert", "running", 8);
        change(stuck, "document_edit_states", "{\"document_id\":\"" + missing + "\"}");
        change(stuck, "documents", "{\"id\":\"" + missing + "\"}");
        change(stuck, "document_edit_states", "{\"document_id\":\"" + live + "\"}");
        String active = run("agent", "running", 30);
        change(active, "document_edit_states", "{\"document_id\":\"" + missing + "\"}");

        sweep();
        assertThat(changes(stuck)).isEqualTo(3);

        apply(1);
        sweep();

        assertThat(changes(stuck)).isOne();
        assertThat(changes(active)).isOne();
    }

    private void sweep() {
        transaction.executeWithoutResult(status -> scrubber.scrubOrphans(100));
    }

    private Map<String, Long> preview() throws Exception {
        String body = script(PREVIEW);
        int select = body.indexOf("SELECT");
        return jdbc.queryForList(body.substring(select, body.lastIndexOf("COMMIT;"))).stream().collect(Collectors.toMap(
                row -> (String) row.get("kind"), row -> ((Number) row.get("runs")).longValue(), (a, b) -> a, java.util.TreeMap::new));
    }

    private void apply(int expected) throws IOException, SQLException {
        String body = script(APPLY).replace(":'expected'", "'" + expected + "'");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            try {
                statement.execute(body);
            } catch (SQLException e) {
                statement.execute("ROLLBACK");
                throw e;
            }
        }
    }

    /** psql 전용 줄을 뺀다. 나머지는 psql과 JDBC가 같게 실행한다. */
    private static String script(Path path) throws IOException {
        return Files.readAllLines(path).stream().filter(line -> !line.startsWith("\\set")).collect(Collectors.joining("\n"));
    }

    private String run(String kind, String status, int daysAgo) {
        String id = kind + "-" + UUID.randomUUID();
        insertRun(id, kind, status, daysAgo);
        return id;
    }

    private void insertRun(String id, String kind, String status, int daysAgo) {
        jdbc.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind, status, updated_at) "
                + "VALUES (?, 'ws', 'user', ?, ?, now() - make_interval(days => ?))", id, kind, status, daysAgo);
    }

    private void change(String run, String table, String key) {
        jdbc.update("INSERT INTO ai_task_changes(run_id, table_name, row_key, before_value) VALUES (?, ?, CAST(? AS jsonb), '{\"markdown\":\"본문\"}')",
                run, table, key);
    }

    private int changes(String run) {
        return jdbc.queryForObject("SELECT count(*) FROM ai_task_changes WHERE run_id = ?", Integer.class, run);
    }

    private String status(String run) {
        return jdbc.queryForObject("SELECT status FROM ai_task_runs WHERE id = ?", String.class, run);
    }

    private Map<String, String> statuses() {
        return jdbc.queryForList("SELECT id, status FROM ai_task_runs").stream()
                .collect(Collectors.toMap(row -> (String) row.get("id"), row -> (String) row.get("status")));
    }

    private String insertDocument() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO documents(id, byte_size, content_hash, filename, display_name, normalized_filename, mime_type,
                    status, uploaded_at, updated_at, user_id, workspace_id, current_content_hash, current_version, document_role, sort_order)
                VALUES (?, 1, 'h', ?, 'a', ?, 'text/markdown', 'completed', now(), now(), 'user', 'ws', 'h', 1, 'EDITABLE', 0)
                """, id, id, id);
        return id;
    }
}
