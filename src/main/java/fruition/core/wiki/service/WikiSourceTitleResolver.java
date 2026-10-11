package fruition.core.wiki.service;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.Folder;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 소스 페이지의 표시 제목을 문서의 현재 상태로 계산한다.
 *
 * <p>ai는 편입 시점의 파일명을 제목으로 저장하므로 이름 변경·이동은 재편입 전까지 반영되지 않는다.
 * 문서 이름은 폴더 안에서만 유일하므로(V51), 같은 표시 이름의 활성 문서가 워크스페이스에 더 있으면
 * {@code 이름 · 폴더/경로}로 구분한다. 최상위 폴더 문서는 {@link #ROOT_LABEL}을 경로로 쓴다.
 */
@Component
public class WikiSourceTitleResolver {

    static final String ROOT_LABEL = "최상위";
    private static final String SEPARATOR = " · ";
    /** 폴더가 꼬여 순환이 생겨도 멈추도록 거슬러 올라가는 깊이를 제한한다(DocumentAccessPolicy와 같은 값). */
    private static final int MAX_DEPTH = 64;

    private final DocumentRepository documentRepository;
    private final FolderRepository folderRepository;

    public WikiSourceTitleResolver(DocumentRepository documentRepository, FolderRepository folderRepository) {
        this.documentRepository = documentRepository;
        this.folderRepository = folderRepository;
    }

    /**
     * 문서 ID별 표시 제목. 없거나 삭제됐거나 다른 워크스페이스 문서는 결과에서 빠지므로 호출자가 ai 제목을 유지한다.
     * 조회는 문서 1회, 이름 충돌 1회, (충돌이 있을 때만) 폴더 1회다.
     */
    public Map<String, String> titles(String workspaceId, Collection<Document> documents) {
        List<Document> active = documents.stream()
                .filter(d -> d.getDeletedAt() == null && workspaceId.equals(d.getWorkspaceId()))
                .toList();
        if (active.isEmpty()) {
            return Map.of();
        }
        Set<String> names = active.stream().map(Document::getDisplayName).collect(Collectors.toSet());
        Set<String> duplicated = Set.copyOf(documentRepository.findDuplicatedDisplayNames(workspaceId, names));
        Map<UUID, Folder> folders = duplicated.isEmpty() ? Map.of() : folderRepository
                .findAllByWorkspaceIdAndDeletedAtIsNull(workspaceId).stream()
                .collect(Collectors.toMap(Folder::getId, f -> f));

        Map<String, String> titles = new HashMap<>();
        for (Document document : active) {
            String name = document.getDisplayName();
            titles.put(document.getId(), duplicated.contains(name)
                    ? name + SEPARATOR + pathOf(document.getFolderId(), folders)
                    : name);
        }
        return titles;
    }

    private static String pathOf(UUID folderId, Map<UUID, Folder> folders) {
        List<String> names = new ArrayList<>();
        UUID current = folderId;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            Folder folder = folders.get(current);
            if (folder == null) {
                break;
            }
            names.add(0, folder.getName());
            current = folder.getParentFolderId();
        }
        return names.isEmpty() ? ROOT_LABEL : String.join("/", names);
    }
}
