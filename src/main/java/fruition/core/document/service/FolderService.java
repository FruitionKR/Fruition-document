package fruition.core.document.service;

import fruition.shared.idempotency.IdempotencyService;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.Folder;
import fruition.core.document.dto.BreadcrumbResponse;
import fruition.core.document.dto.DocumentListResponse;
import fruition.core.document.dto.DocumentTreeResponse;
import fruition.core.document.dto.FolderChildrenResponse;
import fruition.core.document.dto.FolderCreateRequest;
import fruition.core.document.dto.FolderLifecycleResponse;
import fruition.core.document.dto.HierarchySearchResponse;
import fruition.core.document.dto.FolderPositionRequest;
import fruition.core.document.dto.FolderRenameRequest;
import fruition.core.document.dto.FolderResponse;
import fruition.core.document.exception.HierarchyCycleException;
import fruition.core.document.exception.HierarchyItemNotFoundException;
import fruition.core.document.exception.HierarchyVersionConflictException;
import fruition.core.document.exception.HierarchyWriteForbiddenException;
import fruition.core.document.exception.InvalidHierarchyRequestException;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FolderService {

    private static final int MAX_NAME_LENGTH = 255;

    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final FolderRepository folderRepository;
    private final DocumentRepository documentRepository;
    private final IdempotencyService idempotencyService;
    private final SiblingReorderer siblingReorderer;
    private final DocumentItemAssembler documentItemAssembler;

    public FolderService(WorkspaceAccessGuard workspaceAccessGuard,
                         FolderRepository folderRepository,
                         DocumentRepository documentRepository,
                         IdempotencyService idempotencyService,
                         SiblingReorderer siblingReorderer,
                         DocumentItemAssembler documentItemAssembler) {
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.folderRepository = folderRepository;
        this.documentRepository = documentRepository;
        this.idempotencyService = idempotencyService;
        this.siblingReorderer = siblingReorderer;
        this.documentItemAssembler = documentItemAssembler;
    }

    @Transactional
    public FolderResponse create(String workspaceId, String userId, String idempotencyKey,
                                 FolderCreateRequest request) {
        String name = normalizeName(request.name());
        UUID parentFolderId = request.parentFolderId();
        String scope = "POST:/api/workspaces/" + workspaceId + "/folders";
        String hash = idempotencyService.requestHash(name, String.valueOf(parentFolderId));
        return idempotencyService.execute(
                userId, scope, idempotencyKey, hash, FolderResponse.class, 201,
                response -> response.id().toString(), () -> {
                    verifyMembership(workspaceId, userId);
                    verifyParentFolder(workspaceId, parentFolderId);
                    Folder folder = new Folder(
                            UUID.randomUUID(), workspaceId, parentFolderId, name,
                            nextSortOrder(workspaceId, parentFolderId));
                    folderRepository.save(folder);
                    return FolderResponse.from(folder);
                });
    }

    @Transactional
    public FolderResponse rename(String workspaceId, String userId, UUID folderId, String idempotencyKey,
                                 FolderRenameRequest request) {
        String name = normalizeName(request.name());
        String scope = "PATCH:/api/workspaces/" + workspaceId + "/folders/" + folderId;
        String hash = idempotencyService.requestHash(name, String.valueOf(request.baseVersion()));
        return idempotencyService.execute(
                userId, scope, idempotencyKey, hash, FolderResponse.class, 200,
                response -> folderId.toString(), () -> {
                    verifyMembership(workspaceId, userId);
                    requireFolder(workspaceId, folderId);
                    int updated = folderRepository.renameIfVersionMatches(
                            folderId, workspaceId, request.baseVersion(), name, Instant.now());
                    if (updated == 0) {
                        throw new HierarchyVersionConflictException("폴더가 이미 변경되어 이름을 변경할 수 없습니다.");
                    }
                    return FolderResponse.from(requireFolder(workspaceId, folderId));
                });
    }

    @Transactional
    public FolderResponse move(String workspaceId, String userId, UUID folderId, String idempotencyKey,
                               FolderPositionRequest request) {
        UUID targetParentId = request.parentFolderId();
        String scope = "PATCH:/api/workspaces/" + workspaceId + "/folders/" + folderId + "/position";
        String hash = idempotencyService.requestHash(
                String.valueOf(targetParentId), String.valueOf(request.position()),
                String.valueOf(request.baseVersion()));
        return idempotencyService.execute(
                userId, scope, idempotencyKey, hash, FolderResponse.class, 200,
                response -> folderId.toString(), () -> {
                    verifyMembership(workspaceId, userId);
                    requireFolder(workspaceId, folderId);
                    if (targetParentId != null) {
                        verifyParentFolder(workspaceId, targetParentId);
                        if (folderRepository.countAncestorMatches(targetParentId, folderId) > 0) {
                            throw new HierarchyCycleException("폴더를 자기 자신이나 하위 폴더로 이동할 수 없습니다.");
                        }
                    }
                    long sortOrder = siblingReorderer.placeFolder(
                            workspaceId, targetParentId, folderId, request.position());
                    int updated = folderRepository.moveIfVersionMatches(
                            folderId, workspaceId, request.baseVersion(), targetParentId, sortOrder, Instant.now());
                    if (updated == 0) {
                        throw new HierarchyVersionConflictException("폴더가 이미 변경되어 이동할 수 없습니다.");
                    }
                    return FolderResponse.from(requireFolder(workspaceId, folderId));
                });
    }

    @Transactional
    public FolderLifecycleResponse delete(String workspaceId, String userId, UUID folderId,
                                          String idempotencyKey, long baseVersion) {
        return deleteFolder(workspaceId, userId, folderId, idempotencyKey, baseVersion, false);
    }

    @Transactional
    public FolderLifecycleResponse deleteEmpty(String workspaceId, String userId, UUID folderId,
                                               String idempotencyKey, long baseVersion) {
        return deleteFolder(workspaceId, userId, folderId, idempotencyKey, baseVersion, true);
    }

    private FolderLifecycleResponse deleteFolder(String workspaceId, String userId, UUID folderId,
                                                 String idempotencyKey, long baseVersion, boolean requireEmpty) {
        String scope = "DELETE:/api/workspaces/" + workspaceId + "/folders/" + folderId;
        if (requireEmpty) scope += "/empty";
        String hash = idempotencyService.requestHash(String.valueOf(baseVersion));
        return idempotencyService.execute(
                userId, scope, idempotencyKey, hash, FolderLifecycleResponse.class, 200,
                response -> folderId.toString(), () -> {
                    verifyMembership(workspaceId, userId);
                    // 추가·이동도 같은 부모 행을 잠그므로 빈 폴더 검사와 삭제 사이에 자식이 들어올 수 없다.
                    folderRepository.findActiveForUpdate(folderId, workspaceId)
                            .orElseThrow(() -> new HierarchyItemNotFoundException("폴더를 찾을 수 없습니다."));
                    if (requireEmpty && hasChildren(workspaceId, folderId)) {
                        throw new HierarchyVersionConflictException("폴더에 다른 항목이 있어 취소 삭제할 수 없습니다.");
                    }
                    if (hasChildren(workspaceId, folderId) && !isWorkspaceOwner(workspaceId, userId)) {
                        throw new HierarchyWriteForbiddenException("내용이 있는 폴더는 워크스페이스 소유자만 삭제할 수 있습니다.");
                    }
                    UUID operationId = UUID.randomUUID();
                    Instant now = Instant.now();
                    int updated = folderRepository.softDeleteRootIfVersionMatches(
                            folderId, workspaceId, baseVersion, userId, now, operationId);
                    if (updated == 0) {
                        throw new HierarchyVersionConflictException("폴더가 이미 변경되어 삭제할 수 없습니다.");
                    }
                    folderRepository.softDeleteDescendantFolders(folderId, userId, now, operationId);
                    documentRepository.softDeleteDocumentsInSubtree(folderId, userId, now, operationId);
                    return new FolderLifecycleResponse(
                            folderId, baseVersion + 1, true, now, operationId);
                });
    }

    @Transactional
    public FolderLifecycleResponse restore(String workspaceId, String userId, UUID folderId,
                                           String idempotencyKey, long baseVersion) {
        String scope = "POST:/api/workspaces/" + workspaceId + "/folders/" + folderId + "/restore";
        String hash = idempotencyService.requestHash(String.valueOf(baseVersion));
        return idempotencyService.execute(
                userId, scope, idempotencyKey, hash, FolderLifecycleResponse.class, 200,
                response -> folderId.toString(), () -> {
                    verifyMembership(workspaceId, userId);
                    Folder deleted = folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNotNull(
                                    folderId, workspaceId)
                            .orElseThrow(() -> new HierarchyItemNotFoundException(
                                    "삭제된 폴더를 찾을 수 없습니다."));
                    UUID operationId = deleted.getDeleteOperationId();
                    UUID originalParent = deleted.getParentFolderId();
                    boolean parentActive = originalParent == null
                            || folderRepository.findActiveForUpdate(
                                    originalParent, workspaceId).isPresent();
                    UUID targetParent = parentActive ? originalParent : null;
                    long targetSortOrder = parentActive
                            ? deleted.getSortOrder() : nextSortOrder(workspaceId, null);
                    Instant now = Instant.now();
                    int updated = folderRepository.restoreRootIfVersionMatches(
                            folderId, workspaceId, baseVersion, targetParent, targetSortOrder, now);
                    if (updated == 0) {
                        throw new HierarchyVersionConflictException("폴더가 이미 변경되어 복구할 수 없습니다.");
                    }
                    if (operationId != null) {
                        folderRepository.restoreDescendantFolders(folderId, operationId, now);
                        documentRepository.restoreDocumentsInSubtree(folderId, operationId, now);
                    }
                    return new FolderLifecycleResponse(
                            folderId, baseVersion + 1, false, null, null);
                });
    }

    @Transactional(readOnly = true)
    public FolderChildrenResponse children(String workspaceId, String userId, UUID folderId) {
        verifyMembership(workspaceId, userId);
        if (folderId != null) {
            requireFolder(workspaceId, folderId);
        }

        List<FolderChildrenResponse.Item> items = new ArrayList<>();
        for (Folder child : folderRepository.findChildren(workspaceId, folderId)) {
            items.add(FolderChildrenResponse.Item.folder(
                    child.getId().toString(), child.getName(), child.getSortOrder(),
                    child.getCurrentVersion(),
                    hasChildren(workspaceId, child.getId())));
        }
        for (Document child : documentRepository.findChildDocuments(workspaceId, folderId)) {
            items.add(FolderChildrenResponse.Item.document(
                    child.getId(), child.getDisplayName(), child.getSortOrder(), child.getCurrentVersion()));
        }
        items.sort(Comparator.comparingLong(FolderChildrenResponse.Item::sortOrder)
                .thenComparing(FolderChildrenResponse.Item::id));
        return new FolderChildrenResponse(items);
    }

    @Transactional(readOnly = true)
    public DocumentTreeResponse tree(String workspaceId, String userId) {
        verifyMembership(workspaceId, userId);

        Map<UUID, List<Folder>> foldersByParent = new HashMap<>();
        for (Folder folder : folderRepository.findAllByWorkspaceIdAndDeletedAtIsNull(workspaceId)) {
            foldersByParent.computeIfAbsent(folder.getParentFolderId(), ignored -> new ArrayList<>())
                    .add(folder);
        }

        Map<UUID, List<Document>> documentsByFolder = new HashMap<>();
        List<Document> documents = documentRepository.findVisibleByWorkspaceId(workspaceId);
        for (Document document : documents) {
            documentsByFolder.computeIfAbsent(document.getFolderId(), ignored -> new ArrayList<>())
                    .add(document);
        }
        // 화면이 계층과 문서 상태를 함께 쓰므로 목록 조회와 같은 항목을 실어 보낸다.
        Map<String, DocumentListResponse.DocumentItem> itemsById = documentItemAssembler.assemble(documents)
                .stream()
                .collect(Collectors.toMap(DocumentListResponse.DocumentItem::id, item -> item));

        return new DocumentTreeResponse(
                buildTreeItems(null, foldersByParent, documentsByFolder, itemsById, new HashSet<>()));
    }

    private List<DocumentTreeResponse.Item> buildTreeItems(
            UUID parentFolderId,
            Map<UUID, List<Folder>> foldersByParent,
            Map<UUID, List<Document>> documentsByFolder,
            Map<String, DocumentListResponse.DocumentItem> itemsById,
            Set<UUID> ancestorFolderIds
    ) {
        List<DocumentTreeResponse.Item> items = new ArrayList<>();
        for (Folder folder : foldersByParent.getOrDefault(parentFolderId, List.of())) {
            if (ancestorFolderIds.contains(folder.getId())) {
                continue;
            }
            Set<UUID> nextAncestors = new HashSet<>(ancestorFolderIds);
            nextAncestors.add(folder.getId());
            items.add(DocumentTreeResponse.Item.folder(
                    folder.getId().toString(),
                    folder.getName(),
                    folder.getSortOrder(),
                    folder.getCurrentVersion(),
                    buildTreeItems(folder.getId(), foldersByParent, documentsByFolder, itemsById, nextAncestors)
            ));
        }
        for (Document document : documentsByFolder.getOrDefault(parentFolderId, List.of())) {
            // 문서의 name은 파일명이다. 화면이 확장자로 종류를 가리므로 display_name을 쓰면
            // Markdown 문서가 아닌 것으로 취급된다. 사람이 붙인 이름은 document.display_name에 있다.
            items.add(DocumentTreeResponse.Item.document(
                    document.getId(), document.getFilename(), document.getSortOrder(),
                    document.getCurrentVersion(), itemsById.get(document.getId())));
        }
        items.sort(Comparator.comparingLong(DocumentTreeResponse.Item::sortOrder)
                .thenComparing(DocumentTreeResponse.Item::id));
        return items;
    }

    @Transactional(readOnly = true)
    public BreadcrumbResponse breadcrumb(String workspaceId, String userId, UUID folderId, String documentId) {
        verifyMembership(workspaceId, userId);
        if ((folderId == null) == (documentId == null)) {
            throw new InvalidHierarchyRequestException("folder_id 또는 document_id 중 하나만 지정해야 합니다.");
        }
        if (documentId != null) {
            Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                    .orElseThrow(() -> new HierarchyItemNotFoundException("문서를 찾을 수 없습니다."));
            List<BreadcrumbResponse.Node> nodes = folderPathNodes(document.getFolderId());
            nodes.add(BreadcrumbResponse.Node.document(document.getId(), document.getDisplayName()));
            return new BreadcrumbResponse(nodes);
        }
        requireFolder(workspaceId, folderId);
        return new BreadcrumbResponse(folderPathNodes(folderId));
    }

    @Transactional(readOnly = true)
    public HierarchySearchResponse search(String workspaceId, String userId, String query) {
        verifyMembership(workspaceId, userId);
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            throw new InvalidHierarchyRequestException("검색어를 입력해야 합니다.");
        }
        String pattern = "%" + trimmed.toLowerCase() + "%";
        List<HierarchySearchResponse.Match> results = new ArrayList<>();
        for (Folder folder : folderRepository.searchByName(workspaceId, pattern)) {
            results.add(new HierarchySearchResponse.Match(
                    "folder", folder.getId().toString(), folder.getName(),
                    folderPathNodes(folder.getParentFolderId())));
        }
        for (Document document : documentRepository.searchByName(workspaceId, pattern)) {
            results.add(new HierarchySearchResponse.Match(
                    "document", document.getId(), document.getDisplayName(),
                    folderPathNodes(document.getFolderId())));
        }
        return new HierarchySearchResponse(results);
    }

    /** 최상위부터 지정 폴더까지의 폴더 노드 목록. folderId가 null이면 빈 목록. */
    private List<BreadcrumbResponse.Node> folderPathNodes(UUID folderId) {
        List<BreadcrumbResponse.Node> nodes = new ArrayList<>();
        if (folderId == null) {
            return nodes;
        }
        for (Folder folder : folderRepository.findAncestorPath(folderId)) {
            nodes.add(BreadcrumbResponse.Node.folder(folder.getId().toString(), folder.getName()));
        }
        return nodes;
    }

    private boolean hasChildren(String workspaceId, UUID folderId) {
        return folderRepository.existsByWorkspaceIdAndParentFolderIdAndDeletedAtIsNull(workspaceId, folderId)
                || documentRepository.existsByWorkspaceIdAndFolderIdAndDeletedAtIsNull(workspaceId, folderId);
    }

    private long nextSortOrder(String workspaceId, UUID parentFolderId) {
        long folderMax = folderRepository.findMaxSortOrder(workspaceId, parentFolderId);
        long documentMax = documentRepository.findMaxSortOrderInFolder(workspaceId, parentFolderId);
        return Math.max(folderMax, documentMax) + 1;
    }

    private Folder requireFolder(String workspaceId, UUID folderId) {
        return folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(folderId, workspaceId)
                .orElseThrow(() -> new HierarchyItemNotFoundException("폴더를 찾을 수 없습니다."));
    }

    private void verifyParentFolder(String workspaceId, UUID parentFolderId) {
        if (parentFolderId != null
                && folderRepository.findActiveForUpdate(parentFolderId, workspaceId).isEmpty()) {
            throw new HierarchyItemNotFoundException("상위 폴더를 찾을 수 없습니다.");
        }
    }

    private void verifyMembership(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
    }

    private boolean isWorkspaceOwner(String workspaceId, String userId) {
        return workspaceAccessGuard.isOwner(workspaceId, userId);
    }

    private String normalizeName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw new InvalidHierarchyRequestException("폴더 이름을 입력해야 합니다.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new InvalidHierarchyRequestException("폴더 이름은 255자 이하여야 합니다.");
        }
        return name;
    }
}
