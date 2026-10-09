package fruition.core.document.service;

import fruition.TestcontainersConfiguration;
import fruition.core.document.dto.DocumentLifecycleRequest;
import fruition.core.document.dto.DocumentListResponse;
import fruition.core.document.dto.DocumentPositionRequest;
import fruition.core.document.dto.DocumentRenameRequest;
import fruition.core.document.dto.FolderCreateRequest;
import fruition.core.document.dto.FolderRenameRequest;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 멤버는 기본으로 남의 문서를 편집하고, 문서·폴더를 view로 바꾸면 소유자와 OWNER만 편집하는지 실제 DB로 확인한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DocumentPermissionIntegrationTest {

    @Autowired DocumentService documentService;
    @Autowired DocumentPermissionService permissionService;
    @Autowired DocumentPlacementService placementService;
    @Autowired FolderService folderService;
    @Autowired StringRedisTemplate redis;

    final String workspace = "ws_" + UUID.randomUUID();
    final String author = "user_author_" + UUID.randomUUID();
    final String member = "user_member_" + UUID.randomUUID();
    final String owner = "user_owner_" + UUID.randomUUID();

    UUID folderId;
    String documentId;
    long revision = 1;

    @BeforeEach
    void setUp() {
        role(author, "MEMBER");
        role(member, "MEMBER");
        role(owner, "OWNER");
        folderId = folderService.create(workspace, author, key(), new FolderCreateRequest("팀 문서", null)).id();
        documentId = documentService.createMarkdown(workspace, author, key(),
                new MarkdownDocumentCreateRequest("회의록", "# 처음", folderId)).id();
    }

    @Test
    void memberEditsOthersDocumentByDefaultButCannotDelete() {
        save(member, "# 멤버가 고침");

        assertThat(item(author).updatedBy()).isEqualTo(member);
        assertThat(item(member).canEdit()).isTrue();
        assertThat(item(member).canDelete()).isFalse();
        assertThat(item(author).canDelete()).isTrue();
        assertThatThrownBy(() -> documentService.delete(workspace, member, documentId, key(),
                new DocumentLifecycleRequest(1L))).isInstanceOf(DocumentWriteForbiddenException.class);
    }

    @Test
    void treeVersionDiffersPerUserBecauseCanDeleteDoes() {
        // 같은 클라이언트에서 계정을 바꿔도 앞 사용자의 can_delete를 304로 받지 않는다.
        assertThat(folderService.treeVersion(workspace, member))
                .isNotEqualTo(folderService.treeVersion(workspace, author));
    }

    @Test
    void viewDocumentAllowsOnlyAuthorAndWorkspaceOwner() {
        assertThatThrownBy(() -> permissionService.setDocument(workspace, member, documentId, "view"))
                .isInstanceOf(DocumentWriteForbiddenException.class);
        String before = folderService.treeVersion(workspace, member);
        permissionService.setDocument(workspace, author, documentId, "view");

        assertThat(folderService.treeVersion(workspace, member)).isNotEqualTo(before);
        assertThat(item(member).canEdit()).isFalse();
        assertThat(item(member).permission()).isEqualTo("view");
        assertThatThrownBy(() -> save(member, "# 막혀야 함")).isInstanceOf(DocumentWriteForbiddenException.class);
        assertThatThrownBy(() -> documentService.rename(workspace, member, documentId,
                new DocumentRenameRequest("바꾸기", 1L))).isInstanceOf(DocumentWriteForbiddenException.class);
        assertThatThrownBy(() -> placementService.move(workspace, member, documentId, key(),
                new DocumentPositionRequest(null, 0, 1L))).isInstanceOf(DocumentWriteForbiddenException.class);
        save(author, "# 작성자는 고칠 수 있음");
        save(owner, "# OWNER도 고칠 수 있음");

        permissionService.setDocument(workspace, author, documentId, null);
        assertThat(item(member).canEdit()).isTrue();
    }

    @Test
    void folderViewIsInheritedAndDocumentSettingWins() {
        assertThatThrownBy(() -> permissionService.setFolder(workspace, author, folderId, "view"))
                .isInstanceOf(DocumentWriteForbiddenException.class);
        permissionService.setFolder(workspace, owner, folderId, "view");

        assertThat(item(member).canEdit()).isFalse();
        assertThat(item(member).permission()).isNull();
        assertThatThrownBy(() -> save(member, "# 막혀야 함")).isInstanceOf(DocumentWriteForbiddenException.class);
        assertThatThrownBy(() -> folderService.rename(workspace, member, folderId, key(),
                new FolderRenameRequest("바꾸기", 1L))).isInstanceOf(DocumentWriteForbiddenException.class);
        save(author, "# 작성자는 고칠 수 있음");

        permissionService.setDocument(workspace, author, documentId, "edit");
        save(member, "# 문서 설정이 폴더보다 우선");
    }

    private void save(String userId, String markdown) {
        revision = documentService.saveContent(workspace, userId, documentId, markdown, revision,
                "write_" + UUID.randomUUID(), null).currentVersion();
    }

    private DocumentListResponse.DocumentItem item(String userId) {
        return documentService.findAll(workspace, userId, null).documents().stream()
                .filter(document -> document.id().equals(documentId))
                .findFirst().orElseThrow();
    }

    private void role(String userId, String role) {
        redis.opsForValue().set("authz:role:" + workspace + ":" + userId, role);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
