package fruition.core.document.service;

import fruition.TestcontainersConfiguration;
import fruition.core.document.dto.DocumentPositionRequest;
import fruition.core.document.dto.DocumentPositionResponse;
import fruition.core.document.dto.BreadcrumbResponse;
import fruition.core.document.dto.DocumentListResponse;
import fruition.core.document.dto.DocumentTreeResponse;
import fruition.core.document.dto.DocumentUploadResponse;
import fruition.core.document.dto.FolderChildrenResponse;
import fruition.core.document.dto.HierarchySearchResponse;
import fruition.core.document.dto.FolderCreateRequest;
import fruition.core.document.dto.FolderLifecycleResponse;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.dto.FolderPositionRequest;
import fruition.core.document.dto.FolderRenameRequest;
import fruition.core.document.dto.FolderResponse;
import fruition.core.document.exception.HierarchyCycleException;
import fruition.core.document.exception.HierarchyItemNotFoundException;
import fruition.core.document.exception.HierarchyVersionConflictException;
import fruition.core.document.exception.HierarchyWriteForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FolderServiceIntegrationTest {

    @Autowired FolderService folderService;
    @Autowired DocumentPlacementService documentPlacementService;
    @Autowired DocumentService documentService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    private String userId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        userId = "user_" + suffix;
        workspaceId = "ws_" + suffix;
        // users/workspaces는 access_db 소유 — core_db에는 FK가 없어 ID만 쓰면 된다 (MSA DB 분리).
        seedAuthzProjection(userId, "OWNER");
    }

    /** guard가 access DB 대신 Redis projection을 읽으므로 멤버십을 projection에도 심는다. */
    private void seedAuthzProjection(String memberId, String role) {
        redisTemplate.opsForValue().set("authz:role:" + workspaceId + ":" + memberId, role);
    }

    @Test
    void create_rejectsSameNameSiblingsAndAppendsOrder() {
        FolderResponse first = folderService.create(workspaceId, userId, "k1",
                new FolderCreateRequest("자료", null));
        assertThatThrownBy(() -> folderService.create(workspaceId, userId, "duplicate",
                new FolderCreateRequest("자료", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        FolderResponse second = folderService.create(workspaceId, userId, "k2",
                new FolderCreateRequest("추가 자료", null));

        assertThat(first.sortOrder()).isEqualTo(0);
        assertThat(second.sortOrder()).isEqualTo(1);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM folders WHERE workspace_id = ? AND name = ?",
                Integer.class, workspaceId, "자료");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void create_isIdempotentForSameKey() {
        FolderResponse first = folderService.create(workspaceId, userId, "same-key",
                new FolderCreateRequest("자료", null));
        FolderResponse replay = folderService.create(workspaceId, userId, "same-key",
                new FolderCreateRequest("자료", null));

        assertThat(replay.id()).isEqualTo(first.id());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM folders WHERE workspace_id = ?", Integer.class, workspaceId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void rename_conflictsOnStaleVersion() {
        FolderResponse folder = folderService.create(workspaceId, userId, "k1",
                new FolderCreateRequest("옛이름", null));

        assertThatThrownBy(() -> folderService.rename(workspaceId, userId, folder.id(), "k2",
                new FolderRenameRequest("새이름", 999L)))
                .isInstanceOf(HierarchyVersionConflictException.class);
    }

    @Test
    void move_rejectsCycleIntoOwnDescendant() {
        FolderResponse parent = folderService.create(workspaceId, userId, "k1",
                new FolderCreateRequest("부모", null));
        FolderResponse child = folderService.create(workspaceId, userId, "k2",
                new FolderCreateRequest("자식", parent.id()));

        assertThatThrownBy(() -> folderService.move(workspaceId, userId, parent.id(), "k3",
                new FolderPositionRequest(child.id(), null, parent.currentVersion())))
                .isInstanceOf(HierarchyCycleException.class);
    }

    @Test
    void children_returnFoldersAndDocumentsInMixedOrder() {
        FolderResponse parent = folderService.create(workspaceId, userId, "k1",
                new FolderCreateRequest("부모", null));
        FolderResponse childFolder = folderService.create(workspaceId, userId, "k2",
                new FolderCreateRequest("하위폴더", parent.id()));
        insertDocumentInFolder("doc_mid", "메모.md", "EDITABLE", parent.id(), 1);

        FolderChildrenResponse children = folderService.children(workspaceId, userId, parent.id());

        List<FolderChildrenResponse.Item> items = children.items();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).type()).isEqualTo("folder");
        assertThat(items.get(0).id()).isEqualTo(childFolder.id().toString());
        assertThat(items.get(1).type()).isEqualTo("document");
        assertThat(items.get(1).id()).isEqualTo("doc_mid");
    }

    @Test
    void tree_returnsAllActiveFoldersAndDocumentsNestedInMixedOrder() {
        FolderResponse parent = folderService.create(workspaceId, userId, "k1",
                new FolderCreateRequest("부모", null));
        FolderResponse child = folderService.create(workspaceId, userId, "k2",
                new FolderCreateRequest("자식", parent.id()));
        insertDocumentInFolder("doc_root", "최상위.md", "EDITABLE", null, 1);
        insertDocumentInFolder("doc_parent", "부모 문서.md", "EDITABLE", parent.id(), 1);
        insertDocumentInFolder("doc_child", "자식 문서.md", "EDITABLE", child.id(), 0);
        jdbcTemplate.update(
                "UPDATE documents SET deleted_at = now() WHERE id = ?",
                "doc_parent");

        DocumentTreeResponse tree = folderService.tree(workspaceId, userId);

        assertThat(tree.items()).extracting(DocumentTreeResponse.Item::id)
                .containsExactly(parent.id().toString(), "doc_root");
        DocumentTreeResponse.Item parentItem = tree.items().get(0);
        assertThat(parentItem.children()).extracting(DocumentTreeResponse.Item::id)
                .containsExactly(child.id().toString());
        assertThat(parentItem.children().get(0).children())
                .extracting(DocumentTreeResponse.Item::id)
                .containsExactly("doc_child");
    }

    /**
     * 화면은 계층과 문서 상태를 함께 쓴다. 트리가 상태를 주지 않으면 목록 조회를 또 불러
     * 합쳐야 하고, 두 응답의 규칙이 갈리면 같은 문서가 화면마다 다르게 보인다.
     */
    @Test
    void tree_carriesSameDocumentMetadataAsListResponse() {
        insertDocumentInFolder("doc_meta", "메타.md", "EDITABLE", null, 0);

        DocumentTreeResponse tree = folderService.tree(workspaceId, userId);
        DocumentListResponse list = documentService.findAll(workspaceId, userId, null);

        DocumentTreeResponse.Item item = tree.items().stream()
                .filter(candidate -> "doc_meta".equals(candidate.id()))
                .findFirst().orElseThrow();
        DocumentListResponse.DocumentItem fromList = list.documents().stream()
                .filter(candidate -> "doc_meta".equals(candidate.id()))
                .findFirst().orElseThrow();

        assertThat(item.document()).isEqualTo(fromList);
    }

    /**
     * 화면은 name의 확장자로 Markdown 문서인지 가린다. display_name(확장자 없음)을 주면
     * 모든 노트가 편집 불가로 취급된다. 사람에게 보일 제목은 document.display_name에 있다.
     */
    @Test
    void tree_documentNameIsFilenameNotDisplayName() {
        insertDocumentInFolder("doc_naming", "회의록.md", "EDITABLE", null, 0);

        DocumentTreeResponse tree = folderService.tree(workspaceId, userId);

        DocumentTreeResponse.Item item = tree.items().stream()
                .filter(candidate -> "doc_naming".equals(candidate.id()))
                .findFirst().orElseThrow();
        assertThat(item.name()).isEqualTo("회의록.md");
        assertThat(item.document().displayName()).isEqualTo("회의록");
    }

    /** 폴더에는 문서 메타가 없다. 키 자체가 빠져야 화면이 종류를 헷갈리지 않는다. */
    @Test
    void tree_folderItemHasNoDocumentMetadata() {
        FolderResponse folder = folderService.create(workspaceId, userId, "k_meta",
                new FolderCreateRequest("폴더", null));

        DocumentTreeResponse tree = folderService.tree(workspaceId, userId);

        DocumentTreeResponse.Item item = tree.items().stream()
                .filter(candidate -> folder.id().toString().equals(candidate.id()))
                .findFirst().orElseThrow();
        assertThat(item.document()).isNull();
    }

    @Test
    void document_movesFromRootIntoFolder() {
        FolderResponse folder = folderService.create(workspaceId, userId, "k1",
                new FolderCreateRequest("자료", null));
        insertDocumentInFolder("doc_move", "메모.md", "EDITABLE", null, 0);

        DocumentPositionResponse moved = documentPlacementService.move(workspaceId, userId, "doc_move", "mk1",
                new DocumentPositionRequest(folder.id(), null, 1L));

        assertThat(moved.folderId()).isEqualTo(folder.id());
        assertThat(moved.currentVersion()).isEqualTo(2);
        UUID stored = jdbcTemplate.queryForObject(
                "SELECT folder_id FROM documents WHERE id = ?", UUID.class, "doc_move");
        assertThat(stored).isEqualTo(folder.id());
    }

    @Test
    void document_moveConflictsOnStaleVersion() {
        insertDocumentInFolder("doc_stale", "메모.md", "EDITABLE", null, 0);

        assertThatThrownBy(() -> documentPlacementService.move(workspaceId, userId, "doc_stale", "mk1",
                new DocumentPositionRequest(null, null, 999L)))
                .isInstanceOf(HierarchyVersionConflictException.class);
    }

    @Test
    void document_moveToMissingFolderIsNotFound() {
        insertDocumentInFolder("doc_orphan", "메모.md", "EDITABLE", null, 0);

        assertThatThrownBy(() -> documentPlacementService.move(workspaceId, userId, "doc_orphan", "mk1",
                new DocumentPositionRequest(UUID.randomUUID(), null, 1L)))
                .isInstanceOf(HierarchyItemNotFoundException.class);
    }

    @Test
    void folder_movesToExplicitPosition() {
        FolderResponse a = folderService.create(workspaceId, userId, "ka", new FolderCreateRequest("A", null));
        FolderResponse b = folderService.create(workspaceId, userId, "kb", new FolderCreateRequest("B", null));
        FolderResponse c = folderService.create(workspaceId, userId, "kc", new FolderCreateRequest("C", null));

        folderService.move(workspaceId, userId, c.id(), "mk",
                new FolderPositionRequest(null, 0, c.currentVersion()));

        List<FolderChildrenResponse.Item> items = folderService.children(workspaceId, userId, null).items();
        assertThat(items).extracting(FolderChildrenResponse.Item::id)
                .containsExactly(c.id().toString(), a.id().toString(), b.id().toString());
        assertThat(items).extracting(FolderChildrenResponse.Item::sortOrder)
                .containsExactly(0L, 1L, 2L);
    }

    @Test
    void document_reordersBetweenFolders() {
        FolderResponse a = folderService.create(workspaceId, userId, "ka", new FolderCreateRequest("A", null));
        FolderResponse b = folderService.create(workspaceId, userId, "kb", new FolderCreateRequest("B", null));
        insertDocumentInFolder("doc_reorder", "메모.md", "EDITABLE", null, 2);

        documentPlacementService.move(workspaceId, userId, "doc_reorder", "mk",
                new DocumentPositionRequest(null, 1, 1L));

        List<FolderChildrenResponse.Item> items = folderService.children(workspaceId, userId, null).items();
        assertThat(items).extracting(FolderChildrenResponse.Item::id)
                .containsExactly(a.id().toString(), "doc_reorder", b.id().toString());
        assertThat(items).extracting(FolderChildrenResponse.Item::sortOrder)
                .containsExactly(0L, 1L, 2L);
    }

    @Test
    void folder_deleteSoftDeletesSubtreeWithSharedOperation() {
        FolderResponse parent = folderService.create(workspaceId, userId, "kp", new FolderCreateRequest("부모", null));
        FolderResponse child = folderService.create(workspaceId, userId, "kc", new FolderCreateRequest("자식", parent.id()));
        insertDocumentInFolder("doc_p", "p.md", "EDITABLE", parent.id(), 5);
        insertDocumentInFolder("doc_c", "c.md", "EDITABLE", child.id(), 6);

        FolderLifecycleResponse res = folderService.delete(workspaceId, userId, parent.id(), "dk",
                parent.currentVersion());

        assertThat(res.deleted()).isTrue();
        assertThat(folderService.children(workspaceId, userId, null).items()).isEmpty();
        UUID op = res.deleteOperationId();
        Integer deletedFolders = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM folders WHERE delete_operation_id = ? AND deleted_at IS NOT NULL",
                Integer.class, op);
        Integer deletedDocuments = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents WHERE delete_operation_id = ? AND deleted_at IS NOT NULL",
                Integer.class, op);
        assertThat(deletedFolders).isEqualTo(2);
        assertThat(deletedDocuments).isEqualTo(2);
    }

    @Test
    void folder_restoreRestoresSubtreeUnderOriginalParent() {
        FolderResponse parent = folderService.create(workspaceId, userId, "kp", new FolderCreateRequest("부모", null));
        folderService.create(workspaceId, userId, "kc", new FolderCreateRequest("자식", parent.id()));
        insertDocumentInFolder("doc_p2", "p.md", "EDITABLE", parent.id(), 5);
        FolderLifecycleResponse deleted = folderService.delete(workspaceId, userId, parent.id(), "dk",
                parent.currentVersion());

        FolderLifecycleResponse restored = folderService.restore(workspaceId, userId, parent.id(), "rk",
                deleted.currentVersion());

        assertThat(restored.deleted()).isFalse();
        assertThat(folderService.children(workspaceId, userId, null).items())
                .extracting(FolderChildrenResponse.Item::id).contains(parent.id().toString());
        assertThat(folderService.children(workspaceId, userId, parent.id()).items()).hasSize(2);
    }

    @Test
    void folder_individualRestoreOfDescendantPlacesAtRoot() {
        FolderResponse parent = folderService.create(workspaceId, userId, "kp", new FolderCreateRequest("부모", null));
        FolderResponse child = folderService.create(workspaceId, userId, "kc", new FolderCreateRequest("자식", parent.id()));
        folderService.delete(workspaceId, userId, parent.id(), "dk", parent.currentVersion());

        // 자식만 개별 복구: 원래 부모가 아직 삭제 상태이므로 최상위로 배치된다.
        folderService.restore(workspaceId, userId, child.id(), "rk", child.currentVersion() + 1);

        List<String> rootIds = folderService.children(workspaceId, userId, null).items().stream()
                .map(FolderChildrenResponse.Item::id).toList();
        assertThat(rootIds).contains(child.id().toString());
        assertThat(rootIds).doesNotContain(parent.id().toString());
        UUID childParent = jdbcTemplate.queryForObject(
                "SELECT parent_folder_id FROM folders WHERE id = ?", UUID.class, child.id());
        assertThat(childParent).isNull();
    }

    @Test
    void folder_deleteNonEmptyByNonOwnerForbidden() {
        String memberId = insertMember("MEMBER");
        FolderResponse a = folderService.create(workspaceId, memberId, "ka", new FolderCreateRequest("A", null));
        folderService.create(workspaceId, memberId, "kb", new FolderCreateRequest("하위", a.id()));

        assertThatThrownBy(() -> folderService.delete(workspaceId, memberId, a.id(), "dk", a.currentVersion()))
                .isInstanceOf(HierarchyWriteForbiddenException.class);
    }

    @Test
    void folder_deleteEmptyByMemberAllowed() {
        String memberId = insertMember("MEMBER");
        FolderResponse a = folderService.create(workspaceId, memberId, "ka", new FolderCreateRequest("A", null));

        FolderLifecycleResponse res = folderService.delete(workspaceId, memberId, a.id(), "dk", a.currentVersion());

        assertThat(res.deleted()).isTrue();
    }

    @Test
    void createMarkdown_placesDocumentInSelectedFolder() {
        FolderResponse folder = folderService.create(workspaceId, userId, "kf", new FolderCreateRequest("자료", null));

        DocumentUploadResponse created = documentService.createMarkdown(workspaceId, userId, "cmk",
                new MarkdownDocumentCreateRequest("메모", "# 본문", folder.id()));

        UUID stored = jdbcTemplate.queryForObject(
                "SELECT folder_id FROM documents WHERE id = ?", UUID.class, created.id());
        assertThat(stored).isEqualTo(folder.id());
        assertThat(folderService.children(workspaceId, userId, folder.id()).items())
                .extracting(FolderChildrenResponse.Item::id).contains(created.id());
    }

    @Test
    void createMarkdown_rejectsMissingFolder() {
        assertThatThrownBy(() -> documentService.createMarkdown(workspaceId, userId, "cmk",
                new MarkdownDocumentCreateRequest("메모", "# 본문", UUID.randomUUID())))
                .isInstanceOf(HierarchyItemNotFoundException.class);
    }

    @Test
    void breadcrumb_returnsPathFromRootToDocument() {
        FolderResponse a = folderService.create(workspaceId, userId, "ka", new FolderCreateRequest("A", null));
        FolderResponse b = folderService.create(workspaceId, userId, "kb", new FolderCreateRequest("B", a.id()));
        DocumentUploadResponse doc = documentService.createMarkdown(workspaceId, userId, "cmk",
                new MarkdownDocumentCreateRequest("메모", "# 본문", b.id()));

        BreadcrumbResponse crumb = folderService.breadcrumb(workspaceId, userId, null, doc.id());

        assertThat(crumb.path()).extracting(BreadcrumbResponse.Node::name)
                .containsExactly("A", "B", "메모");
        assertThat(crumb.path()).extracting(BreadcrumbResponse.Node::type)
                .containsExactly("folder", "folder", "document");
    }

    @Test
    void breadcrumb_returnsPathToFolder() {
        FolderResponse a = folderService.create(workspaceId, userId, "ka", new FolderCreateRequest("A", null));
        FolderResponse b = folderService.create(workspaceId, userId, "kb", new FolderCreateRequest("B", a.id()));

        BreadcrumbResponse crumb = folderService.breadcrumb(workspaceId, userId, b.id(), null);

        assertThat(crumb.path()).extracting(BreadcrumbResponse.Node::name).containsExactly("A", "B");
    }

    @Test
    void search_findsFoldersAndDocumentsWithBreadcrumb() {
        FolderResponse a = folderService.create(workspaceId, userId, "ka", new FolderCreateRequest("보고서 폴더", null));
        documentService.createMarkdown(workspaceId, userId, "cmk",
                new MarkdownDocumentCreateRequest("보고서 메모", "# 본문", a.id()));

        HierarchySearchResponse result = folderService.search(workspaceId, userId, "보고서");

        assertThat(result.results()).extracting(HierarchySearchResponse.Match::name)
                .contains("보고서 폴더", "보고서 메모");
        HierarchySearchResponse.Match docMatch = result.results().stream()
                .filter(m -> m.type().equals("document")).findFirst().orElseThrow();
        assertThat(docMatch.breadcrumb()).extracting(BreadcrumbResponse.Node::name).containsExactly("보고서 폴더");
    }

    private String insertMember(String role) {
        String memberId = "member_" + UUID.randomUUID();
        // users/workspace_members는 access_db 소유 — guard가 읽는 projection만 심는다 (MSA DB 분리).
        seedAuthzProjection(memberId, role);
        return memberId;
    }

    private void insertDocumentInFolder(String documentId, String filename, String role, UUID folderId, long sortOrder) {
        jdbcTemplate.update(
                """
                INSERT INTO documents(
                    id, byte_size, content_hash, filename, display_name, normalized_filename,
                    mime_type, source_uri, status, uploaded_at, updated_at, user_id, workspace_id,
                    current_content_hash, current_version, document_role, sort_order, folder_id
                ) VALUES (?, 1, ?, ?, ?, ?, ?, ?, 'completed', now(), now(), ?, ?, ?, 1, ?, ?, ?)
                """,
                documentId,
                documentId + "-hash",
                filename,
                filename.substring(0, filename.lastIndexOf('.')),
                filename.toLowerCase(),
                role.equals("EDITABLE") ? "text/markdown" : "application/pdf",
                role.equals("EDITABLE") ? null : "sources/" + documentId,
                userId,
                workspaceId,
                documentId + "-hash",
                role,
                sortOrder,
                folderId);
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void deleteEmpty_rejectsOwnerCascadeAndReplaysSuccessfulDelete() {
        FolderResponse folder = folderService.create(workspaceId, userId, "empty-parent",
                new FolderCreateRequest("정리 폴더", null));
        FolderResponse child = folderService.create(workspaceId, userId, "empty-child",
                new FolderCreateRequest("다른 작업 폴더", folder.id()));
        assertThatThrownBy(() -> folderService.deleteEmpty(workspaceId, userId, folder.id(), "undo", 1))
                .isInstanceOf(HierarchyVersionConflictException.class);
        assertThat(folderService.children(workspaceId, userId, folder.id()).items()).hasSize(1);
        folderService.deleteEmpty(workspaceId, userId, child.id(), "undo-child", 1);
        var deleted = folderService.deleteEmpty(workspaceId, userId, folder.id(), "undo", 1);
        assertThat(folderService.deleteEmpty(workspaceId, userId, folder.id(), "undo", 1)).isEqualTo(deleted);
    }

    @Test
    void deleteEmpty_serializesConcurrentChildCreation() throws Exception {
        FolderResponse folder = folderService.create(workspaceId, userId, "race-parent",
                new FolderCreateRequest("정리 폴더", null));
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var deletion = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .execute(status -> {
                        var result = folderService.deleteEmpty(workspaceId, userId, folder.id(), "race-delete", 1);
                        locked.countDown();
                        try {
                            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                        } catch (InterruptedException e) { throw new RuntimeException(e); }
                        return result;
                    }));
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var creation = executor.submit(() -> {
                started.countDown();
                return folderService.create(workspaceId, userId, "race-create", new FolderCreateRequest("늦은 항목", folder.id()));
            });
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> creation.get(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            deletion.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThatThrownBy(() -> creation.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(HierarchyItemNotFoundException.class);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM folders WHERE parent_folder_id = ?", Integer.class, folder.id()))
                    .isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

}
