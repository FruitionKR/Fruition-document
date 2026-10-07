package fruition.core.document.service;

import fruition.core.document.domain.DocumentContentVersion;
import fruition.core.document.repository.DocumentContentVersionRepository;
import fruition.core.document.repository.PostgresDocumentEditSaveResult;

import java.time.Duration;
import java.time.Instant;

/** The caller must hold the document row lock in the save transaction. */
public final class DocumentHistoryPolicy {
    public static final Duration INTERVAL = Duration.ofMinutes(10);
    private final DocumentContentVersionRepository versions;

    public DocumentHistoryPolicy(DocumentContentVersionRepository versions) {
        this.versions = versions;
    }

    public long recordSave(String documentId, String markdown, PostgresDocumentEditSaveResult result,
                           String type, Long restoredFrom, Instant recordedAt) {
        if (result.replayed()) return 0;
        boolean immediate = !"manual".equals(type);
        if (!result.changed() && !"ai".equals(type)) return 0;
        if (versions.findTopByIdDocumentIdOrderByIdVersionDesc(documentId).isEmpty()) {
            append(documentId, result.baseRevision(), result.baseMarkdown(), result.baseContentHash(),
                    result.actorUserId(), recordedAt, "initial", null);
        }
        if (immediate) {
            ensureRevision(documentId, result.baseRevision(), result.baseMarkdown(), result.baseContentHash(),
                    result.actorUserId(), recordedAt, "before_" + type);
            return append(documentId, result.revision(), markdown, result.contentHash(),
                    result.actorUserId(), recordedAt, type, restoredFrom);
        }
        return recordIfDue(documentId, result.revision(), markdown, result.contentHash(),
                result.actorUserId(), recordedAt);
    }

    public long recordIfDue(String documentId, long revision, String markdown, String hash,
                            String actor, Instant now) {
        DocumentContentVersion last = versions.findTopByIdDocumentIdOrderByIdVersionDesc(documentId).orElse(null);
        if (last == null || last.getContentHash().equals(hash)
                || now.isBefore(last.getCreatedAt().plus(INTERVAL))) return 0;
        return append(documentId, revision, markdown, hash, actor, now, "manual", null);
    }

    public void ensureRevision(String documentId, long revision, String markdown, String hash,
                               String actor, Instant now, String type) {
        if (versions.findFirstByIdDocumentIdAndRevisionOrderByIdVersionDesc(documentId, revision).isEmpty()) {
            append(documentId, revision, markdown, hash, actor, now, type, null);
        }
    }

    private long append(String documentId, long revision, String markdown, String hash,
                        String actor, Instant now, String type, Long restoredFrom) {
        long version = versions.findTopByIdDocumentIdOrderByIdVersionDesc(documentId)
                .map(v -> v.getVersion() + 1).orElse(1L);
        if (versions.insertSnapshot(documentId, version, revision, markdown, hash, actor, now, type, restoredFrom) != 1) {
            throw new IllegalStateException("문서 이력을 기록하지 못했습니다: " + documentId);
        }
        return version;
    }
}
