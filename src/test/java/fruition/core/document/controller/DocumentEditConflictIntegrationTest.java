package fruition.core.document.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.service.DocumentPermissionService;
import fruition.core.document.service.DocumentService;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 편집 충돌 API(deprecated, ADR 0032). 등록은 곧바로 자동 병합 저장으로 처리되고 해결된 충돌로 남는다.
 * 자동 병합 도입 전에 열린 충돌의 목록·해결은 그대로 동작한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DocumentEditConflictIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired DocumentService documentService;
    @Autowired DocumentPermissionService permissionService;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    final String workspace = "ws_" + UUID.randomUUID();
    final String author = "user_author_" + UUID.randomUUID();
    final String member = "user_member_" + UUID.randomUUID();
    final String owner = "user_owner_" + UUID.randomUUID();

    String documentId;

    @BeforeEach
    void setUp() {
        role(author, "MEMBER");
        role(member, "MEMBER");
        role(owner, "OWNER");
        documentId = documentService.createMarkdown(workspace, author, UUID.randomUUID().toString(),
                new MarkdownDocumentCreateRequest("회의록", "# 처음\n\n본문", null)).id();
        // 작성자가 먼저 저장해 revision 2가 된다. revision 1에서 편집하던 멤버의 저장은 자동 병합된다.
        documentService.saveContent(workspace, author, documentId, "# 작성자 본\n\n본문", 1L,
                "write_" + UUID.randomUUID(), null);
    }

    @Test
    void registerAutoMergesAndReturnsResolvedConflictWithoutOwnerNotification() throws Exception {
        String first = register(member, "# 처음\n\n본문\n\n멤버 추가", "client-1").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("resolved"))
                .andExpect(jsonPath("$.resolution").value("merged"))
                .andExpect(jsonPath("$.resolved_by").value(member))
                .andExpect(jsonPath("$.resolved_revision").value(3))
                .andExpect(jsonPath("$.author_user_id").value(member))
                .andExpect(jsonPath("$.base_revision").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(markdown()).isEqualTo("# 작성자 본\n\n본문\n\n멤버 추가");

        // 같은 client_conflict_id 재전송은 기존 충돌을 그대로 돌려주고 다시 저장하지 않는다.
        String second = register(member, "# 처음\n\n본문\n\n멤버 추가", "client-1").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(second).path("id")).isEqualTo(objectMapper.readTree(first).path("id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_edit_conflicts WHERE document_id = ?",
                Integer.class, documentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revision FROM document_edit_states WHERE document_id = ?",
                Long.class, documentId)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE workspace_id = ? AND type = ?",
                Integer.class, workspace, "edit_conflict_registered")).isZero();
        mockMvc.perform(get("/api/workspaces/" + workspace + "/conflicts").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts.length()").value(0));
    }

    @Test
    void registerOverlappingKeepsConflictBodyAndServerBodyInVersions() throws Exception {
        register(member, "# 멤버 본\n\n본문", "client-1").andExpect(status().isCreated())
                .andExpect(jsonPath("$.resolution").value("conflict"))
                .andExpect(jsonPath("$.resolved_revision").value(3));

        assertThat(markdown()).isEqualTo("# 멤버 본\n\n본문");
        assertThat(jdbc.queryForObject("SELECT markdown FROM document_content_versions WHERE document_id = ? AND version = 2",
                String.class, documentId)).isEqualTo("# 작성자 본\n\n본문");
        assertThat(jdbc.queryForObject("SELECT merge_status FROM document_content_versions WHERE document_id = ? AND version = 3",
                String.class, documentId)).isEqualTo("overwritten");
    }

    @Test
    void openConflictFromBeforeAutoMergeCanStillBeResolved() throws Exception {
        String conflictId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO document_edit_conflicts(
                    id, workspace_id, document_id, base_revision, markdown, content_hash, author_user_id, client_conflict_id
                ) VALUES (?::uuid, ?, ?, 1, '# 예전 충돌 본', 'hash', ?, 'legacy-1')
                """, conflictId, workspace, documentId, member);

        mockMvc.perform(get("/api/workspaces/" + workspace + "/conflicts").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts.length()").value(1))
                .andExpect(jsonPath("$.conflicts[0].server.revision").value(2));
        resolve(member, conflictId, Map.of("choice", "server")).andExpect(status().isForbidden());
        resolve(owner, conflictId, Map.of("choice", "conflict", "base_revision", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("conflict"))
                .andExpect(jsonPath("$.resolved_revision").value(3));
        assertThat(markdown()).isEqualTo("# 예전 충돌 본");
        resolve(owner, conflictId, Map.of("choice", "server"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT_ALREADY_RESOLVED"));
    }

    @Test
    void viewOnlyMemberCannotRegister() throws Exception {
        permissionService.setDocument(workspace, author, documentId, "view");

        register(member, "# 멤버 본", "client-1").andExpect(status().isForbidden());
    }

    @Test
    void originalDocumentCannotRegisterConflict() throws Exception {
        documentId = documentService.upload(workspace, author, UUID.randomUUID().toString(), null,
                new MockMultipartFile("file", "보고서.pdf", "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8))).id();

        register(member, "# 멤버 본", "client-1").andExpect(status().isBadRequest());
    }

    private ResultActions register(String userId, String markdown, String clientConflictId) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + workspace + "/documents/" + documentId + "/conflicts")
                .header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "markdown", markdown, "base_revision", 1, "client_conflict_id", clientConflictId))));
    }

    private ResultActions resolve(String userId, String conflictId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + workspace + "/conflicts/" + conflictId + "/resolve")
                .header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String markdown() {
        return jdbc.queryForObject("SELECT markdown FROM document_edit_states WHERE document_id = ?",
                String.class, documentId);
    }

    private void role(String userId, String role) {
        redis.opsForValue().set("authz:role:v2:" + workspace + ":" + userId, "{\"role\":\"" + role + "\",\"permissions\":[]}");
    }

    private String bearer(String userId) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com");
    }
}
