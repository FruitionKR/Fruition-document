package fruition.core.agent.controller;

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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Agent 폴더 트리 읽기가 사이드바 트리와 같은 계층을 서비스 토큰으로만 돌려주는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AgentFolderLookupIntegrationTest {

    private static final String URL = "/internal/agent/folder-lookup/tree";
    /** build.gradle이 테스트에 넣는 AGENT_INTERNAL_TOKEN. */
    private static final String TOKEN = "test-agent-internal";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    final String workspace = "ws_" + UUID.randomUUID();
    final String member = "member_" + UUID.randomUUID();
    final String outsider = "outsider_" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        role(member, "MEMBER");
        role(outsider, "NONE");
    }

    @Test
    void returnsNestedFoldersAndDocuments() throws Exception {
        UUID minutes = insertFolder(null, "회의록");
        insertFolder(minutes, "2026");
        String document = insertDocument(minutes);

        tree(member)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("folder"))
                .andExpect(jsonPath("$.items[0].name").value("회의록"))
                .andExpect(jsonPath("$.items[0].children[0].name").value("2026"))
                .andExpect(jsonPath("$.items[0].children[1].id").value(document))
                .andExpect(jsonPath("$.items[0].children[1].document.display_name").value("문서-" + document));
    }

    @Test
    void rejectsNonMemberWith404() throws Exception {
        insertFolder(null, "회의록");

        tree(outsider)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));
    }

    @Test
    void rejectsMissingOrWrongServiceTokenWith401() throws Exception {
        String body = body(member);

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).header("X-Agent-Service-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions tree(String userId) throws Exception {
        return mockMvc.perform(post(URL)
                .header("X-Agent-Service-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(userId)));
    }

    private String body(String userId) {
        return """
                {"workspace_id":"%s","user_id":"%s"}
                """.formatted(workspace, userId);
    }

    private void role(String userId, String role) {
        redis.opsForValue().set("authz:role:v2:" + workspace + ":" + userId,
                "{\"role\":\"" + role + "\",\"permissions\":[]}");
    }

    private UUID insertFolder(UUID parent, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO folders(id, workspace_id, parent_folder_id, name, sort_order, current_version,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, 1, now(), now())
                """, id, workspace, parent, name);
        return id;
    }

    private String insertDocument(UUID folder) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO documents(
                    id, byte_size, content_hash, filename, display_name, normalized_filename,
                    mime_type, source_uri, status, uploaded_at, updated_at, user_id, workspace_id,
                    current_content_hash, current_version, document_role, sort_order, folder_id
                ) VALUES (?, 1, 'hash', ?, ?, ?, 'text/markdown', '', 'completed', now(), now(), ?, ?,
                          'hash', 1, 'EDITABLE', 1, ?)
                """, id, id + ".md", "문서-" + id, id + ".md", member, workspace, folder);
        return id;
    }
}
