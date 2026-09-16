package fruition.core.document.service;

import fruition.shared.idempotency.IdempotencyService;
import fruition.core.document.domain.Folder;
import fruition.core.document.dto.FolderCreateRequest;
import fruition.core.document.dto.FolderPositionRequest;
import fruition.core.document.dto.FolderRenameRequest;
import fruition.core.document.dto.FolderResponse;
import fruition.core.document.exception.HierarchyCycleException;
import fruition.core.document.exception.HierarchyVersionConflictException;
import fruition.core.document.exception.InvalidHierarchyRequestException;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
class FolderServiceTest {

    private static final String WORKSPACE_ID = "ws_1";
    private static final String USER_ID = "user_1";

    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock FolderRepository folderRepository;
    @Mock DocumentRepository documentRepository;
    @Mock IdempotencyService idempotencyService;
    @Mock SiblingReorderer siblingReorderer;
    @Mock DocumentItemAssembler documentItemAssembler;

    private FolderService service;

    @BeforeEach
    void setUp() {
        service = new FolderService(workspaceAccessGuard,
                folderRepository, documentRepository,
                idempotencyService, siblingReorderer, documentItemAssembler);
        lenient().when(idempotencyService.execute(
                any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(7).get());
    }

    private void memberOk() {
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
    }

    private void noReplay() {
    }

    @Test
    void create_appendsAtEndOfParentMixedOrder() {
        memberOk();
        noReplay();
        when(folderRepository.findMaxSortOrder(WORKSPACE_ID, null)).thenReturn(1L);
        when(documentRepository.findMaxSortOrderInFolder(WORKSPACE_ID, null)).thenReturn(3L);

        FolderResponse response = service.create(WORKSPACE_ID, USER_ID, "key-1",
                new FolderCreateRequest("자료", null));

        assertThat(response.name()).isEqualTo("자료");
        assertThat(response.sortOrder()).isEqualTo(4);
        assertThat(response.currentVersion()).isEqualTo(1);
        verify(folderRepository).save(any(Folder.class));
        verify(idempotencyService).execute(
                eq(USER_ID), any(), eq("key-1"), any(), eq(FolderResponse.class),
                eq(201), any(), any());
    }

    @Test
    void create_trimsAndRejectsBlankName() {
        assertThatThrownBy(() -> service.create(WORKSPACE_ID, USER_ID, "key-1",
                new FolderCreateRequest("   ", null)))
                .isInstanceOf(InvalidHierarchyRequestException.class);
        verify(folderRepository, never()).save(any());
    }

    @Test
    void create_rejectsNonMember() {
        doThrow(new WorkspaceNotFoundException(WORKSPACE_ID))
                .when(workspaceAccessGuard).requireMember(WORKSPACE_ID, "intruder");
        assertThatThrownBy(() -> service.create(WORKSPACE_ID, "intruder", "key-1",
                new FolderCreateRequest("자료", null)))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void create_returnsReplayWithoutSaving() {
        UUID id = UUID.randomUUID();
        FolderResponse stored = new FolderResponse(id, null, "자료", 0, 1, null, null);
        doReturn(stored).when(idempotencyService).execute(
                any(), any(), any(), any(), eq(FolderResponse.class),
                anyInt(), any(), any());

        FolderResponse response = service.create(WORKSPACE_ID, USER_ID, "key-1",
                new FolderCreateRequest("자료", null));

        assertThat(response).isEqualTo(stored);
        verify(folderRepository, never()).save(any());
    }

    @Test
    void rename_conflictsOnStaleVersion() {
        memberOk();
        noReplay();
        UUID id = UUID.randomUUID();
        when(folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(id, WORKSPACE_ID))
                .thenReturn(Optional.of(new Folder(id, WORKSPACE_ID, null, "옛이름", 0)));
        when(folderRepository.renameIfVersionMatches(eq(id), eq(WORKSPACE_ID), eq(1L), eq("새이름"), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.rename(WORKSPACE_ID, USER_ID, id, "key-1",
                new FolderRenameRequest("새이름", 1L)))
                .isInstanceOf(HierarchyVersionConflictException.class);
    }

    @Test
    void move_rejectsCycleBeforeUpdate() {
        memberOk();
        UUID movingId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(movingId, WORKSPACE_ID))
                .thenReturn(Optional.of(new Folder(movingId, WORKSPACE_ID, null, "이동", 0)));
        when(folderRepository.findActiveForUpdate(targetId, WORKSPACE_ID))
                .thenReturn(Optional.of(new Folder(targetId, WORKSPACE_ID, movingId, "대상", 0)));
        when(folderRepository.countAncestorMatches(targetId, movingId)).thenReturn(1L);

        assertThatThrownBy(() -> service.move(WORKSPACE_ID, USER_ID, movingId, "key-1",
                new FolderPositionRequest(targetId, null, 1L)))
                .isInstanceOf(HierarchyCycleException.class);
        verify(folderRepository, never()).moveIfVersionMatches(any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void move_conflictsOnStaleVersion() {
        memberOk();
        noReplay();
        UUID movingId = UUID.randomUUID();
        when(folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(movingId, WORKSPACE_ID))
                .thenReturn(Optional.of(new Folder(movingId, WORKSPACE_ID, null, "이동", 0)));
        when(siblingReorderer.placeFolder(WORKSPACE_ID, null, movingId, null)).thenReturn(0L);
        when(folderRepository.moveIfVersionMatches(eq(movingId), eq(WORKSPACE_ID), eq(2L), eq(null),
                org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.move(WORKSPACE_ID, USER_ID, movingId, "key-1",
                new FolderPositionRequest(null, null, 2L)))
                .isInstanceOf(HierarchyVersionConflictException.class);
    }
}
