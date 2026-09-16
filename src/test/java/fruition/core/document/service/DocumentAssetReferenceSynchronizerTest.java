package fruition.core.document.service;

import fruition.core.document.domain.DocumentAsset;
import fruition.core.document.domain.DocumentAssetReference;
import fruition.core.document.exception.InvalidDocumentAssetException;
import fruition.core.document.repository.DocumentAssetReferenceRepository;
import fruition.core.document.repository.DocumentAssetRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentAssetReferenceSynchronizerTest {

    private final DocumentAssetRepository assetRepository = mock(DocumentAssetRepository.class);
    private final DocumentAssetReferenceRepository referenceRepository =
            mock(DocumentAssetReferenceRepository.class);
    private final DocumentAssetReferenceSynchronizer synchronizer =
            new DocumentAssetReferenceSynchronizer(assetRepository, referenceRepository);

    @Test
    void synchronize_rejectsCrossWorkspaceReferenceBeforeDatabaseMutation() {
        UUID assetId = UUID.randomUUID();

        assertThatThrownBy(() -> synchronizer.synchronize(
                "doc_1", "ws_1", Set.of(reference("ws_2", assetId))))
                .isInstanceOf(InvalidDocumentAssetException.class);
        verify(referenceRepository, never()).save(any());
    }

    @Test
    void synchronize_rejectsMissingAsset() {
        UUID assetId = UUID.randomUUID();
        when(assetRepository.findAllByIdInAndWorkspaceId(Set.of(assetId), "ws_1"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> synchronizer.synchronize(
                "doc_1", "ws_1", Set.of(reference("ws_1", assetId))))
                .isInstanceOf(InvalidDocumentAssetException.class);
    }

    @Test
    void synchronize_addsAndRemovesDiffAndMarksLastRemovedAssetUnreferenced() {
        UUID removedId = UUID.randomUUID();
        UUID retainedId = UUID.randomUUID();
        UUID addedId = UUID.randomUUID();
        DocumentAsset retained = asset(retainedId);
        DocumentAsset added = asset(addedId);
        DocumentAsset removed = asset(removedId);
        retained.markUnreferenced(Instant.now());
        added.markUnreferenced(Instant.now());
        when(assetRepository.findAllByIdInAndWorkspaceId(Set.of(retainedId, addedId), "ws_1"))
                .thenReturn(List.of(retained, added));
        when(referenceRepository.findAllByIdDocumentId("doc_1")).thenReturn(List.of(
                new DocumentAssetReference("doc_1", removedId, Instant.now()),
                new DocumentAssetReference("doc_1", retainedId, Instant.now())
        ));
        when(referenceRepository.existsByIdAssetId(removedId)).thenReturn(false);
        when(assetRepository.findById(removedId)).thenReturn(Optional.of(removed));

        synchronizer.synchronize("doc_1", "ws_1", Set.of(
                reference("ws_1", retainedId), reference("ws_1", addedId)));

        ArgumentCaptor<DocumentAssetReference> addedReference =
                ArgumentCaptor.forClass(DocumentAssetReference.class);
        verify(referenceRepository).save(addedReference.capture());
        assertThat(addedReference.getValue().getAssetId()).isEqualTo(addedId);
        verify(referenceRepository).deleteAll(any());
        verify(referenceRepository).flush();
        assertThat(retained.getUnreferencedSince()).isNull();
        assertThat(added.getUnreferencedSince()).isNull();
        assertThat(removed.getUnreferencedSince()).isNotNull();
    }

    private DocumentAssetReferenceParser.ManagedAssetReference reference(String workspaceId, UUID assetId) {
        return new DocumentAssetReferenceParser.ManagedAssetReference(workspaceId, assetId);
    }

    private DocumentAsset asset(UUID id) {
        return new DocumentAsset(
                id, "ws_1", "user_1", "image.png", "image/png", 3,
                1, 1, "a".repeat(64), "assets/ws_1/" + id + "/content", Instant.now());
    }
}
