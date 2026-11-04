package fruition.core.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.agent.repository.AgentRunCommandRepository;
import fruition.core.agent.repository.PipelineAgentArtifactClient;
import fruition.core.agent.repository.PipelineAgentToolAuthorizationClient;
import fruition.core.document.dto.DocumentContentSaveResponse;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.service.DocumentEditLockService;
import fruition.core.document.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 승인된 apply_document_edit이 실제 DB에서 병합만 하고 겹치면 덮어쓰지 않는지, 적용 표·감사 상태가 어떻게 남는지 본다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class AgentToolApplyDocumentEditIntegrationTest {

    private static final String BASE = "# 제목\n\n첫 문단\n\n둘째 문단\n\n끝 문단";
    private static final String SERVER = "# 제목\n\n첫 문단(작성자 표식)\n\n둘째 문단\n\n끝 문단";
    private static final String AGENT_MARKER = "AI가쓴본문표식";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired DocumentService documents;
    @Autowired DocumentEditLockService locks;
    @Autowired AgentRunCommandRepository runCommands;
    @MockBean PipelineAgentToolAuthorizationClient authorization;
    @MockBean PipelineAgentArtifactClient artifacts;
    @Value("${app.skill.agent-token}") String serviceToken;

    private final String workspace = "ws_" + UUID.randomUUID();
    private final String author = "user_author_" + UUID.randomUUID();
    private final String member = "user_member_" + UUID.randomUUID();
    private final String runId = UUID.randomUUID().toString();
    private final String operationId = "op_" + UUID.randomUUID().toString().replace("-", "");
    private String documentId;

    @BeforeEach
    void setUp() {
        role(author, "OWNER");
        role(member, "MEMBER");
        documentId = documents.createMarkdown(workspace, author, UUID.randomUUID().toString(),
                new MarkdownDocumentCreateRequest("회의록", BASE, null)).id();
    }

    @Test
    void onCurrentVersionSavesAndAuditsAsAgentEdit() throws Exception {
        apply(BASE.replace("끝 문단", "끝 문단(AI)"), "write-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_version").value(2))
                .andExpect(jsonPath("$.merge.status").value("none"));

        assertThat(jdbc.queryForMap("SELECT source, operation_id FROM document_content_versions WHERE document_id = ? AND version = 2",
                documentId)).containsEntry("source", "agent").containsEntry("operation_id", operationId);
        assertThat(operationStatus()).isEqualTo("succeeded");
    }

    @Test
    void staleBaseWithoutOverlapIsMerged() throws Exception {
        authorSaves(SERVER);
        String merged = SERVER.replace("끝 문단", "끝 문단(AI)");

        apply(BASE.replace("끝 문단", "끝 문단(AI)"), "write-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_version").value(3))
                .andExpect(jsonPath("$.merge.status").value("merged"))
                .andExpect(jsonPath("$.markdown").value(merged));

        assertThat(markdown()).isEqualTo(merged);
        assertThat(operationStatus()).isEqualTo("succeeded");
        assertThat(projectionStatus()).isEqualTo("consumed");
    }

    @Test
    void sameIdempotencyKeyAfterMergeReplaysFirstResult() throws Exception {
        authorSaves(SERVER);
        String body = BASE.replace("끝 문단", "끝 문단(AI)");
        apply(body, "write-1").andExpect(status().isOk());

        apply(body, "write-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_version").value(3));

        assertThat(revision()).isEqualTo(3L);
    }

    /** 사용자가 보지 않는 문서다. 겹치면 사람이 쓴 내용을 남기고 작업을 충돌로 돌려보낸다. */
    @Test
    void overlapReturnsConflictAndLeavesDocumentUntouched(CapturedOutput output) throws Exception {
        authorSaves(SERVER);
        String body = BASE.replace("첫 문단", "첫 문단(" + AGENT_MARKER + ")");

        apply(body, "write-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.current_revision").value(2));

        assertThat(markdown()).isEqualTo(SERVER);
        assertThat(revision()).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_content_versions WHERE document_id = ? AND version > 2",
                Integer.class, documentId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_edit_writes WHERE document_id = ? AND revision_write_id = 'write-1'",
                Integer.class, documentId)).isZero();
        // 적용 표는 소비되고 감사 로그는 conflict로 닫힌다.
        assertThat(projectionStatus()).isEqualTo("consumed");
        assertThat(operationStatus()).isEqualTo("conflict");
        assertThat(output.getAll()).doesNotContain(AGENT_MARKER).doesNotContain("작성자 표식");
    }

    @Test
    void retryAfterConflictIsStillConflictWithSameState() throws Exception {
        authorSaves(SERVER);
        String body = BASE.replace("첫 문단", "첫 문단(AI)");
        apply(body, "write-1").andExpect(status().isConflict());

        apply(body, "write-1").andExpect(status().isConflict());

        assertThat(markdown()).isEqualTo(SERVER);
        assertThat(projectionStatus()).isEqualTo("consumed");
        assertThat(operationStatus()).isEqualTo("conflict");
    }

    @Test
    void missingBaseSnapshotIsConflictNotOverwrite() throws Exception {
        authorSaves(SERVER);
        jdbc.update("DELETE FROM document_content_versions WHERE document_id = ? AND version = 1", documentId);

        apply(BASE.replace("끝 문단", "끝 문단(AI)"), "write-1").andExpect(status().isConflict());

        assertThat(markdown()).isEqualTo(SERVER);
    }

    @Test
    void lockedByAnotherUserIsLockedAndTouchesNothing() throws Exception {
        locks.acquire(workspace, author, documentId);

        apply(BASE.replace("끝 문단", "끝 문단(AI)"), "write-1")
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_EDIT_LOCKED"));

        assertThat(markdown()).isEqualTo(BASE);
        assertThat(projectionStatus()).isEqualTo("ready");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_operation_logs WHERE operation_id = ?",
                Integer.class, operationId)).isZero();
    }

    @Test
    void revokedEditAccessIsForbidden() throws Exception {
        jdbc.update("INSERT INTO document_permissions(workspace_id, document_id, access, updated_by) VALUES (?, ?, 'view', ?)",
                workspace, documentId, author);

        apply(BASE.replace("끝 문단", "끝 문단(AI)"), "write-1").andExpect(status().isForbidden());

        assertThat(markdown()).isEqualTo(BASE);
    }

    @Test
    void deletedDocumentIsNotFound() throws Exception {
        jdbc.update("UPDATE documents SET deleted_at = now() WHERE id = ?", documentId);

        apply(BASE.replace("끝 문단", "끝 문단(AI)"), "write-1").andExpect(status().isNotFound());
    }

    /** 병합 전용은 이 도구 경로만이다. 수동 수락의 저장은 겹쳐도 지금처럼 덮어쓰고 이력에 남긴다(ADR 0032). */
    @Test
    void manualAgentAcceptStillOverwritesOnOverlap() {
        authorSaves(SERVER);
        String body = BASE.replace("첫 문단", "첫 문단(AI)");
        runCommands.prepareToolApply("run_" + UUID.randomUUID(), workspace, member, documentId, 1, operationId, body);

        DocumentContentSaveResponse saved = documents.saveContent(
                workspace, member, documentId, body, 1L, "write-1", "agent", operationId);

        assertThat(saved.merge().status()).isEqualTo("overwritten");
        assertThat(markdown()).isEqualTo(body);
    }

    @Test
    void plainUserSaveStillOverwritesOnOverlap() {
        authorSaves(SERVER);
        String body = BASE.replace("첫 문단", "첫 문단(사용자)");

        DocumentContentSaveResponse saved = documents.saveContent(workspace, member, documentId, body, 1L, "write-1", null);

        assertThat(saved.merge().status()).isEqualTo("overwritten");
        assertThat(markdown()).isEqualTo(body);
    }

    private ResultActions apply(String agentBody, String idempotencyKey) throws Exception {
        JsonNode target = mapper.valueToTree(Map.of("type", "whole_document", "start_line", 1, "end_line", 7));
        when(artifacts.resolve(any(), eq("apply_document_edit"), any())).thenReturn(
                new PipelineAgentArtifactClient.ResolvedArtifact("artifact-1", "sha256:abc", "apply_document_edit",
                        documentId, 1L, target, agentBody));
        return mockMvc.perform(post("/internal/agent/tools/execute/apply_document_edit")
                .header("X-Agent-Service-Token", serviceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "run_id", runId, "workspace_id", workspace, "user_id", member, "plan_id", "plan-1",
                        "plan_version", 1, "operation_hash", "a".repeat(64), "operation_id", operationId,
                        "idempotency_key", idempotencyKey,
                        "arguments", Map.of("document_id", documentId, "base_version", 1, "target", target,
                                "content_artifact_id", "artifact-1", "content_hash", "sha256:abc")))));
    }

    private void authorSaves(String markdown) {
        documents.saveContent(workspace, author, documentId, markdown, 1L, "write_" + UUID.randomUUID(), null);
    }

    private String markdown() {
        return jdbc.queryForObject("SELECT markdown FROM document_edit_states WHERE document_id = ?", String.class, documentId);
    }

    private Long revision() {
        return jdbc.queryForObject("SELECT revision FROM document_edit_states WHERE document_id = ?", Long.class, documentId);
    }

    private String operationStatus() {
        return jdbc.queryForObject("SELECT status FROM ai_operation_logs WHERE operation_id = ?", String.class, operationId);
    }

    private String projectionStatus() {
        return jdbc.queryForObject("SELECT status FROM agent_apply_projections WHERE apply_operation_id = ?", String.class, operationId);
    }

    private void role(String userId, String role) {
        redis.opsForValue().set("authz:role:v2:" + workspace + ":" + userId, "{\"role\":\"" + role + "\",\"permissions\":[]}");
    }
}
