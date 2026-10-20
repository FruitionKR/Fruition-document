package fruition.core.skill.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.domain.Folder;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.DocumentEditStateInitializer;
import fruition.core.skill.exception.SkillFolderSourceForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 범위·정렬·DB 조회는 SkillFolderSourceIntegrationTest가 본다. 여기서는 권한 판정 분기를 본다. */
@ExtendWith(MockitoExtension.class)
class SkillFolderSourceServiceTest {

    private static final UUID FOLDER = UUID.randomUUID();

    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock DocumentAccessPolicy documentAccessPolicy;
    @Mock FolderRepository folderRepository;
    @Mock DocumentRepository documentRepository;
    @Mock DocumentEditStateRepository editStateRepository;
    @Mock DocumentEditStateInitializer editStateInitializer;
    private SkillFolderSourceService service;

    @BeforeEach
    void setUp() {
        service = new SkillFolderSourceService(workspaceAccessGuard, documentAccessPolicy, folderRepository,
                documentRepository, editStateRepository, editStateInitializer);
    }

    @Test
    void read_excludesDocumentsTheViewerCannotRead() {
        when(folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(FOLDER, "ws_1"))
                .thenReturn(Optional.of(mock(Folder.class)));
        Document editable = document("doc_edit", Instant.parse("2026-01-01T00:00:00Z"));
        Document viewOnly = document("doc_view", Instant.parse("2026-01-02T00:00:00Z"));
        Document hidden = document("doc_hidden", Instant.parse("2026-01-03T00:00:00Z"));
        when(documentRepository.findChildDocuments("ws_1", FOLDER)).thenReturn(List.of(editable, viewOnly, hidden));
        // DB가 아직 허용하지 않는 값이라도 보기·편집이 아니면 읽지 못한다(fail-closed).
        when(documentAccessPolicy.viewer("ws_1", "user_1")).thenReturn(new DocumentAccessPolicy.Viewer(
                "user_1", false, false, Map.of("doc_view", "view", "doc_hidden", "none"), Map.of(), Map.of()));
        lenient().when(editStateRepository.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> Optional.of(new DocumentEditState(call.getArgument(0), "# 본문", "hash", 1)));

        var response = service.read("ws_1", "user_1", FOLDER.toString(), 20);

        assertThat(response.totalDocuments()).isEqualTo(2);
        assertThat(response.items()).extracting(item -> item.documentId()).containsExactly("doc_view", "doc_edit");
    }

    @Test
    void read_rejectsFolderTheViewerCannotRead() {
        when(folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(FOLDER, "ws_1"))
                .thenReturn(Optional.of(mock(Folder.class)));
        when(documentAccessPolicy.viewer("ws_1", "user_1")).thenReturn(new DocumentAccessPolicy.Viewer(
                "user_1", false, false, Map.of(), Map.of(FOLDER, "none"), Map.of()));

        assertThatThrownBy(() -> service.read("ws_1", "user_1", FOLDER.toString(), 20))
                .isInstanceOf(SkillFolderSourceForbiddenException.class);
        verifyNoInteractions(documentRepository);
    }

    @Test
    void read_rejectsNonMemberBeforeLookingUpFolder() {
        doThrow(new WorkspaceNotFoundException("ws_1")).when(workspaceAccessGuard).requireMember("ws_1", "user_1");

        assertThatThrownBy(() -> service.read("ws_1", "user_1", FOLDER.toString(), 20))
                .isInstanceOf(SkillFolderSourceForbiddenException.class);
        verifyNoInteractions(folderRepository, documentRepository);
    }

    @Test
    void read_returnsNullMarkdownWhenEditStateCannotBeBuilt() {
        when(folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(FOLDER, "ws_1"))
                .thenReturn(Optional.of(mock(Folder.class)));
        Document legacy = document("doc_legacy", Instant.parse("2026-01-01T00:00:00Z"));
        when(documentRepository.findChildDocuments("ws_1", FOLDER)).thenReturn(List.of(legacy));
        when(documentAccessPolicy.viewer("ws_1", "user_1")).thenReturn(new DocumentAccessPolicy.Viewer(
                "user_1", false, false, Map.of(), Map.of(), Map.of()));
        doThrow(new RuntimeException("storage down")).when(editStateInitializer).initializeIfNeeded(legacy);

        var response = service.read("ws_1", "user_1", FOLDER.toString(), 20);

        assertThat(response.items()).singleElement().satisfies(item -> assertThat(item.markdown()).isNull());
    }

    private static Document document(String id, Instant updatedAt) {
        Document document = mock(Document.class);
        lenient().when(document.getId()).thenReturn(id);
        lenient().when(document.getUserId()).thenReturn("owner_1");
        lenient().when(document.getFolderId()).thenReturn(FOLDER);
        lenient().when(document.getDocumentRole()).thenReturn(DocumentRole.EDITABLE);
        lenient().when(document.getUpdatedAt()).thenReturn(updatedAt);
        lenient().when(document.getDisplayName()).thenReturn("문서 " + id);
        return document;
    }
}
