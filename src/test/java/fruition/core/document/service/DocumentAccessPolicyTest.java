package fruition.core.document.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.domain.Document;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentAccessPolicyTest {

    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock JdbcTemplate jdbcTemplate;

    @Test
    void viewer_readsRoleAndPermissionsOnce() {
        when(workspaceAccessGuard.access("ws_1", "user_1"))
                .thenReturn(new WorkspaceAccessGuard.Access("MEMBER", List.of("document.convert")));

        DocumentAccessPolicy.Viewer viewer = new DocumentAccessPolicy(workspaceAccessGuard, jdbcTemplate)
                .viewer("ws_1", "user_1");

        assertThat(viewer.workspaceOwner()).isFalse();
        assertThat(viewer.convertPermission()).isTrue();
        // 소유자 여부와 변환 권한을 따로 묻지 않는다(권한 조회 한 번).
        verify(workspaceAccessGuard, times(1)).access("ws_1", "user_1");
        verify(workspaceAccessGuard, never()).isOwner(anyString(), anyString());
        verify(workspaceAccessGuard, never()).hasPermission(anyString(), anyString(), anyString());
    }

    @Test
    void requireEditAndDelete_judgeOwnerFromSingleAccessRead() {
        when(workspaceAccessGuard.access("ws_1", "owner_1"))
                .thenReturn(new WorkspaceAccessGuard.Access("OWNER", List.of()));
        when(workspaceAccessGuard.access("ws_1", "member_1"))
                .thenReturn(new WorkspaceAccessGuard.Access("MEMBER", List.of()));
        Document document = mock(Document.class);
        when(document.getWorkspaceId()).thenReturn("ws_1");
        when(document.getUserId()).thenReturn("author_1");
        DocumentAccessPolicy policy = new DocumentAccessPolicy(workspaceAccessGuard, jdbcTemplate);

        assertThatCode(() -> policy.requireDelete(document, "owner_1")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireDelete(document, "member_1"))
                .isInstanceOf(DocumentWriteForbiddenException.class);

        verify(workspaceAccessGuard, times(1)).access("ws_1", "owner_1");
        verify(workspaceAccessGuard, times(1)).access("ws_1", "member_1");
        verify(workspaceAccessGuard, never()).isOwner(anyString(), anyString());
        verify(workspaceAccessGuard, never()).hasPermission(anyString(), anyString(), anyString());
    }
}
