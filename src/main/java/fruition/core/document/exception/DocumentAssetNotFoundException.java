package fruition.core.document.exception;

import java.util.UUID;

public class DocumentAssetNotFoundException extends RuntimeException {
    public DocumentAssetNotFoundException(UUID assetId) {
        super("이미지 asset을 찾을 수 없습니다: id=" + assetId);
    }
}
