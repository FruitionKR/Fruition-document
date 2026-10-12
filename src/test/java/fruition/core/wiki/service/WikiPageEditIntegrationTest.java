package fruition.core.wiki.service;

import fruition.TestcontainersConfiguration;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.PipelineWikiStateRequester.ManualEditResult;
import fruition.core.wiki.repository.PipelineWikiStateRequester.WikiPageSnapshot;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 사람이 Wiki 본문을 고치는 API(#75): revision 충돌, write ID 재전송, 권한, ai 기록. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WikiPageEditIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean PipelineWikiStateRequester wikiStateRequester;

    private String userId;
    private String workspaceId;
    private String pageId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_" + suffix;
        workspaceId = "ws_" + suffix;
        pageId = "wp_" + suffix;
        role(userId, "OWNER", "[]");
        when(wikiStateRequester.lookup(List.of(pageId), workspaceId)).thenReturn(List.of(
                new WikiPageSnapshot(pageId, "concept", "캐시", "cache", workspaceId, "active")));
        when(wikiStateRequester.manualEdit(eq(workspaceId), anyString(), eq(pageId), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String operationId = invocation.getArgument(3);
                    String base = "wiki/" + workspaceId + "/pages/" + pageId + "/ops/" + operationId;
                    return Optional.of(new ManualEditResult(pageId, "concept", base + ".md", base + ".json",
                            "sha256:" + "a".repeat(64)));
                });
        // 이력 도입 뒤 AI가 만든 revision 2까지 있는 페이지
        jdbc.update("""
                INSERT INTO wiki_page_versions(page_id, revision, contribution_count, markdown, markdown_key,
                    content_hash, created_at)
                VALUES (?, 1, 1, '# 캐시', 'k1', 'h1', now()), (?, 2, 1, '# 캐시\n근거', 'k2', 'h2', now())
                """, pageId, pageId);
    }

    @Test
    void save_appendsRevisionAndRecordsManualContribution() throws Exception {
        save("# 캐시\n사람이 고침", 2, "w1").andExpect(status().isOk())
                .andExpect(jsonPath("$.page_id").value(pageId))
                .andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.markdown").value("# 캐시\n사람이 고침"))
                .andExpect(jsonPath("$.updated_at").isNotEmpty());

        Map<String, Object> version = jdbc.queryForMap(
                "SELECT * FROM wiki_page_versions WHERE page_id = ? AND revision = 3", pageId);
        String operationId = (String) version.get("operation_id");
        assertThat(version.get("created_by")).isEqualTo(userId);
        assertThat((String) version.get("markdown_key")).endsWith(operationId + ".md");
        verify(wikiStateRequester).manualEdit(workspaceId, userId, pageId, operationId, "# 캐시\n사람이 고침");

        Map<String, Object> contribution = jdbc.queryForMap(
                "SELECT * FROM wiki_page_contributions WHERE page_id = ? AND ingest_operation_id = ?",
                pageId, operationId);
        assertThat((String) contribution.get("object_key")).endsWith(operationId + ".json");
        assertThat(contribution.get("source_document_id")).isNull();
        assertThat(contribution.get("sequence_revision")).isEqualTo(3L);
        assertThat(contribution.get("active")).isEqualTo(true);

        assertThat(jdbc.queryForMap("SELECT * FROM ai_operation_logs WHERE operation_id = ?", operationId))
                .containsEntry("operation_type", "wiki_edit")
                .containsEntry("status", "succeeded")
                .containsEntry("workspace_id", workspaceId)
                .containsEntry("user_id", userId);
        assertThat(jdbc.queryForMap("SELECT * FROM ai_operation_changes WHERE operation_id = ?", operationId))
                .containsEntry("resource_id", pageId)
                .containsEntry("resource_display_name", "캐시")
                .containsEntry("before_revision", 2L)
                .containsEntry("after_revision", 3L)
                .containsEntry("change_type", "updated");
    }

    @Test
    void staleBaseRevision_returns409WithCurrentRevision() throws Exception {
        save("# 다른 본문", 1, "w1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WIKI_PAGE_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.current_revision").value(2));

        verify(wikiStateRequester, never()).manualEdit(any(), any(), any(), any(), any());
        assertThat(maxRevision()).isEqualTo(2);
    }

    @Test
    void sameWriteId_replaysFirstResultWithoutCallingAiAgain() throws Exception {
        save("# 고침", 2, "w1").andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3));
        // 응답을 못 받은 클라이언트의 재전송은 base_revision이 그대로다.
        save("# 고침", 2, "w1").andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3));
        // 같은 write ID를 다른 본문에 쓰면 거절한다.
        save("# 또 고침", 2, "w1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));

        verify(wikiStateRequester, times(1)).manualEdit(any(), any(), any(), any(), any());
        assertThat(maxRevision()).isEqualTo(3);
    }

    @Test
    void sameMarkdown_isNoOp() throws Exception {
        save("# 캐시\n근거", 2, "w1").andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));

        verify(wikiStateRequester, never()).manualEdit(any(), any(), any(), any(), any());
    }

    @Test
    void memberWithoutWikiIngest_returns403() throws Exception {
        role(userId, "MEMBER", "[]");

        save("# 고침", 2, "w1").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_PERMISSION_REQUIRED"));
        verify(wikiStateRequester, never()).manualEdit(any(), any(), any(), any(), any());
    }

    @Test
    void memberWithWikiIngest_canEdit() throws Exception {
        role(userId, "MEMBER", "[\"wiki.ingest\"]");

        save("# 고침", 2, "w1").andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3));
    }

    @Test
    void nonMember_returns404() throws Exception {
        role(userId, "NONE", "[]");

        save("# 고침", 2, "w1").andExpect(status().isNotFound());
        verify(wikiStateRequester, never()).manualEdit(any(), any(), any(), any(), any());
    }

    @Test
    void pageMissingInAi_returns404AndRecordsNothing() throws Exception {
        when(wikiStateRequester.manualEdit(eq(workspaceId), anyString(), eq(pageId), anyString(), anyString()))
                .thenReturn(Optional.empty());

        save("# 고침", 2, "w1").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WIKI_PAGE_NOT_FOUND"));
        assertThat(maxRevision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_operation_logs WHERE workspace_id = ?",
                Integer.class, workspaceId)).isZero();
    }

    private ResultActions save(String markdown, long baseRevision, String writeId) throws Exception {
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "markdown", markdown, "base_revision", baseRevision, "revision_write_id", writeId));
        return mockMvc.perform(put("/api/workspaces/" + workspaceId + "/wiki/pages/" + pageId + "/content")
                .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long maxRevision() {
        return jdbc.queryForObject("SELECT max(revision) FROM wiki_page_versions WHERE page_id = ?",
                Long.class, pageId);
    }

    private void role(String user, String role, String permissions) {
        redisTemplate.opsForValue().set("authz:role:v2:" + workspaceId + ":" + user,
                "{\"role\":\"" + role + "\",\"permissions\":" + permissions + "}");
    }
}
