package fruition.core.purge;

import fruition.TestcontainersConfiguration;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 Postgres FK와 MinIO로 워크스페이스·사용자 파기를 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalPurgeIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MinioClient minio;
    @Autowired StorageProperties storage;
    @Value("${app.internal.callback-token}") String token;

    @Test
    void workspacePurgeRemovesRowsAndObjectsAndCanBeRepeated() throws Exception {
        String workspace = "ws_" + UUID.randomUUID();
        String other = "ws_" + UUID.randomUUID();
        String user = "user_" + UUID.randomUUID();
        Seeded purged = seedWorkspace(workspace, user);
        Seeded kept = seedWorkspace(other, user);

        purgeWorkspaces(workspace)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted_rows.documents").value(1))
                .andExpect(jsonPath("$.deleted_rows.wiki_page_contributions").value(1))
                .andExpect(jsonPath("$.deleted_objects").value(3));

        for (String table : new String[]{"documents", "folders", "document_assets", "ai_operation_logs",
                "ai_task_runs", "chat_sessions", "meetings"}) {
            assertThat(count(table, workspace)).as(table).isZero();
            assertThat(count(table, other)).as(table).isOne();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wiki_page_versions WHERE page_id = ?",
                Integer.class, purged.pageId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_content_versions WHERE document_id = ?",
                Integer.class, purged.documentId())).isZero();
        for (String key : purged.objectKeys()) {
            assertThatThrownBy(() -> stat(key)).as(key).isNotNull();
        }
        for (String key : kept.objectKeys()) {
            stat(key);
        }

        purgeWorkspaces(workspace)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted_rows.documents").value(0))
                .andExpect(jsonPath("$.deleted_objects").value(0));
    }

    @Test
    void userPurgeRemovesPrivateDataButKeepsSharedDocuments() throws Exception {
        String workspace = "ws_" + UUID.randomUUID();
        String leaving = "user_" + UUID.randomUUID();
        String staying = "user_" + UUID.randomUUID();
        Seeded leaver = seedWorkspace(workspace, leaving);
        insertChatSession(workspace, staying);

        mockMvc.perform(post("/internal/purge/users").header("X-Internal-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"" + leaving + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted_rows.chat_sessions").value(1))
                .andExpect(jsonPath("$.deleted_rows.meetings").value(1))
                .andExpect(jsonPath("$.deleted_objects").value(1));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_sessions WHERE user_id = ?", Integer.class, leaving)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_sessions WHERE user_id = ?", Integer.class, staying)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE user_id = ?", Integer.class, leaving)).isZero();
        assertThat(count("meetings", workspace)).isZero();
        assertThat(count("documents", workspace)).isOne();
        assertThatThrownBy(() -> stat(leaver.recordingKey())).isNotNull();
        stat(leaver.sourceKey());
    }

    @Test
    void rejectsMissingToken() throws Exception {
        mockMvc.perform(post("/internal/purge/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace_ids\":[\"ws\"]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/purge/workspaces").header("X-Internal-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace_ids\":[]}"))
                .andExpect(status().isBadRequest());
    }

    private ResultActions purgeWorkspaces(String workspaceId) throws Exception {
        return mockMvc.perform(post("/internal/purge/workspaces").header("X-Internal-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspace_ids\":[\"" + workspaceId + "\"]}"));
    }

    /** 문서·이미지·녹음이 있는 워크스페이스. 위키 기여와 AI 실행 변경분처럼 CASCADE 없이 참조하는 행도 넣는다. */
    private Seeded seedWorkspace(String workspace, String user) throws Exception {
        UUID folderId = UUID.randomUUID();
        String documentId = UUID.randomUUID().toString();
        UUID assetId = UUID.randomUUID();
        String operationId = "op_" + UUID.randomUUID();
        String runId = "run_" + UUID.randomUUID();
        String meetingId = "mt_" + UUID.randomUUID();
        String pageId = "page_" + UUID.randomUUID();
        String sourceKey = "sources/documents/" + documentId + "/original";
        String assetKey = "assets/" + workspace + "/" + assetId + "/content";
        String recordingKey = "meetings/" + meetingId + "/recording-" + UUID.randomUUID() + ".webm";

        jdbc.update("""
                INSERT INTO folders(id, workspace_id, parent_folder_id, name, sort_order, current_version, created_at, updated_at)
                VALUES (?, ?, NULL, '자료', 0, 1, now(), now())
                """, folderId, workspace);
        jdbc.update("""
                INSERT INTO documents(
                    id, byte_size, content_hash, filename, display_name, normalized_filename,
                    mime_type, source_uri, status, uploaded_at, updated_at, user_id, workspace_id,
                    current_content_hash, current_version, document_role, sort_order, folder_id
                ) VALUES (?, 1, 'hash', 'a.pdf', 'a', 'a.pdf', 'application/pdf', ?, 'completed', now(), now(), ?, ?,
                          'hash', 1, 'ORIGINAL', 0, ?)
                """, documentId, "s3://" + storage.getBucket() + "/" + sourceKey, user, workspace, folderId);
        jdbc.update("""
                INSERT INTO document_content_versions(document_id, version, markdown, content_hash, created_by, created_at)
                VALUES (?, 1, '본문', 'hash', ?, now())
                """, documentId, user);
        jdbc.update("""
                INSERT INTO document_assets(id, workspace_id, uploaded_by, original_filename, content_type, byte_size,
                    width, height, content_hash, storage_key, created_at)
                VALUES (?, ?, ?, 'a.png', 'image/png', 1, 1, 1, 'hash', ?, now())
                """, assetId, workspace, user, assetKey);
        jdbc.update("INSERT INTO document_asset_references(document_id, asset_id, created_at) VALUES (?, ?, now())",
                documentId, assetId);
        jdbc.update("""
                INSERT INTO ai_operation_logs(operation_id, workspace_id, user_id, operation_type, target_document_id,
                    status, changed_resource_count, created_at)
                VALUES (?, ?, ?, 'ingest', ?, 'succeeded', 1, now())
                """, operationId, workspace, user, documentId);
        jdbc.update("""
                INSERT INTO wiki_page_contributions(page_id, ingest_operation_id, source_document_id, sequence_revision,
                    object_key, active, created_at)
                VALUES (?, ?, ?, 1, 'wiki/contribution', true, now())
                """, pageId, operationId, documentId);
        jdbc.update("""
                INSERT INTO wiki_page_versions(page_id, revision, contribution_count, markdown, markdown_key,
                    content_hash, operation_id, created_at)
                VALUES (?, 1, 1, '위키', 'wiki/key', 'hash', NULL, now())
                """, pageId);
        jdbc.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind) VALUES (?, ?, ?, 'query')",
                runId, workspace, user);
        jdbc.update("INSERT INTO ai_task_changes(run_id, table_name, row_key) VALUES (?, 'documents', '{}'::jsonb)", runId);
        insertChatSession(workspace, user);
        jdbc.update("""
                INSERT INTO meetings(id, workspace_id, created_by, display_name, source, status, recording_key,
                    created_at, updated_at)
                VALUES (?, ?, ?, '주간 회의', 'upload', 'failed', ?, now(), now())
                """, meetingId, workspace, user, recordingKey);

        for (String key : new String[]{sourceKey, assetKey, recordingKey}) {
            minio.putObject(PutObjectArgs.builder().bucket(storage.getBucket()).object(key)
                    .stream(new ByteArrayInputStream(new byte[]{1}), 1, -1).build());
        }
        return new Seeded(documentId, pageId, sourceKey, assetKey, recordingKey);
    }

    private void insertChatSession(String workspace, String user) {
        jdbc.update("INSERT INTO chat_sessions(id, created_at, user_id, workspace_id) VALUES (?, now(), ?, ?)",
                "chat_" + UUID.randomUUID(), user, workspace);
    }

    private int count(String table, String workspace) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE workspace_id = ?", Integer.class, workspace);
    }

    private void stat(String key) throws Exception {
        minio.statObject(StatObjectArgs.builder().bucket(storage.getBucket()).object(key).build());
    }

    private record Seeded(String documentId, String pageId, String sourceKey, String assetKey, String recordingKey) {
        String[] objectKeys() {
            return new String[]{sourceKey, assetKey, recordingKey};
        }
    }
}
