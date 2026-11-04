package fruition.core.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.agent.dto.AgentAutoApprovalSettings;
import fruition.core.agent.repository.AgentAutoApprovalSettingsRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.domain.Document;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.DocumentEditTargets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentAutoApprovalServiceTest {

    @Mock AgentAutoApprovalSettingsRepository settingsRepository;
    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock DocumentAccessPolicy documentAccessPolicy;
    @Mock DocumentRepository documentRepository;
    @Mock FolderRepository folderRepository;

    private AgentAutoApprovalService service() {
        return new AgentAutoApprovalService(settingsRepository, workspaceAccessGuard, documentAccessPolicy,
                new DocumentEditTargets(documentRepository, folderRepository));
    }

    private static JsonNode renamePlan() throws Exception {
        return new ObjectMapper().readTree("""
                {"id":"run-1","status":"awaiting_approval","plan":{"version":1,"operation_hash":"h",
                 "status":"awaiting_approval","operations":[{"id":"op","tool_name":"rename_document",
                 "target_id":"doc_1","arguments":{"document_id":"doc_1","display_name":"새 이름","base_version":2}}]}}
                """);
    }

    private static DocumentAccessPolicy.Viewer viewer(Map<String, String> documentAccess) {
        return new DocumentAccessPolicy.Viewer("user_1", false, false, documentAccess, Map.of(), Map.of());
    }

    private Document documentOwnedBySomeoneElse() {
        Document document = mock(Document.class);
        when(document.getId()).thenReturn("doc_1");
        when(document.getUserId()).thenReturn("owner_2");
        return document;
    }

    @Test
    void settingIsReadAtDecisionTime() throws Exception {
        when(settingsRepository.find("user_1"))
                .thenReturn(new AgentAutoApprovalSettings(false, true), AgentAutoApprovalSettings.DISABLED);
        when(documentAccessPolicy.viewer("ws_1", "user_1")).thenReturn(viewer(Map.of()));
        Document document = documentOwnedBySomeoneElse();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));

        assertThat(service().decide("ws_1", "user_1", renamePlan()).autoApprove()).isTrue();
        // 계획이 만들어진 뒤 설정을 끄면 다음 판단부터 수동이다.
        assertThat(service().decide("ws_1", "user_1", renamePlan()).autoApprove()).isFalse();
    }

    @Test
    void userWithViewOnlyAccessIsNotAutoApproved() throws Exception {
        when(settingsRepository.find("user_1")).thenReturn(new AgentAutoApprovalSettings(true, true));
        when(documentAccessPolicy.viewer("ws_1", "user_1"))
                .thenReturn(viewer(Map.of("doc_1", DocumentAccessPolicy.VIEW)));
        Document document = documentOwnedBySomeoneElse();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));

        var decision = service().decide("ws_1", "user_1", renamePlan());

        assertThat(decision.autoApprove()).isFalse();
        assertThat(decision.reason()).isEqualTo("forbidden");
    }

    /** 자기 문서라 편집할 수 있어도 보기 폴더에서는 꺼낼 수 없다. 실행 때도 같은 이유로 403이다. */
    @Test
    void ownerMovingDocumentOutOfViewFolderIsNotAutoApproved() throws Exception {
        java.util.UUID viewFolder = java.util.UUID.randomUUID();
        when(settingsRepository.find("user_1")).thenReturn(new AgentAutoApprovalSettings(true, true));
        when(documentAccessPolicy.viewer("ws_1", "user_1")).thenReturn(new DocumentAccessPolicy.Viewer(
                "user_1", false, false, Map.of(), Map.of(viewFolder, DocumentAccessPolicy.VIEW), Map.of()));
        Document document = mock(Document.class);
        when(document.getUserId()).thenReturn("user_1");
        when(document.getFolderId()).thenReturn(viewFolder);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));
        when(folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(viewFolder, "ws_1"))
                .thenReturn(Optional.of(mock(fruition.core.document.domain.Folder.class)));

        var decision = service().decide("ws_1", "user_1", new ObjectMapper().readTree("""
                {"id":"run-1","status":"awaiting_approval","plan":{"version":1,"operation_hash":"h",
                 "status":"awaiting_approval","operations":[{"id":"op","tool_name":"move_document","target_id":"doc_1",
                 "arguments":{"document_id":"doc_1","folder_id":null,"position":null,"base_version":2}}]}}
                """));

        assertThat(decision.autoApprove()).isFalse();
        assertThat(decision.reason()).isEqualTo("forbidden");
    }

    @Test
    void missingOrDeletedTargetIsNotAutoApproved() throws Exception {
        when(settingsRepository.find("user_1")).thenReturn(new AgentAutoApprovalSettings(true, true));
        when(documentAccessPolicy.viewer("ws_1", "user_1")).thenReturn(viewer(Map.of()));
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.empty());

        assertThat(service().decide("ws_1", "user_1", renamePlan()).autoApprove()).isFalse();
    }

    @Test
    void disabledSettingsDoNotReadPermissions() throws Exception {
        when(settingsRepository.find("user_1")).thenReturn(AgentAutoApprovalSettings.DISABLED);

        assertThat(service().decide("ws_1", "user_1", renamePlan()).reason()).isEqualTo("disabled");
        verifyNoInteractions(documentAccessPolicy, documentRepository, folderRepository);
    }

    @Test
    void settingsRequireWorkspaceMembership() {
        AgentAutoApprovalSettings settings = new AgentAutoApprovalSettings(true, false);
        when(settingsRepository.find("user_1")).thenReturn(settings);

        assertThat(service().update("ws_1", "user_1", settings)).isEqualTo(settings);
        assertThat(service().get("ws_1", "user_1")).isEqualTo(settings);

        verify(settingsRepository).save("user_1", settings);
        verify(workspaceAccessGuard, org.mockito.Mockito.times(2)).requireMember("ws_1", "user_1");
    }
}
