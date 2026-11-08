package fruition.core.document.service;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.Folder;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 대상 부모 폴더 안의 폴더·문서 형제를 비관적 잠금으로 잠그고, 이동 항목을 지정 위치(position)에 넣어
 * 공용 sort_order를 재배열한다. 이동 항목 자신의 sort_order는 반환값으로 돌려주고 여기서 쓰지 않는다.
 * 호출자의 트랜잭션 안에서 실행된다.
 */
@Service
public class SiblingReorderer {

    private static final String FOLDER = "folder";
    private static final String DOCUMENT = "document";

    private final FolderRepository folderRepository;
    private final DocumentRepository documentRepository;

    public SiblingReorderer(FolderRepository folderRepository, DocumentRepository documentRepository) {
        this.folderRepository = folderRepository;
        this.documentRepository = documentRepository;
    }

    public long placeFolder(String workspaceId, UUID parentFolderId, UUID movedFolderId, Integer position) {
        return place(workspaceId, parentFolderId, FOLDER, movedFolderId.toString(), position);
    }

    public long placeDocument(String workspaceId, UUID parentFolderId, String movedDocumentId, Integer position) {
        return place(workspaceId, parentFolderId, DOCUMENT, movedDocumentId, position);
    }

    public boolean folderPlacementUnchanged(String workspaceId, UUID parentFolderId, UUID folderId,
                                            Integer position) {
        return placementUnchanged(workspaceId, parentFolderId, FOLDER, folderId.toString(), position);
    }

    public boolean documentPlacementUnchanged(String workspaceId, UUID parentFolderId, String documentId,
                                              Integer position) {
        return placementUnchanged(workspaceId, parentFolderId, DOCUMENT, documentId, position);
    }

    private boolean placementUnchanged(String workspaceId, UUID parentFolderId, String movedType,
                                       String movedId, Integer position) {
        List<Sibling> siblings = siblings(workspaceId, parentFolderId, null, null);
        int current = -1;
        for (int index = 0; index < siblings.size(); index++) {
            Sibling sibling = siblings.get(index);
            if (sibling.type().equals(movedType) && sibling.id().equals(movedId)) current = index;
        }
        if (current < 0) return false;
        int desired = position == null ? siblings.size() - 1
                : Math.max(0, Math.min(position, siblings.size() - 1));
        return current == desired;
    }

    private long place(String workspaceId, UUID parentFolderId, String movedType, String movedId, Integer position) {
        List<Sibling> siblings = siblings(workspaceId, parentFolderId, movedType, movedId);
        int index = position == null ? siblings.size() : Math.max(0, Math.min(position, siblings.size()));
        Instant now = Instant.now();
        for (int i = 0; i < siblings.size(); i++) {
            long newOrder = i < index ? i : i + 1;
            Sibling sibling = siblings.get(i);
            if (sibling.sortOrder() != newOrder) {
                if (FOLDER.equals(sibling.type())) {
                    folderRepository.updateSortOrder(UUID.fromString(sibling.id()), workspaceId, newOrder, now);
                } else {
                    documentRepository.updateSortOrder(sibling.id(), workspaceId, newOrder, now);
                }
            }
        }
        return index;
    }

    private List<Sibling> siblings(String workspaceId, UUID parentFolderId, String excludedType, String excludedId) {
        List<Sibling> siblings = new ArrayList<>();
        for (Folder folder : folderRepository.findChildrenForUpdate(workspaceId, parentFolderId)) {
            if (!(FOLDER.equals(excludedType) && folder.getId().toString().equals(excludedId))) {
                siblings.add(new Sibling(FOLDER, folder.getId().toString(), folder.getSortOrder()));
            }
        }
        for (Document document : documentRepository.findChildDocumentsForUpdate(workspaceId, parentFolderId)) {
            if (!(DOCUMENT.equals(excludedType) && document.getId().equals(excludedId))) {
                siblings.add(new Sibling(DOCUMENT, document.getId(), document.getSortOrder()));
            }
        }
        siblings.sort(Comparator.comparingLong(Sibling::sortOrder).thenComparing(Sibling::id));
        return siblings;
    }

    private record Sibling(String type, String id, long sortOrder) {}
}
