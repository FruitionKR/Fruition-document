package fruition.core.aihistory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.service.DocumentEditLockService;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 문서 편집 복구는 복구를 실행하는 사용자의 권한·편집 잠금으로 저장된다.
 * 원래 작업자(A)가 아니라 실행자(B) 기준으로 403·423이 나는지 실제 DB로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DocumentRestorePermissionIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired DocumentService documentService;
    @Autowired DocumentPermissionService permissionService;
    @Autowired DocumentEditLockService editLockService;
    @Autowired OperationLogRepository operationLogRepository;
    @Autowired OperationChangeRepository operationChangeRepository;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    final String workspace = "ws_" + UUID.randomUUID();
    final String author = "user_author_" + UUID.randomUUID();
    final String member = "user_member_" + UUID.randomUUID();

    String documentId;
    String operationId;

    @BeforeEach
    void setUp() {
        role(author, "MEMBER");
        role(member, "MEMBER");
        documentId = documentService.createMarkdown(workspace, author, UUID.randomUUID().toString(),
                new MarkdownDocumentCreateRequest("회의록", "# 처음", null)).id();
        documentService.saveContent(workspace, author, documentId, "# 작성자 수정", 1L,
                "write_" + UUID.randomUUID(), null);
        // 작성자의 편집 1 -> 2를 document_edit 작업으로 기록한다.
        operationId = "op_" + UUID.randomUUID();
        operationLogRepository.save(OperationLog.completed(operationId, workspace, author,
                OperationType.document_edit, documentId, "edit", 1, Instant.now()));
        operationChangeRepository.save(new OperationChange(operationId, ResourceType.document, documentId,
                "회의록", 1L, 2L, ChangeType.updated, "edit", null, null));
    }

    @Test
    void viewOnlyMemberGets403AndDocumentIsUnchanged() throws Exception {
        permissionService.setDocument(workspace, author, documentId, "view");

        restore(member).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_WRITE_FORBIDDEN"));

        assertThat(currentMarkdown()).isEqualTo("# 작성자 수정");
    }

    @Test
    void memberGets423WhenAuthorHoldsEditLock() throws Exception {
        editLockService.acquire(workspace, author, documentId);

        restore(member).andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_EDIT_LOCKED"));

        assertThat(currentMarkdown()).isEqualTo("# 작성자 수정");
    }

    private org.springframework.test.web.servlet.ResultActions restore(String userId) throws Exception {
        String base = "/api/workspaces/" + workspace + "/ai-operation-logs/" + operationId;
        String token = objectMapper.readTree(mockMvc.perform(get(base + "/restore-preview")
                        .header("Authorization", bearer(userId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("preview_token").asText();
        return mockMvc.perform(post(base + "/restore")
                .header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preview_token\":\"" + token + "\"}"));
    }

    private String currentMarkdown() {
        return jdbc.queryForObject(
                "SELECT markdown FROM document_content_versions WHERE document_id = ? ORDER BY version DESC LIMIT 1",
                String.class, documentId);
    }

    private void role(String userId, String role) {
        redis.opsForValue().set("authz:role:v2:" + workspace + ":" + userId, "{\"role\":\"" + role + "\",\"permissions\":[]}");
    }

    private String bearer(String userId) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com");
    }
}
