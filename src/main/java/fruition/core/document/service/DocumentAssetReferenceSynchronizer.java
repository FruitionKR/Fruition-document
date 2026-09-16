package fruition.core.document.service;

import fruition.core.document.domain.DocumentAsset;
import fruition.core.document.domain.DocumentAssetReference;
import fruition.core.document.exception.InvalidDocumentAssetException;
import fruition.core.document.repository.DocumentAssetReferenceRepository;
import fruition.core.document.repository.DocumentAssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DocumentAssetReferenceSynchronizer {

    private final DocumentAssetRepository assetRepository;
    private final DocumentAssetReferenceRepository referenceRepository;

    public DocumentAssetReferenceSynchronizer(
            DocumentAssetRepository assetRepository,
            DocumentAssetReferenceRepository referenceRepository
    ) {
        this.assetRepository = assetRepository;
        this.referenceRepository = referenceRepository;
    }

    @Transactional
    public void synchronize(
            String documentId,
            String workspaceId,
            Set<DocumentAssetReferenceParser.ManagedAssetReference> requestedReferences
    ) {
        Set<UUID> requestedIds = validateWorkspace(requestedReferences, workspaceId);
        Map<UUID, DocumentAsset> requestedAssets = loadRequestedAssets(requestedIds, workspaceId);
        List<DocumentAssetReference> currentReferences =
                referenceRepository.findAllByIdDocumentId(documentId);
        Set<UUID> currentIds = currentReferences.stream()
                .map(DocumentAssetReference::getAssetId)
                .collect(Collectors.toSet());

        Set<UUID> addedIds = difference(requestedIds, currentIds);
        Set<UUID> removedIds = difference(currentIds, requestedIds);
        Instant now = Instant.now();

        for (UUID addedId : addedIds) {
            referenceRepository.save(new DocumentAssetReference(documentId, addedId, now));
        }
        requestedAssets.values().forEach(DocumentAsset::markReferenced);

        List<DocumentAssetReference> removedReferences = currentReferences.stream()
                .filter(reference -> removedIds.contains(reference.getAssetId()))
                .toList();
        referenceRepository.deleteAll(removedReferences);
        referenceRepository.flush();

        for (UUID removedId : removedIds) {
            if (!referenceRepository.existsByIdAssetId(removedId)) {
                assetRepository.findById(removedId).ifPresent(asset -> asset.markUnreferenced(now));
            }
        }
    }

    @Transactional
    public void copyReferences(String sourceDocumentId, String targetDocumentId) {
        Instant now = Instant.now();
        List<DocumentAssetReference> copies = referenceRepository.findAllByIdDocumentId(sourceDocumentId).stream()
                .map(reference -> new DocumentAssetReference(
                        targetDocumentId, reference.getAssetId(), now))
                .toList();
        referenceRepository.saveAll(copies);
    }

    private Set<UUID> validateWorkspace(
            Set<DocumentAssetReferenceParser.ManagedAssetReference> references,
            String workspaceId
    ) {
        Set<UUID> ids = new HashSet<>();
        for (DocumentAssetReferenceParser.ManagedAssetReference reference : references) {
            if (!workspaceId.equals(reference.workspaceId())) {
                throw invalid("다른 워크스페이스의 이미지 asset을 참조할 수 없습니다.");
            }
            ids.add(reference.assetId());
        }
        return Set.copyOf(ids);
    }

    private Map<UUID, DocumentAsset> loadRequestedAssets(Collection<UUID> ids, String workspaceId) {
        if (ids.isEmpty()) return Map.of();
        Map<UUID, DocumentAsset> assets = assetRepository.findAllByIdInAndWorkspaceId(ids, workspaceId).stream()
                .collect(Collectors.toMap(DocumentAsset::getId, Function.identity()));
        if (assets.size() != ids.size()) {
            throw invalid("존재하지 않거나 접근할 수 없는 이미지 asset이 포함되어 있습니다.");
        }
        return assets;
    }

    private Set<UUID> difference(Set<UUID> left, Set<UUID> right) {
        Set<UUID> difference = new HashSet<>(left);
        difference.removeAll(right);
        return difference;
    }

    private InvalidDocumentAssetException invalid(String message) {
        return new InvalidDocumentAssetException(message);
    }
}
