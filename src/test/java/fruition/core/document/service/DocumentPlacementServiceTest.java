package fruition.core.document.service;

import fruition.shared.idempotency.IdempotencyService;
import fruition.core.document.domain.Document;
import fruition.core.document.dto.DocumentPositionRequest;
import fruition.core.document.dto.DocumentPositionResponse;
import fruition.core.document.exception.HierarchyItemNotFoundException;
import fruition.core.document.exception.HierarchyVersionConflictException;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.authz.WorkspaceAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class DocumentPlacementServiceTest {

    private static final String WORKSPACE_ID = "ws_1";
    private static final String USER_ID = "user_1";
    private static final String DOCUMENT_ID = "doc_1";

    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock DocumentRepository documentRepository;
    @Mock FolderRepository folderRepository;
    @Mock IdempotencyService idempotencyService;
    @Mock SiblingReorderer siblingReorderer;

    private DocumentPlacementService service;

    @BeforeEach
    void setUp() {
        service = new DocumentPlacementService(
                workspaceAccessGuard,
                documentRepository, folderRepository, idempotencyService, siblingReorderer);
        lenient().when(idempotencyService.execute(
                any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(7).get());
    }

    private Document document() {
        return new Document(DOCUMENT_ID, WORKSPACE_ID, USER_ID, "메모.md", "text/markdown", 10, null, null, "direct");
    }

    private void memberOk() {
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
    }

    private void noReplay() {
    }

    @Test
    void move_rejectsNonMember() {
        doThrow(new WorkspaceNotFoundException(WORKSPACE_ID))
                .when(workspaceAccessGuard).requireMember(WORKSPACE_ID, "intruder");
        assertThatThrownBy(() -> service.move(WORKSPACE_ID, "intruder", DOCUMENT_ID, "k1",
                new DocumentPositionRequest(null, null, 1L)))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void move_rejectsMissingDocument() {
        memberOk();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.move(WORKSPACE_ID, USER_ID, DOCUMENT_ID, "k1",
                new DocumentPositionRequest(null, null, 1L)))
                .isInstanceOf(HierarchyItemNotFoundException.class);
    }

    @Test
    void move_rejectsMissingTargetFolder() {
        memberOk();
        UUID targetFolderId = UUID.randomUUID();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(document()));
        when(folderRepository.findActiveForUpdate(targetFolderId, WORKSPACE_ID))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.move(WORKSPACE_ID, USER_ID, DOCUMENT_ID, "k1",
                new DocumentPositionRequest(targetFolderId, null, 1L)))
                .isInstanceOf(HierarchyItemNotFoundException.class);
    }

    @Test
    void move_conflictsOnStaleVersion() {
        memberOk();
        noReplay();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(document()));
        when(siblingReorderer.placeDocument(WORKSPACE_ID, null, DOCUMENT_ID, null)).thenReturn(0L);
        when(documentRepository.moveIfVersionMatches(eq(DOCUMENT_ID), eq(WORKSPACE_ID), eq(9L), eq(null),
                anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.move(WORKSPACE_ID, USER_ID, DOCUMENT_ID, "k1",
                new DocumentPositionRequest(null, null, 9L)))
                .isInstanceOf(HierarchyVersionConflictException.class);
    }

    @Test
    void move_placesAtReorderedPositionInTargetFolder() {
        memberOk();
        noReplay();
        UUID targetFolderId = UUID.randomUUID();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(document()));
        when(folderRepository.findActiveForUpdate(targetFolderId, WORKSPACE_ID))
                .thenReturn(Optional.of(new fruition.core.document.domain.Folder(
                        targetFolderId, WORKSPACE_ID, null, "대상", 0)));
        when(siblingReorderer.placeDocument(WORKSPACE_ID, targetFolderId, DOCUMENT_ID, 1)).thenReturn(1L);
        when(documentRepository.moveIfVersionMatches(eq(DOCUMENT_ID), eq(WORKSPACE_ID), eq(1L),
                eq(targetFolderId), eq(1L), any())).thenReturn(1);

        service.move(WORKSPACE_ID, USER_ID, DOCUMENT_ID, "k1",
                new DocumentPositionRequest(targetFolderId, 1, 1L));

        verify(documentRepository).moveIfVersionMatches(eq(DOCUMENT_ID), eq(WORKSPACE_ID), eq(1L),
                eq(targetFolderId), eq(1L), any());
        verify(idempotencyService).execute(
                eq(USER_ID), any(), eq("k1"), any(), eq(DocumentPositionResponse.class),
                eq(200), any(), any());
    }

    @Test
    void move_returnsReplayWithoutUpdating() {
        DocumentPositionResponse stored = new DocumentPositionResponse(DOCUMENT_ID, null, 3, 2);
        doReturn(stored).when(idempotencyService).execute(
                any(), any(), any(), any(), eq(DocumentPositionResponse.class),
                anyInt(), any(), any());

        service.move(WORKSPACE_ID, USER_ID, DOCUMENT_ID, "k1", new DocumentPositionRequest(null, null, 1L));

        verify(documentRepository, never()).moveIfVersionMatches(any(), any(), anyLong(), any(), anyLong(), any());
    }
}
