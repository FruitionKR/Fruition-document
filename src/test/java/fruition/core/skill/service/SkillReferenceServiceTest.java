package fruition.core.skill.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.service.DocumentEditStateInitializer;
import fruition.core.skill.exception.SkillReferenceDocumentTooLargeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillReferenceServiceTest {

    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock DocumentRepository documentRepository;
    @Mock DocumentEditStateRepository editStateRepository;
    @Mock DocumentEditStateInitializer editStateInitializer;
    @Mock Document document;
    private SkillReferenceService service;

    @BeforeEach
    void setUp() {
        service = new SkillReferenceService(
                workspaceAccessGuard, documentRepository, editStateRepository, editStateInitializer);
    }

    @Test
    void read_returnsLatestPostgresMarkdown() {
        when(document.getId()).thenReturn("doc_1");
        when(document.getDocumentRole()).thenReturn(DocumentRole.EDITABLE);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById("doc_1"))
                .thenReturn(Optional.of(new DocumentEditState("doc_1", "# 최신 본문", "hash", 1)));

        var response = service.read("ws_1", "user_1", "doc_1");

        assertThat(response.documentRole()).isEqualTo("EDITABLE");
        assertThat(response.markdown()).isEqualTo("# 최신 본문");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(editStateInitializer, editStateRepository);
        order.verify(editStateInitializer).initializeIfNeeded(document);
        order.verify(editStateRepository).findById("doc_1");
    }

    @Test
    void read_rejectsEditableCanonicalMarkdownOverThirtyThousandCharacters() {
        when(document.getId()).thenReturn("doc_1");
        when(document.getDocumentRole()).thenReturn(DocumentRole.EDITABLE);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById("doc_1"))
                .thenReturn(Optional.of(new DocumentEditState("doc_1", "가".repeat(30001), "hash", 1)));

        assertThatThrownBy(() -> service.read("ws_1", "user_1", "doc_1"))
                .isInstanceOf(SkillReferenceDocumentTooLargeException.class);
    }

    @Test
    void read_returnsOriginalRoleWithoutReadingCoreMarkdown() {
        when(document.getDocumentRole()).thenReturn(DocumentRole.ORIGINAL);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));

        var response = service.read("ws_1", "user_1", "doc_1");

        assertThat(response.documentRole()).isEqualTo("ORIGINAL");
        assertThat(response.markdown()).isNull();
        verifyNoInteractions(editStateInitializer, editStateRepository);
    }

    @Test
    void read_usesDocumentWorkspaceBoundaryBeforeStateLookup() {
        when(document.getId()).thenReturn("doc_1");
        when(document.getDocumentRole()).thenReturn(DocumentRole.EDITABLE);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById("doc_1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.read("ws_1", "user_1", "doc_1"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void read_failsClosedBeforeDocumentAccess() {
        doThrow(new WorkspaceNotFoundException("ws_1"))
                .when(workspaceAccessGuard).requireMember("ws_1", "user_2");

        assertThatThrownBy(() -> service.read("ws_1", "user_2", "doc_1"))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verifyNoInteractions(documentRepository, editStateRepository);
    }
}
