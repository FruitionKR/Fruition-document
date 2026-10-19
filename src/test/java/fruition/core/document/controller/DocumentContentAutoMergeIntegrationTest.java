package fruition.core.document.controller;

import fruition.TestcontainersConfiguration;
import fruition.core.agent.repository.AgentRunCommandRepository;
import fruition.core.document.dto.DocumentContentSaveResponse;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.service.DocumentService;
import fruition.shared.security.JwtTokenProvider;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 기준 버전이 지나간 본문 저장을 서버가 자동 병합하는 흐름(ADR 0032)을 실제 DB와 HTTP로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DocumentContentAutoMergeIntegrationTest {

    private static final String BASE = "# 제목\n\n첫 문단\n\n둘째 문단\n\n끝 문단";
    private static final String SERVER = "# 제목\n\n첫 문단(작성자)\n\n둘째 문단\n\n끝 문단";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired DocumentService documentService;
    @Autowired AgentRunCommandRepository agentRunCommandRepository;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired PostgreSQLContainer<?> postgresContainer;

    final String workspace = "ws_" + UUID.randomUUID();
    final String author = "user_author_" + UUID.randomUUID();
    final String member = "user_member_" + UUID.randomUUID();

    String documentId;

    @BeforeEach
    void setUp() {
        role(author, "MEMBER");
        role(member, "MEMBER");
        documentId = documentService.createMarkdown(workspace, author, UUID.randomUUID().toString(),
                new MarkdownDocumentCreateRequest("회의록", BASE, null)).id();
    }

    @Test
    void saveOnCurrentVersionReportsNoMergeAndOmitsMarkdown() throws Exception {
        save(member, BASE + "\n\n추가", 1, "write-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_version").value(2))
                .andExpect(jsonPath("$.merge.status").value("none"))
                .andExpect(jsonPath("$.merge.base_version").doesNotExist())
                .andExpect(jsonPath("$.merge.overwritten_version").doesNotExist())
                .andExpect(jsonPath("$.markdown").doesNotExist());
    }

    @Test
    void staleSaveWithoutOverlapIsMergedAndReturnsFinalBody() throws Exception {
        authorSaves(SERVER);
        String merged = "# 제목\n\n첫 문단(작성자)\n\n둘째 문단\n\n끝 문단(멤버)";

        save(member, BASE.replace("끝 문단", "끝 문단(멤버)"), 1, "write-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_version").value(3))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.markdown").value(merged))
                .andExpect(jsonPath("$.merge.status").value("merged"))
                .andExpect(jsonPath("$.merge.base_version").value(1))
                .andExpect(jsonPath("$.merge.server_version").value(2))
                .andExpect(jsonPath("$.merge.overwritten_version").doesNotExist());

        assertThat(markdown()).isEqualTo(merged);
        versions().andExpect(jsonPath("$.versions[0].version").value(3))
                .andExpect(jsonPath("$.versions[0].merge_status").value("merged"))
                .andExpect(jsonPath("$.versions[0].source").value("user"))
                .andExpect(jsonPath("$.versions[1].merge_status").doesNotExist());

        // 같은 revision_write_id 재전송은 처음 저장 결과(병합본)를 그대로 돌려준다.
        save(member, BASE.replace("끝 문단", "끝 문단(멤버)"), 1, "write-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_version").value(3))
                .andExpect(jsonPath("$.markdown").value(merged))
                .andExpect(jsonPath("$.merge.status").value("merged"));
        assertThat(revision()).isEqualTo(3L);
    }

    @Test
    void overlappingStaleSaveLastWriterWinsAndServerBodyIsRestorable() throws Exception {
        authorSaves(SERVER);
        String mine = BASE.replace("첫 문단", "첫 문단(멤버)");

        save(member, mine, 1, "write-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_version").value(3))
                .andExpect(jsonPath("$.markdown").value(mine))
                .andExpect(jsonPath("$.merge.status").value("overwritten"))
                .andExpect(jsonPath("$.merge.base_version").value(1))
                .andExpect(jsonPath("$.merge.server_version").value(2))
                .andExpect(jsonPath("$.merge.overwritten_version").value(2));
        assertThat(markdown()).isEqualTo(mine);

        mockMvc.perform(get(documentUrl() + "/versions/2").header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markdown").value(SERVER));
        mockMvc.perform(post(documentUrl() + "/versions/2/restore")
                        .header("Authorization", bearer(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"base_version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_version").value(4));
        assertThat(markdown()).isEqualTo(SERVER);
        versions().andExpect(jsonPath("$.versions[1].version").value(3))
                .andExpect(jsonPath("$.versions[1].merge_status").value("overwritten"));
    }

    @Test
    void missingBaseSnapshotFallsBackToLastWriterWins() throws Exception {
        authorSaves(SERVER);
        jdbc.update("DELETE FROM document_content_versions WHERE document_id = ? AND version = 1", documentId);
        String mine = BASE.replace("끝 문단", "끝 문단(멤버)");

        save(member, mine, 1, "write-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markdown").value(mine))
                .andExpect(jsonPath("$.merge.status").value("overwritten"))
                .andExpect(jsonPath("$.merge.overwritten_version").value(2));
        assertThat(markdown()).isEqualTo(mine);
    }

    @Test
    void baseAheadOfServerIsStillAConflict() throws Exception {
        save(member, "# 미래", 5, "write-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.current_revision").value(1));
    }

    @Test
    void agentApplyOnStaleBaseIsMergedAndAudited() {
        role(member, "OWNER");
        authorSaves(SERVER);
        String operationId = "op_" + UUID.randomUUID().toString().replace("-", "");
        String agentBody = BASE.replace("끝 문단", "끝 문단(AI)");
        agentRunCommandRepository.prepareToolApply("run_" + UUID.randomUUID(), workspace, member, documentId, 1,
                operationId, agentBody);

        DocumentContentSaveResponse saved = documentService.saveContent(
                workspace, member, documentId, agentBody, 1L, "agent-write-" + UUID.randomUUID(), "agent", operationId);

        assertThat(saved.merge().status()).isEqualTo("merged");
        assertThat(saved.currentVersion()).isEqualTo(3);
        assertThat(saved.markdown()).isEqualTo("# 제목\n\n첫 문단(작성자)\n\n둘째 문단\n\n끝 문단(AI)");
        assertThat(jdbc.queryForMap(
                "SELECT source, merge_status, operation_id FROM document_content_versions WHERE document_id = ? AND version = 3",
                documentId)).containsEntry("source", "agent").containsEntry("merge_status", "merged")
                .containsEntry("operation_id", operationId);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ai_operation_logs WHERE operation_id = ?", String.class, operationId))
                .isEqualTo("succeeded");
    }

    @Test
    void migrationArchivesOpenConflictsAsRestorableVersions() throws Exception {
        String databaseName = "v80_conflicts_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(
                postgresContainer.getJdbcUrl(), postgresContainer.getUsername(), postgresContainer.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }
        String url = "jdbc:postgresql://" + postgresContainer.getHost() + ":"
                + postgresContainer.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + databaseName;
        Flyway.configure().dataSource(url, postgresContainer.getUsername(), postgresContainer.getPassword())
                .target(MigrationVersion.fromVersion("79")).load().migrate();
        try (Connection connection = DriverManager.getConnection(
                url, postgresContainer.getUsername(), postgresContainer.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO documents(
                        id, byte_size, content_hash, filename, display_name, normalized_filename,
                        mime_type, status, uploaded_at, updated_at, user_id, updated_by, workspace_id,
                        current_content_hash, current_version, document_role, sort_order
                    ) VALUES ('doc_v80', 1, 'h-server', 'v80.md', 'v80', 'v80.md', 'text/markdown',
                              'completed', now(), now(), 'user_owner', 'user_editor', 'ws_v80',
                              'h-server', 1, 'EDITABLE', 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at)
                    VALUES ('doc_v80', '# 서버 본', 'h-server', 4, now(), now())
                    """);
            statement.executeUpdate("""
                    INSERT INTO document_content_versions(document_id, version, markdown, content_hash, created_by, created_at)
                    VALUES ('doc_v80', 3, '# 이전 본', 'h-old', 'user_owner', now() - interval '1 hour')
                    """);
            statement.executeUpdate("""
                    INSERT INTO document_edit_conflicts(
                        id, workspace_id, document_id, base_revision, markdown, content_hash, author_user_id,
                        client_conflict_id, created_at
                    ) VALUES
                        ('00000000-0000-0000-0000-000000000001', 'ws_v80', 'doc_v80', 3, '# 충돌 A', 'h-a', 'user_a', 'c-a', now() - interval '2 minutes'),
                        ('00000000-0000-0000-0000-000000000002', 'ws_v80', 'doc_v80', 3, '# 충돌 B', 'h-b', 'user_b', 'c-b', now() - interval '1 minute')
                    """);
            statement.executeUpdate("""
                    INSERT INTO document_edit_conflicts(
                        id, workspace_id, document_id, base_revision, markdown, content_hash, author_user_id,
                        client_conflict_id, status, resolution, resolved_by, resolved_revision, resolved_at
                    ) VALUES ('00000000-0000-0000-0000-000000000003', 'ws_v80', 'doc_v80', 2, '# 해결된 충돌', 'h-c',
                              'user_c', 'c-c', 'resolved', 'server', 'user_owner', 3, now())
                    """);
        }

        Flyway.configure().dataSource(url, postgresContainer.getUsername(), postgresContainer.getPassword())
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(
                url, postgresContainer.getUsername(), postgresContainer.getPassword());
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("""
                    SELECT version, markdown, created_by, source, merge_status, restored_from_version
                    FROM document_content_versions WHERE document_id = 'doc_v80' ORDER BY version
                    """)) {
                assertRow(rows, 3, "# 이전 본", "user_owner", null, null, null);
                assertRow(rows, 4, "# 서버 본", "user_editor", null, null, null);
                assertRow(rows, 5, "# 충돌 A", "user_a", "user", "conflict_archived", null);
                assertRow(rows, 6, "# 충돌 B", "user_b", "user", "conflict_archived", null);
                assertRow(rows, 7, "# 서버 본", "user_editor", "restore", null, 4L);
                assertThat(rows.next()).isFalse();
            }
            try (ResultSet state = statement.executeQuery(
                    "SELECT markdown, revision FROM document_edit_states WHERE document_id = 'doc_v80'")) {
                assertThat(state.next()).isTrue();
                assertThat(state.getString("markdown")).isEqualTo("# 서버 본");
                assertThat(state.getLong("revision")).isEqualTo(7);
            }
            try (ResultSet conflicts = statement.executeQuery("""
                    SELECT client_conflict_id, status, resolution, resolved_by, resolved_revision
                    FROM document_edit_conflicts WHERE document_id = 'doc_v80' ORDER BY client_conflict_id
                    """)) {
                assertConflict(conflicts, "c-a", "server", "system:auto-merge-migration", 5);
                assertConflict(conflicts, "c-b", "server", "system:auto-merge-migration", 6);
                assertConflict(conflicts, "c-c", "server", "user_owner", 3);
                assertThat(conflicts.next()).isFalse();
            }
            try (ResultSet outbox = statement.executeQuery(
                    "SELECT revision, content_hash, published FROM document_edit_outbox WHERE document_id = 'doc_v80'")) {
                assertThat(outbox.next()).isTrue();
                assertThat(outbox.getLong("revision")).isEqualTo(7);
                assertThat(outbox.getString("content_hash")).isEqualTo("h-server");
                assertThat(outbox.getBoolean("published")).isFalse();
                assertThat(outbox.next()).isFalse();
            }
        }
    }

    private static void assertRow(ResultSet rows, long version, String markdown, String createdBy,
                                  String source, String mergeStatus, Long restoredFrom) throws Exception {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong("version")).isEqualTo(version);
        assertThat(rows.getString("markdown")).isEqualTo(markdown);
        assertThat(rows.getString("created_by")).isEqualTo(createdBy);
        assertThat(rows.getString("source")).isEqualTo(source);
        assertThat(rows.getString("merge_status")).isEqualTo(mergeStatus);
        assertThat(rows.getObject("restored_from_version", Long.class)).isEqualTo(restoredFrom);
    }

    private static void assertConflict(ResultSet rows, String clientId, String resolution, String resolvedBy,
                                       long resolvedRevision) throws Exception {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("client_conflict_id")).isEqualTo(clientId);
        assertThat(rows.getString("status")).isEqualTo("resolved");
        assertThat(rows.getString("resolution")).isEqualTo(resolution);
        assertThat(rows.getString("resolved_by")).isEqualTo(resolvedBy);
        assertThat(rows.getLong("resolved_revision")).isEqualTo(resolvedRevision);
    }

    private void authorSaves(String markdown) {
        documentService.saveContent(workspace, author, documentId, markdown, 1L, "write_" + UUID.randomUUID(), null);
    }

    private ResultActions save(String userId, String markdown, long baseRevision, String writeId) throws Exception {
        return mockMvc.perform(multipart(HttpMethod.PUT, documentUrl() + "/content")
                .part(new MockPart("markdown", markdown.getBytes(StandardCharsets.UTF_8)))
                .part(new MockPart("base_revision", String.valueOf(baseRevision).getBytes()))
                .part(new MockPart("revision_write_id", writeId.getBytes()))
                .header("Authorization", bearer(userId)));
    }

    private ResultActions versions() throws Exception {
        return mockMvc.perform(get(documentUrl() + "/versions").header("Authorization", bearer(member)))
                .andExpect(status().isOk());
    }

    private String documentUrl() {
        return "/api/workspaces/" + workspace + "/documents/" + documentId;
    }

    private String markdown() {
        return jdbc.queryForObject("SELECT markdown FROM document_edit_states WHERE document_id = ?",
                String.class, documentId);
    }

    private Long revision() {
        return jdbc.queryForObject("SELECT revision FROM document_edit_states WHERE document_id = ?",
                Long.class, documentId);
    }

    private void role(String userId, String role) {
        redis.opsForValue().set("authz:role:v2:" + workspace + ":" + userId,
                "{\"role\":\"" + role + "\",\"permissions\":[]}");
    }

    private String bearer(String userId) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com");
    }
}
