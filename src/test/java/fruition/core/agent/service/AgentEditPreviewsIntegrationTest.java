package fruition.core.agent.service;

import fruition.TestcontainersConfiguration;
import fruition.core.agent.dto.AgentRunEditPreviewsResponse;
import fruition.core.agent.exception.AgentRunNotFoundException;
import fruition.core.agent.repository.PipelineAgentRunStatusRequester;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 편집 미리 보기 프록시가 문서마다 지금 권한을 다시 확인하고 제목을 현재 값으로 덮는지 실제 DB로 본다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class AgentEditPreviewsIntegrationTest {

    private static final String RUN = "0b1f6c1e-5b0e-4b53-9a67-3f2f0d8e9a11";
    private static final String BODY_MARKER = "미리보기본문표식";

    @Autowired AgentTurnService turns;
    @Autowired DocumentService documents;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @MockBean PipelineAgentRunStatusRequester aiRuns;
    @SpyBean DocumentAccessPolicy accessPolicy;
    @SpyBean WorkspaceAccessGuard accessGuard;

    private final String workspace = "ws_" + UUID.randomUUID();
    private final String user = "user_" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        redis.opsForValue().set("authz:role:v2:" + workspace + ":" + user, "{\"role\":\"MEMBER\",\"permissions\":[]}");
    }

    @Test
    void overwritesTitleWithCurrentValueAndKeepsTheRestOfPreview() {
        String document = document("현재 제목");
        aiReturns("2026-11-05T00:00:00Z", item("op-1", document));

        var previews = turns.getEditPreviews(workspace, user, RUN);

        assertThat(previews.expiresAt()).isEqualTo("2026-11-05T00:00:00Z");
        assertThat(previews.items()).hasSize(1);
        var item = previews.items().getFirst();
        assertThat(item.displayName()).isEqualTo("현재 제목");
        assertThat(item.operationId()).isEqualTo("op-1");
        assertThat(item.documentId()).isEqualTo(document);
        assertThat(item.baseVersion()).isEqualTo(1);
        assertThat(item.summary()).isEqualTo("요약");
        assertThat(item.addedLines()).isEqualTo(1);
        assertThat(item.removedLines()).isEqualTo(2);
        assertThat(item.hunks().getFirst().lines().getFirst().text()).isEqualTo(BODY_MARKER);
    }

    @Test
    void removesDocumentUserCanNoLongerRead(CapturedOutput output) {
        String readable = document("읽을 수 있음");
        String blocked = colleagueDocument("읽을 수 없음");
        doReturn(new DocumentAccessPolicy.Viewer(user, false, false, Map.of(blocked, "none"), Map.of(), Map.of()))
                .when(accessPolicy).viewer(workspace, user);
        aiReturns(null, item("op-1", readable), item("op-2", blocked));

        var previews = turns.getEditPreviews(workspace, user, RUN);

        assertThat(previews.items()).extracting(AgentRunEditPreviewsResponse.Item::documentId).containsExactly(readable);
        assertThat(previews.expiresAt()).isNull();
        assertThat(output.getAll()).doesNotContain(BODY_MARKER);
    }

    /** 보기 권한이면 미리 보기는 읽을 수 있다. 저장은 승인 뒤 편집 권한으로 다시 막힌다. */
    @Test
    void keepsViewOnlyDocument() {
        String document = colleagueDocument("보기 전용");
        jdbc.update("INSERT INTO document_permissions(workspace_id, document_id, access, updated_by) VALUES (?, ?, 'view', ?)",
                workspace, document, "someone");
        aiReturns(null, item("op-1", document));

        assertThat(turns.getEditPreviews(workspace, user, RUN).items()).hasSize(1);
    }

    @Test
    void removesDeletedDocumentMissingDocumentAndDocumentOfAnotherWorkspace() {
        String deleted = document("삭제됨");
        jdbc.update("UPDATE documents SET deleted_at = now() WHERE id = ?", deleted);
        String otherWorkspace = "ws_" + UUID.randomUUID();
        redis.opsForValue().set("authz:role:v2:" + otherWorkspace + ":" + user, "{\"role\":\"OWNER\",\"permissions\":[]}");
        String foreign = documents.createMarkdown(otherWorkspace, user, UUID.randomUUID().toString(),
                new MarkdownDocumentCreateRequest("다른 워크스페이스", "# 본문", null)).id();
        aiReturns(null, item("op-1", deleted), item("op-2", "doc_missing"), item("op-3", foreign), item("op-4", null));

        assertThat(turns.getEditPreviews(workspace, user, RUN).items()).isEmpty();
    }

    @Test
    void nonMemberIsRejectedBeforeCallingAi() {
        doThrow(new WorkspaceNotFoundException(workspace)).when(accessGuard).requireMember(workspace, user);

        assertThatThrownBy(() -> turns.getEditPreviews(workspace, user, RUN))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verifyNoInteractions(aiRuns);
    }

    /** run 소유는 AI가 user_id로 가른다. 요청자 ID를 그대로 넘기고 AI의 404를 그대로 돌려준다. */
    @Test
    void runOfAnotherUserStaysNotFound() {
        when(aiRuns.getEditPreviews(workspace, user, RUN)).thenThrow(new AgentRunNotFoundException(RUN));

        assertThatThrownBy(() -> turns.getEditPreviews(workspace, user, RUN))
                .isInstanceOf(AgentRunNotFoundException.class);
    }

    @Test
    void emptyAiResponseStaysEmpty() {
        when(aiRuns.getEditPreviews(workspace, user, RUN)).thenReturn(new AgentRunEditPreviewsResponse(null, null));

        assertThat(turns.getEditPreviews(workspace, user, RUN).items()).isEmpty();
    }

    @Test
    void keepsOwnedCurrentPlanCreatePreviewWithoutInventingDocumentIdentity() {
        var create = new AgentRunEditPreviewsResponse.Item("op-create", "create_document", null,
                "새 문서", null, "생성 요약", 2, 0,
                List.of(new AgentRunEditPreviewsResponse.Hunk(0, 1,
                        List.of(new AgentRunEditPreviewsResponse.Line("insert", BODY_MARKER)))));
        aiReturns(null, create);

        var item = turns.getEditPreviews(workspace, user, RUN).items().getFirst();

        assertThat(item.toolName()).isEqualTo("create_document");
        assertThat(item.documentId()).isNull();
        assertThat(item.baseVersion()).isNull();
        assertThat(item.displayName()).isEqualTo("새 문서");
        assertThat(item.removedLines()).isZero();
    }

    private String document(String title) {
        return documents.createMarkdown(workspace, user, UUID.randomUUID().toString(),
                new MarkdownDocumentCreateRequest(title, "# 본문", null)).id();
    }

    /** 소유자는 권한 설정과 관계없이 읽으므로 권한을 보려면 다른 사람의 문서여야 한다. */
    private String colleagueDocument(String title) {
        String colleague = "user_" + UUID.randomUUID();
        redis.opsForValue().set("authz:role:v2:" + workspace + ":" + colleague, "{\"role\":\"MEMBER\",\"permissions\":[]}");
        return documents.createMarkdown(workspace, colleague, UUID.randomUUID().toString(),
                new MarkdownDocumentCreateRequest(title, "# 본문", null)).id();
    }

    private void aiReturns(String expiresAt, AgentRunEditPreviewsResponse.Item... items) {
        when(aiRuns.getEditPreviews(workspace, user, RUN))
                .thenReturn(new AgentRunEditPreviewsResponse(Arrays.asList(items), expiresAt));
    }

    private static AgentRunEditPreviewsResponse.Item item(String operationId, String documentId) {
        return new AgentRunEditPreviewsResponse.Item(operationId, documentId, "AI가 기억한 옛 제목", 1, "요약", 1, 2,
                List.of(new AgentRunEditPreviewsResponse.Hunk(1, 1,
                        List.of(new AgentRunEditPreviewsResponse.Line("insert", BODY_MARKER)))));
    }
}
