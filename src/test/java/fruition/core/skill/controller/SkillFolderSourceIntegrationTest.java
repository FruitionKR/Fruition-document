package fruition.core.skill.controller;

import fruition.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 폴더 원문 읽기의 대상 범위·정렬·상한·권한을 실제 Postgres와 HTTP로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SkillFolderSourceIntegrationTest {

    private static final String URL = "/internal/agent/skill-execution/folder-sources/read";
    /** build.gradle이 테스트에 넣는 AGENT_INTERNAL_TOKEN. */
    private static final String TOKEN = "test-agent-internal";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    final String workspace = "ws_" + UUID.randomUUID();
    final String owner = "owner_" + UUID.randomUUID();
    final String member = "member_" + UUID.randomUUID();
    final String outsider = "outsider_" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        role(owner, "OWNER");
        role(member, "MEMBER");
        role(outsider, "NONE");
    }

    @Test
    void readsDirectEditableDocumentsNewestFirstWithinLimit() throws Exception {
        UUID folder = insertFolder(workspace, null);
        UUID child = insertFolder(workspace, folder);
        String oldest = insertDocument(folder, "EDITABLE", "2026-01-01T00:00:00Z", "# 가장 오래된");
        String newest = insertDocument(folder, "EDITABLE", "2026-01-03T00:00:00Z", "# 가장 최근");
        String middle = insertDocument(folder, "EDITABLE", "2026-01-02T00:00:00Z", "# 가운데");
        insertDocument(child, "EDITABLE", "2026-02-01T00:00:00Z", "# 하위 폴더");
        insertDocument(folder, "ORIGINAL", "2026-02-01T00:00:00Z", null);
        String deleted = insertDocument(folder, "EDITABLE", "2026-02-01T00:00:00Z", "# 삭제됨");
        jdbc.update("UPDATE documents SET deleted_at = now(), deleted_by = ? WHERE id = ?", owner, deleted);

        read(member, folder.toString(), 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_documents").value(3))
                .andExpect(jsonPath("$.items[*].document_id").value(contains(newest, middle)))
                .andExpect(jsonPath("$.items[0].display_name").value("문서-" + newest))
                .andExpect(jsonPath("$.items[0].updated_at").value("2026-01-03T00:00:00Z"))
                .andExpect(jsonPath("$.items[0].markdown").value("# 가장 최근"));

        read(member, folder.toString(), 50)
                .andExpect(jsonPath("$.items[*].document_id").value(contains(newest, middle, oldest)));
    }

    @Test
    void clampsMaxDocumentsToOneThroughFifty() throws Exception {
        UUID folder = insertFolder(workspace, null);
        for (int day = 1; day <= 52; day++) {
            insertDocument(folder, "EDITABLE", "2026-03-%02dT00:00:00Z".formatted(Math.min(day, 28)), "# " + day);
        }

        read(member, folder.toString(), 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_documents").value(52))
                .andExpect(jsonPath("$.items", hasSize(1)));
        read(member, folder.toString(), 1000)
                .andExpect(jsonPath("$.items", hasSize(50)));
    }

    @Test
    void returnsNullMarkdownWhenNoSavedBodyExists() throws Exception {
        UUID folder = insertFolder(workspace, null);
        String document = insertDocument(folder, "EDITABLE", "2026-01-01T00:00:00Z", null);

        read(member, folder.toString(), 20)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].document_id").value(document))
                .andExpect(jsonPath("$.items[0].markdown").value(nullValue()));
    }

    @Test
    void viewOnlyDocumentsAreStillReadable() throws Exception {
        UUID folder = insertFolder(workspace, null);
        String viewOnly = insertDocument(folder, "EDITABLE", "2026-01-02T00:00:00Z", "# 보기 전용");
        String inViewFolder = insertDocument(folder, "EDITABLE", "2026-01-01T00:00:00Z", "# 폴더 보기 전용");
        jdbc.update("INSERT INTO document_permissions(workspace_id, document_id, access, updated_by) VALUES (?, ?, 'view', ?)",
                workspace, viewOnly, owner);
        jdbc.update("INSERT INTO document_permissions(workspace_id, folder_id, access, updated_by) VALUES (?, ?, 'view', ?)",
                workspace, folder, owner);

        read(member, folder.toString(), 20)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_documents").value(2))
                .andExpect(jsonPath("$.items[*].document_id").value(contains(viewOnly, inViewFolder)));
    }

    @Test
    void rejectsNonMemberWith403() throws Exception {
        UUID folder = insertFolder(workspace, null);

        read(outsider, folder.toString(), 20)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FOLDER_SOURCE_FORBIDDEN"));
    }

    @Test
    void rejectsMissingOrForeignFolderWith404() throws Exception {
        String otherWorkspace = "ws_" + UUID.randomUUID();
        role(member, "MEMBER", otherWorkspace);
        UUID foreign = insertFolder(otherWorkspace, null);
        UUID deleted = insertFolder(workspace, null);
        jdbc.update("UPDATE folders SET deleted_at = now() WHERE id = ?", deleted);

        read(member, foreign.toString(), 20).andExpect(status().isNotFound());
        read(member, deleted.toString(), 20).andExpect(status().isNotFound());
        read(member, UUID.randomUUID().toString(), 20).andExpect(status().isNotFound());
        read(member, "not-a-uuid", 20).andExpect(status().isNotFound());
    }

    @Test
    void rejectsMissingOrWrongServiceTokenWith401() throws Exception {
        UUID folder = insertFolder(workspace, null);
        String body = body(member, folder.toString(), 20);

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).header("X-Agent-Service-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions read(String userId, String folderId, int maxDocuments) throws Exception {
        return mockMvc.perform(post(URL)
                .header("X-Agent-Service-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(userId, folderId, maxDocuments)));
    }

    private String body(String userId, String folderId, int maxDocuments) {
        return """
                {"workspace_id":"%s","user_id":"%s","folder_id":"%s","max_documents":%d}
                """.formatted(workspace, userId, folderId, maxDocuments);
    }

    private void role(String userId, String role) {
        role(userId, role, workspace);
    }

    private void role(String userId, String role, String workspaceId) {
        redis.opsForValue().set("authz:role:v2:" + workspaceId + ":" + userId,
                "{\"role\":\"" + role + "\",\"permissions\":[]}");
    }

    private UUID insertFolder(String workspaceId, UUID parent) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO folders(id, workspace_id, parent_folder_id, name, sort_order, current_version,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, 1, now(), now())
                """, id, workspaceId, parent, "폴더-" + id);
        return id;
    }

    /** markdown이 null이면 편집 상태를 넣지 않는다. source_uri가 비어 있어 저장소에서 만들지도 않는다. */
    private String insertDocument(UUID folder, String role, String updatedAt, String markdown) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO documents(
                    id, byte_size, content_hash, filename, display_name, normalized_filename,
                    mime_type, source_uri, status, uploaded_at, updated_at, user_id, workspace_id,
                    current_content_hash, current_version, document_role, sort_order, folder_id
                ) VALUES (?, 1, 'hash', ?, ?, ?, 'text/markdown', '', 'completed', now(), ?, ?, ?,
                          'hash', 1, ?, 0, ?)
                """, id, id + ".md", "문서-" + id, id + ".md", Timestamp.from(Instant.parse(updatedAt)),
                owner, workspace, role, folder);
        if (markdown != null) {
            jdbc.update("""
                    INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at)
                    VALUES (?, ?, 'hash', 1, now(), now())
                    """, id, markdown);
        }
        return id;
    }
}
