package fruition.core.document.controller;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 409를 받은 멤버가 충돌을 등록하고 OWNER가 목록에서 골라 해결하는 흐름을 실제 DB와 HTTP로 확인한다. */
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
                new MarkdownDocumentCreateRequest("회의록", "# 처음", null)).id();
        // 작성자가 먼저 저장해 revision 2가 되고, revision 1에서 편집하던 멤버는 충돌한다.
        documentService.saveContent(workspace, author, documentId, "# 작성자 본", 1L, "write_" + UUID.randomUUID(), null);
    }

    @Test
    void staleSaveReturnsCurrentRevision() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/workspaces/" + workspace + "/documents/" + documentId + "/content")
                        .part(new MockPart("markdown", "# 멤버 본".getBytes(StandardCharsets.UTF_8)))
                        .part(new MockPart("base_revision", "1".getBytes()))
                        .part(new MockPart("revision_write_id", UUID.randomUUID().toString().getBytes()))
                        .header("Authorization", bearer(member)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.current_revision").value(2));
    }

    @Test
    void resendingSameConflictReturnsExistingOne() throws Exception {
        String first = register(member, "# 멤버 본", "client-1").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.author_user_id").value(member))
                .andExpect(jsonPath("$.base_revision").value(1))
                .andReturn().getResponse().getContentAsString();
        String second = register(member, "# 멤버 본", "client-1").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second).path("id")).isEqualTo(objectMapper.readTree(first).path("id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_edit_conflicts WHERE document_id = ?",
                Integer.class, documentId)).isEqualTo(1);
    }

    @Test
    void ownerListsAndConcurrentResolvesApplyOnlyOnce() throws Exception {
        String conflictId = conflictId(register(member, "# 멤버 본", "client-1"));

        mockMvc.perform(get("/api/workspaces/" + workspace + "/conflicts").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts.length()").value(1))
                .andExpect(jsonPath("$.conflicts[0].conflict.id").value(conflictId))
                .andExpect(jsonPath("$.conflicts[0].conflict.markdown").value("# 멤버 본"))
                .andExpect(jsonPath("$.conflicts[0].document_name").value("회의록"))
                .andExpect(jsonPath("$.conflicts[0].server.markdown").value("# 작성자 본"))
                .andExpect(jsonPath("$.conflicts[0].server.revision").value(2))
                .andExpect(jsonPath("$.conflicts[0].server.updated_by").value(author));

        // 두 요청을 동시에 보내도 충돌 행 잠금으로 하나만 반영되고 나머지는 이미 해결됨 409다.
        Map<String, Object> body = Map.of("choice", "conflict", "base_revision", 2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return resolve(owner, conflictId, body).andReturn().getResponse();
            }));
        }
        start.countDown();
        List<MockHttpServletResponse> responses = new ArrayList<>();
        for (Future<MockHttpServletResponse> future : futures) responses.add(future.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
        JsonNode resolved = objectMapper.readTree(responses.stream().filter(r -> r.getStatus() == 200)
                .findFirst().orElseThrow().getContentAsString());
        assertThat(resolved.path("status").asText()).isEqualTo("resolved");
        assertThat(resolved.path("resolution").asText()).isEqualTo("conflict");
        assertThat(resolved.path("resolved_by").asText()).isEqualTo(owner);
        assertThat(resolved.path("resolved_revision").asLong()).isEqualTo(3);
        JsonNode rejected = objectMapper.readTree(responses.stream().filter(r -> r.getStatus() == 409)
                .findFirst().orElseThrow().getContentAsString());
        assertThat(rejected.path("error").path("code").asText()).isEqualTo("CONFLICT_ALREADY_RESOLVED");
        assertThat(jdbc.queryForObject("SELECT revision FROM document_edit_states WHERE document_id = ?",
                Long.class, documentId)).isEqualTo(3L);
        assertThat(markdown()).isEqualTo("# 멤버 본");
        // 고르지 않은 서버 본은 버전 이력에 남는다.
        assertThat(jdbc.queryForObject("SELECT markdown FROM document_content_versions WHERE document_id = ? AND version = 2",
                String.class, documentId)).isEqualTo("# 작성자 본");
        mockMvc.perform(get("/api/workspaces/" + workspace + "/conflicts").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.conflicts.length()").value(0));
    }

    @Test
    void choosingServerKeepsContentAndConflictRow() throws Exception {
        String conflictId = conflictId(register(member, "# 멤버 본", "client-1"));

        resolve(owner, conflictId, Map.of("choice", "server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("server"))
                .andExpect(jsonPath("$.resolved_revision").value(2))
                .andExpect(jsonPath("$.markdown").value("# 멤버 본"));
        assertThat(markdown()).isEqualTo("# 작성자 본");
    }

    @Test
    void choosingMergedSavesGivenMarkdown() throws Exception {
        String conflictId = conflictId(register(member, "# 멤버 본", "client-1"));

        resolve(owner, conflictId, Map.of("choice", "merged", "base_revision", 2))
                .andExpect(status().isBadRequest());
        Map<String, Object> merged = Map.of("choice", "merged", "base_revision", 2, "markdown", "# 합친 본");
        resolve(owner, conflictId, merged)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("merged"))
                .andExpect(jsonPath("$.resolved_revision").value(3));
        assertThat(markdown()).isEqualTo("# 합친 본");
        assertThat(jdbc.queryForObject("SELECT updated_by FROM documents WHERE id = ?", String.class, documentId))
                .isEqualTo(owner);
    }

    @Test
    void onlyOwnerListsAndResolves() throws Exception {
        String conflictId = conflictId(register(member, "# 멤버 본", "client-1"));

        mockMvc.perform(get("/api/workspaces/" + workspace + "/conflicts").header("Authorization", bearer(author)))
                .andExpect(status().isForbidden());
        resolve(member, conflictId, Map.of("choice", "server")).andExpect(status().isForbidden());
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

    private String conflictId(ResultActions registered) throws Exception {
        JsonNode body = objectMapper.readTree(registered.andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return body.path("id").asText();
    }

    private String markdown() {
        return jdbc.queryForObject("SELECT markdown FROM document_edit_states WHERE document_id = ?",
                String.class, documentId);
    }

    private void role(String userId, String role) {
        redis.opsForValue().set("authz:role:" + workspace + ":" + userId, role);
    }

    private String bearer(String userId) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com");
    }
}
