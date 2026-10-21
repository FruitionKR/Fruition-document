package fruition.core.skill.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.exception.HierarchyItemNotFoundException;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.DocumentEditStateInitializer;
import fruition.core.skill.dto.SkillFolderSourceReadResponse;
import fruition.core.skill.exception.SkillFolderSourceForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 폴더 바로 아래(하위 폴더 제외) Markdown 편집 문서 중 사용자가 읽을 수 있고 본문이 비어 있지 않은 문서의 최신 본문을 돌려준다.
 * 문서별 읽기 권한은 {@link DocumentAccessPolicy}로 판정한다.
 */
@Service
public class SkillFolderSourceService {

    static final int MIN_DOCUMENTS = 1;
    static final int MAX_DOCUMENTS = 50;
    private static final Logger log = LoggerFactory.getLogger(SkillFolderSourceService.class);

    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final DocumentAccessPolicy documentAccessPolicy;
    private final FolderRepository folderRepository;
    private final DocumentRepository documentRepository;
    private final DocumentEditStateRepository editStateRepository;
    private final DocumentEditStateInitializer editStateInitializer;

    public SkillFolderSourceService(
            WorkspaceAccessGuard workspaceAccessGuard,
            DocumentAccessPolicy documentAccessPolicy,
            FolderRepository folderRepository,
            DocumentRepository documentRepository,
            DocumentEditStateRepository editStateRepository,
            DocumentEditStateInitializer editStateInitializer
    ) {
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.documentAccessPolicy = documentAccessPolicy;
        this.folderRepository = folderRepository;
        this.documentRepository = documentRepository;
        this.editStateRepository = editStateRepository;
        this.editStateInitializer = editStateInitializer;
    }

    public SkillFolderSourceReadResponse read(String workspaceId, String userId, String folderId, int maxDocuments) {
        requireMember(workspaceId, userId);
        UUID folder = activeFolder(workspaceId, folderId);
        DocumentAccessPolicy.Viewer viewer = documentAccessPolicy.viewer(workspaceId, userId);
        if (!viewer.canReadFolder(folder)) {
            throw new SkillFolderSourceForbiddenException();
        }
        List<Document> readable = documentRepository.findChildDocuments(workspaceId, folder).stream()
                .filter(document -> document.getDocumentRole() == DocumentRole.EDITABLE)
                .filter(viewer::canRead)
                .sorted(Comparator.comparing(Document::getUpdatedAt,
                                Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
                        .thenComparing(Document::getId))
                .toList();
        int limit = Math.max(MIN_DOCUMENTS, Math.min(MAX_DOCUMENTS, maxDocuments));
        List<Document> withBody = withBody(readable);
        // 본문을 만들지 못했거나 비어 있는(공백뿐인) 문서는 건너뛰고 다음 문서로 limit을 채운다. 건너뛴 문서는 전체 수에서도 뺀다.
        List<SkillFolderSourceReadResponse.Item> items = new ArrayList<>();
        int skipped = 0;
        for (Document document : withBody) {
            if (items.size() == limit) {
                break;
            }
            String markdown = markdown(document);
            if (markdown == null || markdown.isBlank()) {
                skipped++;
                continue;
            }
            items.add(new SkillFolderSourceReadResponse.Item(
                    document.getId(), document.getDisplayName(), document.getUpdatedAt(), markdown));
        }
        return new SkillFolderSourceReadResponse(withBody.size() - skipped, items);
    }

    /**
     * 본문이 있는 문서만 남긴다: 편집 상태가 있거나, 없더라도 저장소 본문으로 만들 수 있는(source_uri가 있는) 문서.
     * 편집 상태 존재 여부는 한 번에 읽고, 저장소 본문은 실제로 실을 문서만 만든다.
     */
    private List<Document> withBody(List<Document> documents) {
        if (documents.isEmpty()) {
            return List.of();
        }
        Set<String> withEditState = new HashSet<>(editStateRepository.findExistingDocumentIds(
                documents.stream().map(Document::getId).toList()));
        return documents.stream()
                .filter(document -> withEditState.contains(document.getId())
                        || (document.getSourceUri() != null && !document.getSourceUri().isBlank()))
                .toList();
    }

    /** 멤버가 아니면 폴더 존재 여부를 알리지 않고 403으로 거절한다. */
    private void requireMember(String workspaceId, String userId) {
        try {
            workspaceAccessGuard.requireMember(workspaceId, userId);
        } catch (WorkspaceNotFoundException e) {
            throw new SkillFolderSourceForbiddenException();
        }
    }

    private UUID activeFolder(String workspaceId, String folderId) {
        UUID folder;
        try {
            folder = UUID.fromString(folderId);
        } catch (IllegalArgumentException e) {
            throw new HierarchyItemNotFoundException("폴더를 찾을 수 없습니다.");
        }
        if (folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(folder, workspaceId).isEmpty()) {
            throw new HierarchyItemNotFoundException("폴더를 찾을 수 없습니다.");
        }
        return folder;
    }

    /** 편집 상태가 아직 없는 예전 문서는 저장소 본문으로 만든다. 만들지 못하면 null(응답에서 빠진다). */
    private String markdown(Document document) {
        try {
            editStateInitializer.initializeIfNeeded(document);
        } catch (RuntimeException e) {
            log.warn("폴더 원문 편집 상태 생성 실패 documentId={}", document.getId(), e);
            return null;
        }
        return editStateRepository.findById(document.getId())
                .map(DocumentEditState::getMarkdown)
                .orElse(null);
    }
}
