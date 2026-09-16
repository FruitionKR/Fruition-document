package fruition.core.document.service;

import fruition.core.document.domain.DocumentAsset;
import fruition.core.document.exception.DocumentAssetNotFoundException;
import fruition.core.document.repository.DocumentAssetRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentAssetReadServiceTest {

    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock DocumentAssetRepository assetRepository;
    @Mock DocumentAssetObjectStorage objectStorage;

    @Test
    void readMetadata_memberReceivesVerifiedMetadataWithoutTouchingStorage() {
        UUID assetId = UUID.randomUUID();
        DocumentAsset asset = asset(assetId, "ws_1");
        when(assetRepository.findByIdAndWorkspaceId(assetId, "ws_1")).thenReturn(Optional.of(asset));

        var result = service().readMetadata("ws_1", "user_1", assetId);

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.contentLength()).isEqualTo(3);
        assertThat(result.etag()).isEqualTo("\"" + "a".repeat(64) + "\"");
        assertThat(result.storageKey()).isEqualTo("assets/ws_1/content");
        verify(objectStorage, never()).get(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void openStream_readsObjectForStoredKey() {
        UUID assetId = UUID.randomUUID();
        DocumentAsset asset = asset(assetId, "ws_1");
        ByteArrayInputStream stream = new ByteArrayInputStream(new byte[]{1, 2, 3});
        when(assetRepository.findByIdAndWorkspaceId(assetId, "ws_1")).thenReturn(Optional.of(asset));
        when(objectStorage.get("assets/ws_1/content")).thenReturn(stream);

        DocumentAssetReadService service = service();

        assertThat(service.openStream(service.readMetadata("ws_1", "user_1", assetId))).isSameAs(stream);
    }

    @Test
    void readMetadata_nonMemberReturnsNotFoundWithoutLookingUpAsset() {
        UUID assetId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new WorkspaceNotFoundException("ws_1"))
                .when(workspaceAccessGuard).requireMember("ws_1", "intruder");

        assertThatThrownBy(() -> service().readMetadata("ws_1", "intruder", assetId))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verify(assetRepository, never()).findByIdAndWorkspaceId(assetId, "ws_1");
        verify(objectStorage, never()).get(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void readMetadata_assetFromAnotherWorkspaceReturnsNotFound() {
        UUID assetId = UUID.randomUUID();
        when(assetRepository.findByIdAndWorkspaceId(assetId, "ws_1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().readMetadata("ws_1", "user_1", assetId))
                .isInstanceOf(DocumentAssetNotFoundException.class);
        verify(objectStorage, never()).get(org.mockito.ArgumentMatchers.anyString());
    }

    private DocumentAssetReadService service() {
        return new DocumentAssetReadService(workspaceAccessGuard, assetRepository, objectStorage);
    }

    private DocumentAsset asset(UUID id, String workspaceId) {
        return new DocumentAsset(id, workspaceId, "user_1", "image.png", "image/png", 3,
                1, 1, "a".repeat(64), "assets/ws_1/content", Instant.now());
    }
}
