package fruition.core.document.service;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.domain.DocumentProcessingState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.dto.DocumentListResponse;
import fruition.core.document.repository.DocumentEditStateRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Document 엔티티를 화면이 쓰는 항목으로 옮긴다.
 *
 * <p>목록 조회와 문서 트리가 같은 항목을 내보내므로 변환 규칙을 여기 한 곳에 둔다.
 * 규칙이 갈리면 같은 문서가 화면마다 다르게 보인다.
 */
@Component
public class DocumentItemAssembler {

    /** 이 시간 동안 진행 갱신이 없으면 멈춘 것으로 본다. */
    private static final int STALLED_THRESHOLD_SECONDS = 60;

    private final DocumentEditStateRepository editStateRepository;

    public DocumentItemAssembler(DocumentEditStateRepository editStateRepository) {
        this.editStateRepository = editStateRepository;
    }

    /** 편집 상태는 문서 ID를 모아 한 번에 읽는다. 문서마다 조회하면 N+1이 된다. */
    public List<DocumentListResponse.DocumentItem> assemble(List<Document> documents) {
        Set<String> editableDocumentIds = editStateRepository.findAllById(
                        documents.stream().map(Document::getId).toList()).stream()
                .map(DocumentEditState::getDocumentId)
                .collect(Collectors.toSet());
        return documents.stream()
                .map(document -> toItem(document, editableDocumentIds.contains(document.getId())))
                .toList();
    }

    private DocumentListResponse.DocumentItem toItem(Document doc, boolean hasEditState) {
        return new DocumentListResponse.DocumentItem(
                doc.getId(),
                doc.getFilename(),
                doc.getMimeType(),
                doc.getByteSize(),
                doc.getStatus(),
                doc.getSourceUri(),
                doc.getExtractedTextUri(),
                doc.getUploadedAt(),
                doc.getProcessedAt(),
                doc.getProcessingStartedAt(),
                doc.getErrorMessage(),
                doc.getPipelineRunId(),
                resolveProcessingState(doc),
                doc.getProcessingStage(),
                areaOf(doc),
                itemKindOf(doc),
                doc.getDisplayName(),
                fileTypeOf(doc),
                doc.getDocumentRole(),
                isEditable(doc, hasEditState),
                doc.getCurrentVersion(),
                doc.getSourceDocumentId(),
                doc.getUpdatedAt(),
                needsReingest(doc));
    }

    static DocumentProcessingState resolveProcessingState(Document doc) {
        if (doc.getStatus() == DocumentStatus.completed) return DocumentProcessingState.completed;
        if (doc.getStatus() == DocumentStatus.failed) return DocumentProcessingState.failed;
        // 업로드만 되고 ingest가 시작되지 않은 문서는 진행 상태가 없다. starting으로 내려보내면
        // 아직 시작도 안 한 문서가 "처리 중"으로 보인다.
        if (doc.getStatus() == DocumentStatus.uploaded && doc.getPipelineRunId() == null) return null;
        if (doc.getPipelineRunId() == null) return DocumentProcessingState.starting;
        if (doc.getProcessingUpdatedAt() == null) return DocumentProcessingState.starting;
        boolean stalled = doc.getProcessingUpdatedAt()
                .isBefore(Instant.now().minusSeconds(STALLED_THRESHOLD_SECONDS));
        return stalled ? DocumentProcessingState.stalled : DocumentProcessingState.running;
    }

    static String areaOf(Document document) {
        return document.getDocumentRole() == DocumentRole.EDITABLE ? "pages" : "sources";
    }

    static String itemKindOf(Document document) {
        return document.getDocumentRole() == DocumentRole.EDITABLE ? "page" : "source_file";
    }

    /**
     * 마지막 ingest 스냅샷(content_hash)과 현재 편집본(current_content_hash)이 다르면 재분석이 필요하다.
     * 스냅샷이 없는 문서(content_hash null)는 아직 한 번도 ingest되지 않은 것이므로 재분석 대상이다.
     * 처리 중이면 이미 재분석이 진행 중이므로 제외한다. 실패(failed)는 기존 오류 표시가 담당한다.
     */
    static boolean needsReingest(Document document) {
        return document.getDocumentRole() == DocumentRole.EDITABLE
                && document.getStatus() != DocumentStatus.processing
                && document.getCurrentContentHash() != null
                && !document.getCurrentContentHash().equals(document.getContentHash());
    }

    static boolean isEditable(Document document, boolean hasEditState) {
        boolean canInitializeEditState = isMarkdown(document)
                && document.getSourceUri() != null
                && !document.getSourceUri().isBlank();
        return document.getDeletedAt() == null
                && document.getDocumentRole() == DocumentRole.EDITABLE
                // 채팅 Wiki page화 문서는 확인용으로만 노출한다. 본문을 고치면 문답 provenance가 끊긴다.
                && !"chat_export".equals(document.getOrigin())
                && (hasEditState || canInitializeEditState)
                && (isMarkdown(document) || document.getStatus() == DocumentStatus.completed);
    }

    static String fileTypeOf(Document document) {
        int extensionIndex = document.getFilename().lastIndexOf('.');
        if (extensionIndex >= 0 && extensionIndex < document.getFilename().length() - 1) {
            return document.getFilename().substring(extensionIndex + 1).toLowerCase(Locale.ROOT);
        }
        return document.getMimeType();
    }

    static boolean isMarkdown(Document document) {
        String mimeType = document.getMimeType();
        String filename = document.getFilename().toLowerCase(Locale.ROOT);
        return "text/markdown".equals(mimeType)
                || "text/x-markdown".equals(mimeType)
                || filename.endsWith(".md")
                || filename.endsWith(".markdown");
    }
}
