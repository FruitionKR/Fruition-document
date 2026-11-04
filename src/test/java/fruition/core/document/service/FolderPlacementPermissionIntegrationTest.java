package fruition.core.document.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fruition.TestcontainersConfiguration;
import fruition.core.agent.dto.AgentToolExecuteRequest;
import fruition.core.agent.repository.PipelineAgentArtifactClient;
import fruition.core.agent.repository.PipelineAgentToolAuthorizationClient;
import fruition.core.agent.service.AgentToolService;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.authz.WorkspaceSettingsClient;
import fruition.core.document.dto.DocumentLifecycleRequest;
import fruition.core.document.dto.DocumentPositionRequest;
import fruition.core.document.dto.DocumentPositionResponse;
import fruition.core.document.dto.DocumentRenameRequest;
import fruition.core.document.dto.FolderCreateRequest;
import fruition.core.document.dto.FolderPositionRequest;
import fruition.core.document.dto.FolderResponse;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import fruition.core.document.exception.HierarchyItemNotFoundException;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import fruition.core.imports.DocumentImportDtos;
import fruition.core.imports.DocumentImportService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 폴더를 편집할 수 없는 사람은 그 폴더에 넣거나 꺼낼 수 없는지 실제 DB로 확인한다. 위치 이름은 다음 구조를 가리킨다.
 * <pre>
 * root
 * ├─ edit          설정 없음(기본 편집)
 * └─ view          view
 *    ├─ inherited  설정 없음(상위 view 상속)
 *    └─ reset      edit로 재설정
 * </pre>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FolderPlacementPermissionIntegrationTest {

    private static final String FORBIDDEN = "이 폴더에 넣거나 꺼낼 권한이 없습니다.";

    @MockBean PipelineAgentToolAuthorizationClient agentAuthorization;
    @MockBean PipelineAgentArtifactClient agentArtifacts;
    @MockBean AiCommandOutboxPublisher outboxPublisher;
    @MockBean WorkspaceAiModelClient workspaceAiModelClient;
    @MockBean WorkspaceSettingsClient workspaceSettings;

    @Autowired DocumentService documentService;
    @Autowired DocumentPermissionService permissionService;
    @Autowired DocumentPlacementService placementService;
    @Autowired FolderService folderService;
    @Autowired AgentToolService agentTools;
    @Autowired DocumentImportService imports;
    @Autowired DocumentRepository documents;
    @Autowired FolderRepository folders;
    @Autowired StringRedisTemplate redis;

    private final ObjectMapper json = new ObjectMapper();

    final String workspace = "ws_" + UUID.randomUUID();
    final String author = "user_author_" + UUID.randomUUID();
    final String member = "user_member_" + UUID.randomUUID();
    final String owner = "user_owner_" + UUID.randomUUID();

    UUID edit;
    UUID view;
    UUID inherited;
    UUID reset;

    @BeforeEach
    void setUp() {
        role(author, "MEMBER");
        role(member, "MEMBER");
        role(owner, "OWNER");
        edit = folder(owner, "편집", null).id();
        view = folder(owner, "보기", null).id();
        inherited = folder(owner, "상속", view).id();
        reset = folder(owner, "재설정", view).id();
        permissionService.setFolder(workspace, owner, view, "view");
        permissionService.setFolder(workspace, owner, reset, "edit");
        when(workspaceAiModelClient.get(anyString()))
                .thenReturn(new WorkspaceAiModelClient.AiModelSelection("gemini", "gemini-3.5-flash-lite"));
    }

    // ---------- 문서 이동: 멤버·문서 소유자·OWNER × 넣기·꺼내기 × 보기·상속·재설정·최상위

    @ParameterizedTest(name = "{0}: {1} -> {2} = {3}")
    @CsvSource({
            // 넣기
            "member, root, view, false", "member, root, inherited, false", "member, root, reset, true",
            "member, root, edit, true", "member, edit, view, false", "member, reset, view, false",
            // 꺼내기
            "member, view, root, false", "member, inherited, edit, false", "member, reset, root, true",
            "member, edit, root, true", "member, view, inherited, false",
            // 문서 소유자라도 예외가 없다.
            "author, root, view, false", "author, root, inherited, false", "author, root, reset, true",
            "author, root, edit, true", "author, edit, view, false", "author, reset, view, false",
            "author, view, root, false", "author, inherited, edit, false", "author, reset, root, true",
            "author, edit, root, true", "author, view, inherited, false",
            // OWNER는 항상 된다.
            "owner, root, view, true", "owner, root, inherited, true", "owner, edit, view, true",
            "owner, view, root, true", "owner, inherited, edit, true", "owner, view, inherited, true"
    })
    void documentMoveChecksSourceAndDestination(String actor, String from, String to, boolean allowed) {
        DocumentPositionResponse placed = authorDocumentIn(folderAt(from));

        ThrowingCallable move = () -> placementService.move(workspace, user(actor), placed.id(), key(),
                new DocumentPositionRequest(folderAt(to), null, placed.currentVersion()));

        if (allowed) {
            assertThat(catchThrowable(move)).isNull();
            assertThat(documents.findById(placed.id()).orElseThrow().getFolderId()).isEqualTo(folderAt(to));
        } else {
            assertForbidden(move);
            assertThat(documents.findById(placed.id()).orElseThrow().getFolderId()).isEqualTo(folderAt(from));
        }
    }

    @Test
    void documentWithOwnEditSettingStillCannotLeaveViewFolder() {
        DocumentPositionResponse placed = authorDocumentIn(view);
        permissionService.setDocument(workspace, author, placed.id(), "edit");

        assertForbidden(() -> placementService.move(workspace, member, placed.id(), key(),
                new DocumentPositionRequest(null, null, placed.currentVersion())));
    }

    @Test
    void documentWithOwnViewSettingInEditFolderKeepsDocumentEditMessage() {
        DocumentPositionResponse placed = authorDocumentIn(edit);
        permissionService.setDocument(workspace, author, placed.id(), "view");

        assertThatThrownBy(() -> placementService.move(workspace, member, placed.id(), key(),
                new DocumentPositionRequest(null, null, placed.currentVersion())))
                .isInstanceOf(DocumentWriteForbiddenException.class)
                .hasMessage("이 문서를 편집할 권한이 없습니다.");
    }

    // ---------- 폴더 이동: 옮기는 폴더 자신, 출발 상위, 목적지

    @ParameterizedTest(name = "{0}: {1} -> {2} = {3}")
    @CsvSource({
            "member, root, view, false", "member, root, inherited, false", "member, root, reset, true",
            "member, root, edit, true", "member, edit, view, false", "member, inherited, root, false",
            "member, view, edit, false", "member, reset, root, true", "member, edit, root, true",
            "author, root, view, false", "author, view, edit, false",
            "owner, root, view, true", "owner, view, root, true", "owner, inherited, edit, true"
    })
    void folderMoveChecksSourceAndDestination(String actor, String from, String to, boolean allowed) {
        FolderResponse moved = folder(owner, "옮길 폴더", folderAt(from));

        ThrowingCallable move = () -> folderService.move(workspace, user(actor), moved.id(), key(),
                new FolderPositionRequest(folderAt(to), null, moved.currentVersion()));

        if (allowed) {
            assertThat(catchThrowable(move)).isNull();
            assertThat(folders.findById(moved.id()).orElseThrow().getParentFolderId()).isEqualTo(folderAt(to));
        } else {
            assertForbidden(move);
            assertThat(folders.findById(moved.id()).orElseThrow().getParentFolderId()).isEqualTo(folderAt(from));
        }
    }

    @Test
    void folderWithOwnEditSettingStillCannotLeaveViewParent() {
        FolderResponse moved = folder(owner, "옮길 폴더", view);
        permissionService.setFolder(workspace, owner, moved.id(), "edit");

        assertForbidden(() -> folderService.move(workspace, member, moved.id(), key(),
                new FolderPositionRequest(null, null, moved.currentVersion())));
    }

    @Test
    void folderWithOwnViewSettingCannotMoveEvenBetweenEditableParents() {
        FolderResponse moved = folder(owner, "옮길 폴더", edit);
        permissionService.setFolder(workspace, owner, moved.id(), "view");

        assertThatThrownBy(() -> folderService.move(workspace, member, moved.id(), key(),
                new FolderPositionRequest(null, null, moved.currentVersion())))
                .isInstanceOf(DocumentWriteForbiddenException.class)
                .hasMessage("이 폴더를 편집할 권한이 없습니다.");
    }

    // ---------- 같은 폴더 안 순서 변경은 넣기·꺼내기가 아니다

    @Test
    void reorderWithinViewFolderKeepsExistingDocumentRule() {
        DocumentPositionResponse own = authorDocumentIn(view);

        placementService.move(workspace, author, own.id(), key(),
                new DocumentPositionRequest(view, 0, own.currentVersion()));

        // 남의 문서는 지금처럼 문서 편집 권한이 없어 막힌다.
        long version = documents.findById(own.id()).orElseThrow().getCurrentVersion();
        assertThatThrownBy(() -> placementService.move(workspace, member, own.id(), key(),
                new DocumentPositionRequest(view, 0, version)))
                .isInstanceOf(DocumentWriteForbiddenException.class)
                .hasMessage("이 문서를 편집할 권한이 없습니다.");
    }

    @Test
    void reorderWithinSameParentChecksOnlyTheFolderItself() {
        FolderResponse moved = folder(owner, "옮길 폴더", view);
        permissionService.setFolder(workspace, owner, moved.id(), "edit");

        FolderResponse reordered = folderService.move(workspace, member, moved.id(), key(),
                new FolderPositionRequest(view, 0, moved.currentVersion()));

        assertThat(reordered.parentFolderId()).isEqualTo(view);
    }

    @Test
    void reorderAtRootStaysAllowedForMembers() {
        DocumentPositionResponse own = authorDocumentIn(null);

        DocumentPositionResponse reordered = placementService.move(workspace, member, own.id(), key(),
                new DocumentPositionRequest(null, 0, own.currentVersion()));

        assertThat(reordered.folderId()).isNull();
    }

    // ---------- 넣는 경로별 차단

    @Test
    void creatingFolderIsCheckedAgainstParent() {
        assertForbidden(() -> folder(member, "새 폴더", view));
        assertForbidden(() -> folder(author, "새 폴더", inherited));
        assertThat(folder(member, "새 폴더", reset).parentFolderId()).isEqualTo(reset);
        assertThat(folder(member, "새 폴더", null).parentFolderId()).isNull();
        assertThat(folder(owner, "새 폴더", view).parentFolderId()).isEqualTo(view);
    }

    @Test
    void creatingMarkdownIsCheckedAgainstFolder() {
        assertForbidden(() -> markdown(member, view));
        assertForbidden(() -> markdown(author, inherited));
        assertForbidden(() -> documentService.createMarkdownAsUpload(workspace, member, key(),
                new MarkdownDocumentCreateRequest("새 문서", "# 본문", view), null));
        assertThat(markdown(member, reset)).isNotBlank();
        assertThat(markdown(owner, view)).isNotBlank();
    }

    @Test
    void uploadingFileIsCheckedAgainstFolder() {
        assertForbidden(() -> documentService.upload(workspace, member, key(), view, pdf("막힘.pdf")));
        assertForbidden(() -> documentService.upload(workspace, author, key(), inherited, markdownFile("막힘.md")));

        assertThat(documentService.upload(workspace, member, key(), reset, pdf("됨.pdf")).folderId()).isEqualTo(reset);
        assertThat(documentService.upload(workspace, owner, key(), view, pdf("됨.pdf")).folderId()).isEqualTo(view);
    }

    @Test
    void importingIsCheckedAgainstFolder() {
        String session = imports.create(workspace, member,
                new DocumentImportDtos.CreateRequest("notion", 3, 100L)).importId().toString();

        assertForbidden(() -> documentService.createMarkdownAsUpload(workspace, member, key(),
                new MarkdownDocumentCreateRequest("가져온 문서", "# 본문", view), session));
        assertForbidden(() -> documentService.uploadImported(workspace, member, key(), view, pdf("가져옴.pdf"), session));

        assertThat(documentService.createMarkdownAsUpload(workspace, member, key(),
                new MarkdownDocumentCreateRequest("가져온 문서", "# 본문", reset), session).folderId()).isEqualTo(reset);
    }

    @Test
    void duplicatingIntoSourceFolderIsChecked() {
        String id = authorDocumentIn(view).id();

        assertForbidden(() -> documentService.duplicate(workspace, member, id, key()));
        assertForbidden(() -> documentService.duplicate(workspace, author, id, key()));
        assertThat(documentService.duplicate(workspace, owner, id, key()).folderId()).isEqualTo(view);

        String inReset = authorDocumentIn(reset).id();
        assertThat(documentService.duplicate(workspace, member, inReset, key()).folderId()).isEqualTo(reset);
    }

    @Test
    void convertingPdfPlacesPlaceholderInSourceFolderSoItIsChecked() {
        String pdfId = documentService.upload(workspace, author, key(), null, pdf("보고서.pdf")).id();
        long version = documents.findById(pdfId).orElseThrow().getCurrentVersion();
        placementService.move(workspace, owner, pdfId, key(), new DocumentPositionRequest(view, null, version));

        assertForbidden(() -> documentService.convertToMarkdown(workspace, author, pdfId, key()));

        assertThat(documentService.convertToMarkdown(workspace, owner, pdfId, key()).folderId()).isEqualTo(view);
    }

    @Test
    void agentToolsFollowTheSameRules() throws Exception {
        when(agentArtifacts.resolve(any(), anyString(), any())).thenReturn(new PipelineAgentArtifactClient.ResolvedArtifact(
                "artifact-1", "sha256:abc", "create_document", null, null, null, "# 문서\n"));
        DocumentPositionResponse inEdit = authorDocumentIn(edit);
        DocumentPositionResponse inView = authorDocumentIn(view);
        FolderResponse movable = folder(owner, "옮길 폴더", edit);

        assertForbidden(() -> agentTools.execute("create_document", tool(member, json.createObjectNode()
                .put("display_name", "새 문서").put("folder_id", view.toString())
                .put("content_artifact_id", "artifact-1").put("content_hash", "sha256:abc"))));
        assertForbidden(() -> agentTools.execute("create_folder", tool(member, json.createObjectNode()
                .put("name", "새 폴더").put("parent_folder_id", view.toString()))));
        assertForbidden(() -> agentTools.execute("move_document", tool(member, moveDocument(inEdit, view))));
        assertForbidden(() -> agentTools.execute("move_document", tool(author, moveDocument(inView, null))));
        assertForbidden(() -> agentTools.execute("move_folder", tool(member, json.createObjectNode()
                .put("folder_id", movable.id().toString()).put("parent_folder_id", view.toString())
                .putNull("position").put("base_version", movable.currentVersion()))));

        agentTools.execute("move_document", tool(member, moveDocument(inEdit, reset)));
        assertThat(documents.findById(inEdit.id()).orElseThrow().getFolderId()).isEqualTo(reset);
    }

    // ---------- 존재 여부는 지금처럼 404로 답한다

    @Test
    void missingOrForeignFolderIsStillNotFound() {
        String foreignWorkspace = "ws_" + UUID.randomUUID();
        role(member, foreignWorkspace, "OWNER");
        UUID foreign = folderService.create(foreignWorkspace, member, key(), new FolderCreateRequest("남의", null)).id();
        DocumentPositionResponse own = authorDocumentIn(null);

        for (UUID target : new UUID[] {foreign, UUID.randomUUID()}) {
            assertThatThrownBy(() -> placementService.move(workspace, member, own.id(), key(),
                    new DocumentPositionRequest(target, null, own.currentVersion())))
                    .isInstanceOf(HierarchyItemNotFoundException.class);
            assertThatThrownBy(() -> markdown(member, target)).isInstanceOf(HierarchyItemNotFoundException.class);
            assertThatThrownBy(() -> folder(member, "새 폴더", target)).isInstanceOf(HierarchyItemNotFoundException.class);
        }
    }

    // ---------- 멱등

    @Test
    void completedRequestsReplaySameResponseAfterPermissionIsRevoked() {
        DocumentPositionResponse own = authorDocumentIn(null);
        String moveKey = key();
        DocumentPositionRequest moveRequest = new DocumentPositionRequest(edit, null, own.currentVersion());
        DocumentPositionResponse moved = placementService.move(workspace, member, own.id(), moveKey, moveRequest);

        String folderKey = key();
        FolderCreateRequest folderRequest = new FolderCreateRequest("새 폴더", edit);
        FolderResponse created = folderService.create(workspace, member, folderKey, folderRequest);

        String markdownKey = key();
        MarkdownDocumentCreateRequest markdownRequest = new MarkdownDocumentCreateRequest("새 문서", "# 본문", edit);
        String markdownId = documentService.createMarkdown(workspace, member, markdownKey, markdownRequest).id();

        String uploadKey = key();
        String uploadId = documentService.upload(workspace, member, uploadKey, edit, pdf("됨.pdf")).id();

        String duplicateKey = key();
        String duplicateId = documentService.duplicate(workspace, member, markdownId, duplicateKey).id();

        permissionService.setFolder(workspace, owner, edit, "view");

        assertThat(placementService.move(workspace, member, own.id(), moveKey, moveRequest)).isEqualTo(moved);
        assertThat(folderService.create(workspace, member, folderKey, folderRequest).id()).isEqualTo(created.id());
        assertThat(documentService.createMarkdown(workspace, member, markdownKey, markdownRequest).id()).isEqualTo(markdownId);
        assertThat(documentService.upload(workspace, member, uploadKey, edit, pdf("됨.pdf")).id()).isEqualTo(uploadId);
        assertThat(documentService.duplicate(workspace, member, markdownId, duplicateKey).id()).isEqualTo(duplicateId);
    }

    @Test
    void rejectedRequestDoesNotConsumeIdempotencyKey() {
        DocumentPositionResponse own = authorDocumentIn(null);
        String moveKey = key();
        DocumentPositionRequest moveRequest = new DocumentPositionRequest(view, null, own.currentVersion());
        String markdownKey = key();
        MarkdownDocumentCreateRequest markdownRequest = new MarkdownDocumentCreateRequest("새 문서", "# 본문", view);
        String uploadKey = key();
        String folderKey = key();
        FolderCreateRequest folderRequest = new FolderCreateRequest("새 폴더", view);

        assertForbidden(() -> placementService.move(workspace, member, own.id(), moveKey, moveRequest));
        assertForbidden(() -> documentService.createMarkdown(workspace, member, markdownKey, markdownRequest));
        assertForbidden(() -> documentService.upload(workspace, member, uploadKey, view, pdf("다시.pdf")));
        assertForbidden(() -> folderService.create(workspace, member, folderKey, folderRequest));

        permissionService.setFolder(workspace, owner, view, "edit");

        assertThat(placementService.move(workspace, member, own.id(), moveKey, moveRequest).folderId()).isEqualTo(view);
        assertThat(documentService.createMarkdown(workspace, member, markdownKey, markdownRequest).folderId()).isEqualTo(view);
        assertThat(documentService.upload(workspace, member, uploadKey, view, pdf("다시.pdf")).folderId()).isEqualTo(view);
        assertThat(folderService.create(workspace, member, folderKey, folderRequest).parentFolderId()).isEqualTo(view);
    }

    // ---------- 넣기·꺼내기가 아닌 기존 동작과 시스템 내부 경로

    @Test
    void ownerExceptionForContentAndRenameIsKept() {
        DocumentPositionResponse own = authorDocumentIn(view);

        documentService.saveContent(workspace, author, own.id(), "# 고침", 1L, "write_" + UUID.randomUUID(), null);
        long version = documents.findById(own.id()).orElseThrow().getCurrentVersion();
        assertThat(documentService.rename(workspace, author, own.id(),
                new DocumentRenameRequest("새 이름", version)).id()).isEqualTo(own.id());
    }

    @Test
    void systemCreatedDocumentsAtRootAreNotAffected() {
        documentService.createInitialNote(workspace, member);

        assertThat(documentService.findAll(workspace, member, null).documents()).isNotEmpty();
        assertThat(documentService.createChatExportDocument(workspace, member, "채팅", "# 채팅", "hash-" + key(),
                java.util.List.of(new DocumentService.PipelineSourceBlock("b1", "채팅"))).skipped()).isFalse();
    }

    /** 취소 보상의 삭제 도구는 넣기·꺼내기가 아니라 삭제 규칙을 따르므로, 만든 뒤 권한이 회수돼도 지울 수 있다. */
    @Test
    void cancelCompensationDeletesWorkAfterPermissionIsRevoked() throws Exception {
        String created = markdown(member, edit);
        FolderResponse createdFolder = folder(member, "새 폴더", edit);
        permissionService.setFolder(workspace, owner, edit, "view");

        agentTools.execute("delete_document", tool(member, json.createObjectNode()
                .put("document_id", created).put("base_version", 1)));
        agentTools.execute("delete_folder", tool(member, json.createObjectNode()
                .put("folder_id", createdFolder.id().toString()).put("base_version", createdFolder.currentVersion())
                .put("require_empty", true)));

        assertThat(documents.findById(created).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(folders.findById(createdFolder.id()).orElseThrow().getDeletedAt()).isNotNull();
    }

    /** 이동 취소 보상은 같은 move 도구다. 권한이 회수되면 403으로 실패하고 ai-svc가 rollback_failed로 남긴다. */
    @Test
    void cancelCompensationMoveIsRejectedAfterPermissionIsRevoked() throws Exception {
        DocumentPositionResponse own = authorDocumentIn(edit);
        DocumentPositionResponse moved = placementService.move(workspace, member, own.id(), key(),
                new DocumentPositionRequest(reset, null, own.currentVersion()));
        permissionService.setFolder(workspace, owner, edit, "view");

        assertForbidden(() -> agentTools.execute("move_document", tool(member, moveDocument(moved, edit))));
        assertThat(documents.findById(own.id()).orElseThrow().getFolderId()).isEqualTo(reset);
    }

    // ---------- 휴지통: 삭제 권한 규칙을 그대로 두었다. 현재 동작을 고정한다.

    /** 문서 복구는 항상 최상위로 돌아간다. 소유자가 보기 폴더의 자기 문서를 삭제·복구하면 폴더 밖으로 나온다. */
    @Test
    void trashRestorePutsDocumentAtRoot() {
        DocumentPositionResponse own = authorDocumentIn(view);

        var deleted = documentService.delete(workspace, author, own.id(), key(),
                new DocumentLifecycleRequest(own.currentVersion()));
        documentService.restore(workspace, author, own.id(), key(),
                new DocumentLifecycleRequest(deleted.currentVersion()));

        assertThat(documents.findById(own.id()).orElseThrow().getFolderId()).isNull();
    }

    /** 빈 폴더는 멤버도 지우고 복구할 수 있고 원래 상위로 돌아간다. 내용이 있으면 OWNER만 된다. */
    @Test
    void trashRestoreOfEmptyFolderReturnsToViewParentForMember() {
        FolderResponse empty = folder(owner, "빈 폴더", view);

        var deleted = folderService.delete(workspace, member, empty.id(), key(), empty.currentVersion());
        folderService.restore(workspace, member, empty.id(), key(), deleted.currentVersion());

        assertThat(folders.findById(empty.id()).orElseThrow().getParentFolderId()).isEqualTo(view);
    }

    // ---------- 도우미

    private void assertForbidden(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(DocumentWriteForbiddenException.class).hasMessage(FORBIDDEN);
    }

    /** 작성자 소유 문서를 최상위에 만들고 OWNER가 원하는 위치로 옮겨 둔다. */
    private DocumentPositionResponse authorDocumentIn(UUID folderId) {
        var created = documentService.createMarkdown(workspace, author, key(),
                new MarkdownDocumentCreateRequest("문서 " + UUID.randomUUID(), "# 처음", null));
        return placementService.move(workspace, owner, created.id(), key(),
                new DocumentPositionRequest(folderId, null, created.currentVersion()));
    }

    private FolderResponse folder(String userId, String name, UUID parent) {
        return folderService.create(workspace, userId, key(), new FolderCreateRequest(name + " " + UUID.randomUUID(), parent));
    }

    private String markdown(String userId, UUID folderId) {
        return documentService.createMarkdown(workspace, userId, key(),
                new MarkdownDocumentCreateRequest("새 문서 " + UUID.randomUUID(), "# 본문 " + UUID.randomUUID(), folderId)).id();
    }

    private static MockMultipartFile pdf(String name) {
        return new MockMultipartFile("file", name, "application/pdf", "%PDF-1.4 본문".getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile markdownFile(String name) {
        return new MockMultipartFile("file", name, "text/markdown", "# 본문".getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode moveDocument(DocumentPositionResponse document, UUID target) {
        ObjectNode arguments = json.createObjectNode().put("document_id", document.id());
        if (target == null) {
            arguments.putNull("folder_id");
        } else {
            arguments.put("folder_id", target.toString());
        }
        return arguments.putNull("position").put("base_version", document.currentVersion());
    }

    private AgentToolExecuteRequest tool(String userId, JsonNode arguments) {
        return new AgentToolExecuteRequest("run-1", workspace, userId, "plan-1", 1, "h".repeat(64), "op-1", key(), arguments);
    }

    private UUID folderAt(String location) {
        return switch (location) {
            case "root" -> null;
            case "edit" -> edit;
            case "view" -> view;
            case "inherited" -> inherited;
            case "reset" -> reset;
            default -> throw new IllegalArgumentException(location);
        };
    }

    private String user(String actor) {
        return switch (actor) {
            case "member" -> member;
            case "author" -> author;
            case "owner" -> owner;
            default -> throw new IllegalArgumentException(actor);
        };
    }

    private void role(String userId, String role) {
        role(userId, workspace, role);
    }

    private void role(String userId, String workspaceId, String role) {
        redis.opsForValue().set("authz:role:v2:" + workspaceId + ":" + userId,
                "{\"role\":\"" + role + "\",\"permissions\":[]}");
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
