package fruition.core.skill.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceAiModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.authz.WorkspacePermissionRequiredException;
import fruition.core.skill.dto.SkillAuthoringRequest;
import fruition.core.skill.dto.SkillPublishRequest;
import fruition.core.skill.dto.SkillUpdateRequest;
import fruition.core.skill.repository.PipelineSkillRequester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class SkillServiceTest {

    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock WorkspaceAiModelClient workspaceAiModelClient;
    @Mock PipelineSkillRequester requester;

    private SkillService service;

    @BeforeEach
    void setUp() {
        service = new SkillService(workspaceAccessGuard, requester, workspaceAiModelClient);
        lenient().when(workspaceAiModelClient.get("ws_1"))
                .thenReturn(new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"));
    }

    @Test
    void delete_requiresMembershipBeforeForwarding() {
        when(requester.get("ws_1", "user_1", "skill_1")).thenReturn(skillOf("personal"));
        service.delete("ws_1", "user_1", "skill_1");
        var order = org.mockito.Mockito.inOrder(workspaceAccessGuard, requester);
        order.verify(workspaceAccessGuard).requireMember("ws_1", "user_1");
        order.verify(requester).delete("ws_1", "user_1", "skill_1");
    }

    @Test
    void delete_rejectsNonMemberBeforePipelineCall() {
        doThrow(new WorkspaceNotFoundException("ws_1"))
                .when(workspaceAccessGuard).requireMember("ws_1", "user_2");
        assertThatThrownBy(() -> service.delete("ws_1", "user_2", "skill_1"))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verifyNoInteractions(requester);
    }

    @Test
    void author_checksMembershipThenForwardsPathAndPrincipalScope() {
        var request = new SkillAuthoringRequest(
                "personal", "meeting-notes", null,
                "회의록 Skill을 만들어줘", "enhance", List.of("doc_1"));

        service.author("ws_1", "user_1", request, "run");

        verify(workspaceAccessGuard).requireMember("ws_1", "user_1");
        verify(workspaceAiModelClient).get("ws_1");
        verify(requester).author("ws_1", "user_1", request,
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "run");
    }

    @Test
    void publish_andUpdate_forwardWorkspaceModelSnapshot() {
        var publish = new SkillPublishRequest("team", "meeting-notes", "회의록 작성", "# 작성 절차",
                List.of("document-create"),
                List.of("list_root_items", "list_folder_children", "create_document"));
        var update = new SkillUpdateRequest("meeting-notes", "회의록 수정", "# 수정 절차");
        when(requester.get("ws_1", "user_1", "skill_1")).thenReturn(skillOf("team"));

        service.publish("ws_1", "user_1", publish, "run");
        service.update("ws_1", "user_1", "skill_1", update, "run");

        verify(requester).publish("ws_1", "user_1", publish,
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "run");
        verify(requester).update("ws_1", "user_1", "skill_1", update,
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "run");
    }

    @Test
    void author_failsClosedBeforePipelineCall() {
        var request = new SkillAuthoringRequest(
                "personal", "meeting-notes", null,
                "회의록 Skill을 만들어줘", "enhance", List.of());
        doThrow(new WorkspaceNotFoundException("ws_1"))
                .when(workspaceAccessGuard).requireMember("ws_1", "user_2");

        assertThatThrownBy(() -> service.author("ws_1", "user_2", request, "run"))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verifyNoInteractions(requester);
    }

    private static JsonNode skillOf(String scopeType) {
        return new ObjectMapper().createObjectNode().put("scope_type", scopeType);
    }

    private void denySkillAuthor() {
        lenient().doThrow(new WorkspacePermissionRequiredException("skill.author"))
                .when(workspaceAccessGuard).requirePermission("ws_1", "user_1", "skill.author");
    }

    @Test
    void author_team_requiresSkillAuthorPermission() {
        denySkillAuthor();
        var request = new SkillAuthoringRequest("team", "meeting-notes", null, "회의록", "enhance", List.of());

        assertThatThrownBy(() -> service.author("ws_1", "user_1", request, "run"))
                .isInstanceOf(WorkspacePermissionRequiredException.class);
        verifyNoInteractions(requester);
    }

    @Test
    void missingScope_isTreatedAsNonPersonal_andRequiresSkillAuthor() {
        denySkillAuthor();
        var request = new SkillAuthoringRequest(null, "meeting-notes", null, "회의록", "enhance", List.of());
        when(requester.get("ws_1", "user_1", "skill_1")).thenReturn(new ObjectMapper().createObjectNode());

        assertThatThrownBy(() -> service.author("ws_1", "user_1", request, "run"))
                .isInstanceOf(WorkspacePermissionRequiredException.class);
        assertThatThrownBy(() -> service.delete("ws_1", "user_1", "skill_1"))
                .isInstanceOf(WorkspacePermissionRequiredException.class);
        verify(requester, never()).delete(any(), any(), any());
    }

    @Test
    void author_personal_needsNoSkillAuthorPermission() {
        denySkillAuthor();
        var request = new SkillAuthoringRequest("personal", "meeting-notes", null, "회의록", "enhance", List.of());

        service.author("ws_1", "user_1", request, "run");

        verify(requester).author(any(), any(), any(), any(), any());
        verify(workspaceAccessGuard, never()).requirePermission(anyString(), anyString(), anyString());
    }

    @Test
    void publish_team_requiresSkillAuthorPermission() {
        denySkillAuthor();
        var publish = new SkillPublishRequest("team", "meeting-notes", "회의록 작성", "# 작성 절차",
                List.of("document-create"), List.of("list_root_items", "list_folder_children", "create_document"));

        assertThatThrownBy(() -> service.publish("ws_1", "user_1", publish, "run"))
                .isInstanceOf(WorkspacePermissionRequiredException.class);
        verifyNoInteractions(requester);
    }

    @Test
    void updateDeleteEnableDisable_teamSkill_requireSkillAuthorPermission() {
        denySkillAuthor();
        when(requester.get("ws_1", "user_1", "skill_1")).thenReturn(skillOf("team"));
        var update = new SkillUpdateRequest("meeting-notes", "회의록 수정", "# 수정 절차");

        assertThatThrownBy(() -> service.update("ws_1", "user_1", "skill_1", update, "run"))
                .isInstanceOf(WorkspacePermissionRequiredException.class);
        assertThatThrownBy(() -> service.delete("ws_1", "user_1", "skill_1"))
                .isInstanceOf(WorkspacePermissionRequiredException.class);
        assertThatThrownBy(() -> service.setEnabled("ws_1", "user_1", "skill_1", true))
                .isInstanceOf(WorkspacePermissionRequiredException.class);
        assertThatThrownBy(() -> service.setEnabled("ws_1", "user_1", "skill_1", false))
                .isInstanceOf(WorkspacePermissionRequiredException.class);
        verify(requester, never()).update(any(), any(), any(), any(), any(), any());
        verify(requester, never()).delete(any(), any(), any());
        verify(requester, never()).setEnabled(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void updateDeleteEnableDisable_personalSkill_needNoSkillAuthorPermission() {
        denySkillAuthor();
        when(requester.get("ws_1", "user_1", "skill_1")).thenReturn(skillOf("personal"));

        service.delete("ws_1", "user_1", "skill_1");
        service.setEnabled("ws_1", "user_1", "skill_1", true);

        verify(requester).delete("ws_1", "user_1", "skill_1");
        verify(requester).setEnabled("ws_1", "user_1", "skill_1", true);
    }
}
