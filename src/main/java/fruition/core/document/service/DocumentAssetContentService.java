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
        var request = requestParser.parse(metadataJson, fileParts);
        documentService.validateContentSavePreconditions(workspaceId, userId, documentId);

        Map<UUID, DocumentAssetValidator.ValidatedAsset> validated =
                assetValidator.validateAll(request.attachments());
        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.markdown(request.markdown());
        String revisionWriteId = revisionWriteId(request.baseVersion(), content.contentHash(), validated);
        Map<UUID, DocumentAssetStorageCoordinator.StoredAsset> stored =
                storageCoordinator.storeAll(workspaceId, documentId, request.baseVersion(), validated);
        String finalMarkdown = replacePlaceholders(request.markdown(), workspaceId, stored);
        try {
            DocumentContentSaveResponse saved = documentService.saveContentWithAssets(
                    workspaceId, userId, documentId, finalMarkdown, request.baseVersion(),
                    revisionWriteId, stored, applyOperationId);
            if (!saved.changed()) storageCoordinator.compensate(stored.values());
            return new DocumentContentSaveResponse(
                    saved.documentId(), saved.currentVersion(), saved.contentHash(), saved.updatedAt(),
                    saved.changed(), saved.markdown(), attachmentResponses(workspaceId, stored));
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
