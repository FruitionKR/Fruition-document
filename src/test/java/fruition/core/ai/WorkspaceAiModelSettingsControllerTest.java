package fruition.core.ai;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.shared.ai.AiModelCatalog;
import fruition.shared.ai.InvalidAiModelException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceAiModelSettingsControllerTest {
    @Mock WorkspaceAccessGuard accessGuard;
    @Mock WorkspaceAiModelClient client;

    private WorkspaceAiModelSettingsController controller;

    @BeforeEach
    void setUp() {
        controller = new WorkspaceAiModelSettingsController(
                accessGuard, client, new AiModelCatalog("openai,gemini,claude"));
    }

    @Test
    void get_memberCanReadSettingButCannotUpdate() {
        when(client.get("ws_1")).thenReturn(
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"));
        when(accessGuard.isOwner("ws_1", "user_1")).thenReturn(false);

        var response = controller.get("user_1", "ws_1");

        verify(accessGuard).requireMember("ws_1", "user_1");
        assertThat(response.getBody().ingestLint().model()).isEqualTo("gpt-6-luna");
        assertThat(response.getBody().canUpdate()).isFalse();
    }

    @Test
    void get_ownerCanUpdate() {
        when(client.get("ws_1")).thenReturn(
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"));
        when(accessGuard.isOwner("ws_1", "owner_1")).thenReturn(true);

        var response = controller.get("owner_1", "ws_1");

        assertThat(response.getBody().canUpdate()).isTrue();
    }

    @Test
    void update_ownerCanChangeSetting() {
        when(accessGuard.getRole("ws_1", "owner_1")).thenReturn("OWNER");
        when(client.update("ws_1", "openai", "gpt-6-luna")).thenReturn(
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"));

        var response = controller.update("owner_1", "ws_1",
                new WorkspaceAiModelSettingsController.SettingsRequest(
                        new WorkspaceAiModelSettingsController.AiModelSelection("openai", "gpt-6-luna")));

        assertThat(response.getBody().ingestLint().provider()).isEqualTo("openai");
        assertThat(response.getBody().canUpdate()).isTrue();
    }

    // ingest_lint는 ingest·lint·스킬·회의록·변환 repair가 함께 쓰는 공용 설정이라 Claude는 저장하지 않는다(저가 모델 정책).
    @Test
    void update_rejectsClaudeWithoutCallingAccess() {
        when(accessGuard.getRole("ws_1", "owner_1")).thenReturn("OWNER");

        assertThatThrownBy(() -> controller.update("owner_1", "ws_1",
                new WorkspaceAiModelSettingsController.SettingsRequest(
                        new WorkspaceAiModelSettingsController.AiModelSelection(
                                "claude", "claude-haiku-4-5-20251001"))))
                .isInstanceOf(InvalidAiModelException.class)
                .hasMessage("Claude 모델은 채팅에서 직접 선택할 때만 사용할 수 있습니다.");
        verifyNoInteractions(client);
    }

    @Test
    void update_rejectsRemovedModel() {
        when(accessGuard.getRole("ws_1", "owner_1")).thenReturn("OWNER");

        assertThatThrownBy(() -> controller.update("owner_1", "ws_1",
                new WorkspaceAiModelSettingsController.SettingsRequest(
                        new WorkspaceAiModelSettingsController.AiModelSelection("gemini", "gemini-3.8-flash"))))
                .isInstanceOf(InvalidAiModelException.class);
        verifyNoInteractions(client);
    }

    @Test
    void update_memberIsForbiddenAndNonMemberIsHidden() {
        when(accessGuard.getRole("ws_1", "member_1")).thenReturn("MEMBER");
        when(accessGuard.getRole("ws_1", "other_1")).thenReturn("NONE");

        var request = new WorkspaceAiModelSettingsController.SettingsRequest(
                new WorkspaceAiModelSettingsController.AiModelSelection("openai", "gpt-6-luna"));
        assertThatThrownBy(() -> controller.update("member_1", "ws_1", request))
                .isInstanceOf(WorkspaceAiModelForbiddenException.class);
        assertThatThrownBy(() -> controller.update("other_1", "ws_1", request))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }
}
