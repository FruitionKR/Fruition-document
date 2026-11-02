package fruition.core.document.service;

import fruition.core.document.dto.DocumentAttachmentSaveResponse;
import fruition.core.document.dto.DocumentContentSaveResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DocumentAssetContentService {

    private final DocumentAssetSaveRequestParser requestParser;
    private final DocumentAssetValidator assetValidator;
    private final DocumentAssetStorageCoordinator storageCoordinator;
    private final DocumentService documentService;

    /** 가져오기 세션 확인. 단위 테스트처럼 주입되지 않으면 가져오기 저장을 받지 않는다. */
    @org.springframework.beans.factory.annotation.Autowired
    private fruition.core.imports.ImportSessionStore importSessions;

    public DocumentAssetContentService(
            DocumentAssetSaveRequestParser requestParser,
            DocumentAssetValidator assetValidator,
            DocumentAssetStorageCoordinator storageCoordinator,
            DocumentService documentService
    ) {
        this.requestParser = requestParser;
        this.assetValidator = assetValidator;
        this.storageCoordinator = storageCoordinator;
        this.documentService = documentService;
    }

    public DocumentContentSaveResponse save(
            String workspaceId,
            String userId,
            String documentId,
            String metadataJson,
            MultiValueMap<String, MultipartFile> fileParts,
            String applyOperationId
    ) {
        return save(workspaceId, userId, documentId, metadataJson, fileParts, applyOperationId, null);
    }

    /**
     * @param importId {@code X-Fruition-Import-Id}. 있으면 그 가져오기 세션이 만든 문서에만 저장할 수 있고, 새 이미지는
     *                 세션 항목으로 센다. 이미지 크기·개수·저장 용량 한도는 그대로다.
     */
    public DocumentContentSaveResponse save(
            String workspaceId,
            String userId,
            String documentId,
            String metadataJson,
            MultiValueMap<String, MultipartFile> fileParts,
            String applyOperationId,
            String importId
    ) {
        var request = requestParser.parse(metadataJson, fileParts);
        documentService.validateContentSavePreconditions(workspaceId, userId, documentId);
        var importSession = importId == null ? null : importSessions.requireOpen(workspaceId, userId, importId);
        if (importSession != null) importSessions.requireDocument(importSession, documentId);

        Map<UUID, DocumentAssetValidator.ValidatedAsset> validated =
                assetValidator.validateAll(request.attachments());
        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.storedMarkdown(request.markdown());
        String revisionWriteId = revisionWriteId(request.baseVersion(), content.contentHash(), validated);
        Map<UUID, DocumentAssetStorageCoordinator.StoredAsset> stored =
                storageCoordinator.storeAll(workspaceId, documentId, request.baseVersion(), validated);
        String finalMarkdown = replacePlaceholders(request.markdown(), workspaceId, stored);
        try {
            DocumentContentSaveResponse saved = documentService.saveContentWithAssets(
                    workspaceId, userId, documentId, finalMarkdown, request.baseVersion(),
                    revisionWriteId, stored, applyOperationId, importSession);
            // 성공한 재전송도 다른 시도가 먼저 커밋했다면 자기 객체는 쓰이지 않는다.
            // coordinator는 DB가 참조하는 key를 남기므로 정상 저장의 객체는 지우지 않는다.
            storageCoordinator.compensate(stored.values());
            return new DocumentContentSaveResponse(
                    saved.documentId(), saved.currentVersion(), saved.contentHash(), saved.updatedAt(),
                    saved.changed(), saved.markdown(), attachmentResponses(workspaceId, stored), saved.merge());
        } catch (RuntimeException exception) {
            storageCoordinator.compensate(stored.values());
            throw exception;
        }
    }

    private String revisionWriteId(
            long baseVersion,
            String markdownHash,
            Map<UUID, DocumentAssetValidator.ValidatedAsset> validated
    ) {
        String attachments = validated.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue().originalFilename()
                        + ":" + entry.getValue().contentHash())
                .collect(Collectors.joining("\0"));
        try {
            return "assets:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (baseVersion + "\0" + markdownHash + "\0" + attachments)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private String replacePlaceholders(
            String markdown,
            String workspaceId,
            Map<UUID, DocumentAssetStorageCoordinator.StoredAsset> stored
    ) {
        String replaced = markdown;
        for (Map.Entry<UUID, DocumentAssetStorageCoordinator.StoredAsset> entry : stored.entrySet()) {
            replaced = replaced.replace(
                    "attachment://" + entry.getKey(),
                    contentPath(workspaceId, entry.getValue().assetId()));
        }
        return replaced;
    }

    private List<DocumentAttachmentSaveResponse> attachmentResponses(
            String workspaceId,
            Map<UUID, DocumentAssetStorageCoordinator.StoredAsset> stored
    ) {
        return stored.entrySet().stream()
                .map(entry -> new DocumentAttachmentSaveResponse(
                        entry.getKey(), entry.getValue().assetId(),
                        contentPath(workspaceId, entry.getValue().assetId())))
                .toList();
    }

    private String contentPath(String workspaceId, UUID assetId) {
        return "/api/workspaces/" + workspaceId + "/assets/" + assetId + "/content";
    }
}
