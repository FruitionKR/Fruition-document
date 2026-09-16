package fruition.core.skill.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.authz.WorkspaceNotFoundException;
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
                .thenReturn(new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-5-nano"));
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
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-5-nano"), "run");
    }

    @Test
    void publish_andUpdate_forwardWorkspaceModelSnapshot() {
        var publish = new SkillPublishRequest("team", "meeting-notes", "회의록 작성", "# 작성 절차",
                List.of("document-create"),
                List.of("list_root_items", "list_folder_children", "create_document"));
        var update = new SkillUpdateRequest("meeting-notes", "회의록 수정", "# 수정 절차");

        service.publish("ws_1", "user_1", publish, "run");
        service.update("ws_1", "user_1", "skill_1", update, "run");

        verify(requester).publish("ws_1", "user_1", publish,
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-5-nano"), "run");
        verify(requester).update("ws_1", "user_1", "skill_1", update,
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-5-nano"), "run");
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
}
