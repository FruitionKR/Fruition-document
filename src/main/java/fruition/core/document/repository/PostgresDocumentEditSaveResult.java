package fruition.core.document.repository;

import java.time.Instant;

public record PostgresDocumentEditSaveResult(
        long baseRevision,
        String baseMarkdown,
        String baseContentHash,
        long revision,
        String contentHash,
        Instant updatedAt,
        String actorUserId,
        boolean changed,
        boolean replayed
) {
}
