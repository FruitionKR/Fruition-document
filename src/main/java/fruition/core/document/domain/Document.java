package fruition.core.document.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
// 파이프라인이 raw SQL로 같은 documents 행에 직접 쓰므로, backend는 변경한 컬럼만 UPDATE해 파이프라인 컬럼을 덮어쓰지 않는다.
@DynamicUpdate
@Table(name = "documents",
        indexes = @Index(name = "idx_documents_reconcile", columnList = "origin, status, reconciled_at"))
public class Document {

    @Id
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String filename;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "normalized_filename", nullable = false)
    private String normalizedFilename;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status;

    /**
     * 문서 원본의 object key. 항상 평문 키(`sources/documents/{id}/original`)이며 `s3://` 형식을 쓰지 않는다.
     * 이 값은 document-svc가 조립해 생성자에서만 넣고 이후 바뀌지 않는다.
     * `s3://<bucket>/<key>` 형식이 들어오는 곳은 파이프라인이 콜백으로 채우는 extractedTextUri 뿐이다.
     */
    @Column(name = "source_uri")
    private String sourceUri;

    /** 파이프라인이 추출한 텍스트의 위치. 파이프라인이 콜백으로 채우며 `s3://<bucket>/<key>` 형식이다. */
    @Column(name = "extracted_text_uri")
    private String extractedTextUri;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "source_document_id")
    private String sourceDocumentId;

    @Column(name = "current_content_hash", length = 64)
    private String currentContentHash;

    @Column(name = "current_version", nullable = false)
    private long currentVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_role", nullable = false)
    private DocumentRole documentRole;

    @Column(name = "folder_id")
    private UUID folderId;

    @Column(name = "sort_order", nullable = false)
    private long sortOrder;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "pipeline_run_id")
    private String pipelineRunId;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "processing_updated_at")
    private Instant processingUpdatedAt;

    /** 파이프라인이 heartbeat로 보내는 현재 처리 단계 라벨(예: "5. Source Page 생성"). */
    @Column(name = "processing_stage")
    private String processingStage;

    /** 문서 출처. 일반 업로드는 "upload", 채팅 Wiki page화 export는 "chat_export". */
    @Column(name = "origin")
    private String origin;

    /** 채팅 export의 선택 모드("full"/"partial"). 파이프라인에 전달한다. 일반 업로드는 null. */
    @Column(name = "selection_mode")
    private String selectionMode;

    /**
     * full 재생성 시 파이프라인에 inline으로 보낼 미편입 문답(delta) Markdown. 있으면 storage 대신 이 값을 input_markdown으로 전달한다.
     * 일반 업로드·첫 export는 null(= document_id + storage 경로).
     */
    @Column(name = "pipeline_input_markdown", columnDefinition = "TEXT")
    private String pipelineInputMarkdown;

    /**
     * 채팅 export가 파이프라인에 넘길 문답 단위 source block(JSON 배열 문자열). {@code session_id:pair_id}
     * provenance는 Markdown 본문이 아니라 여기에만 있다. 일반 업로드는 null.
     */
    @Column(name = "pipeline_input_blocks", columnDefinition = "TEXT")
    private String pipelineInputBlocks;

    /** 채팅 export 완료 후처리(reconcile) 완료 시각. null이면 아직 후처리 안 됨(=폴링 대상). 재생성 시 다시 null로 리셋된다. */
    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private String deletedBy;

    @Column(name = "delete_operation_id")
    private UUID deleteOperationId;

    protected Document() {}

    public Document(String id, String workspaceId, String userId, String filename, String mimeType, long byteSize,
                    String sourceUri, String contentHash) {
        this(id, workspaceId, userId, filename, mimeType, byteSize, sourceUri, contentHash, "upload");
    }

    public Document(String id, String workspaceId, String userId, String filename, String mimeType, long byteSize,
                    String sourceUri, String contentHash, String origin) {
        // 규약 위반을 DB에 눕히지 않고 여기서 끊는다. 표기가 섞이면 쓰기와 읽기가
        // 서로 다른 키를 가리켜도 오류 없이 조용히 엇갈린다.
        if (sourceUri != null && sourceUri.startsWith("s3://")) {
            throw new IllegalArgumentException(
                    "source_uri는 평문 object key여야 합니다. s3:// 형식은 허용하지 않습니다: " + sourceUri);
        }
        this.id = id;
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.filename = filename;
        this.displayName = displayNameOf(filename);
        this.normalizedFilename = filename.toLowerCase(Locale.ROOT);
        this.mimeType = mimeType;
        this.byteSize = byteSize;
        this.status = DocumentStatus.processing;
        this.sourceUri = sourceUri;
        this.contentHash = contentHash;
        this.currentContentHash = contentHash;
        this.currentVersion = 1;
        this.documentRole = isMarkdown(filename, mimeType) ? DocumentRole.EDITABLE : DocumentRole.ORIGINAL;
        this.sortOrder = 0;
        this.uploadedAt = Instant.now();
        this.updatedAt = this.uploadedAt;
        this.origin = origin;
    }

    public void updateStatus(DocumentStatus status, String extractedTextUri,
                             Instant processedAt, String errorMessage) {
        this.status = status;
        this.extractedTextUri = extractedTextUri;
        this.processedAt = processedAt;
        this.errorMessage = errorMessage;
    }

    public void markPipelineStarted(String pipelineRunId, Instant now) {
        this.pipelineRunId = pipelineRunId;
        this.processingStartedAt = now;
        this.processingUpdatedAt = now;
    }

    public void markProcessingHeartbeat(String stage, Instant now) {
        if (stage != null && !stage.isBlank()) {
            this.processingStage = stage;
        }
        this.processingUpdatedAt = now;
    }

    public void markProcessingFailed(String errorMessage, Instant now) {
        this.status = DocumentStatus.failed;
        this.errorMessage = errorMessage;
        this.processedAt = now;
        this.processingUpdatedAt = now;
    }

    public void rename(String filename) {
        this.filename = filename;
        this.displayName = displayNameOf(filename);
        this.normalizedFilename = filename.toLowerCase(Locale.ROOT);
        this.updatedAt = Instant.now();
    }

    public void initializeDirectMarkdown(String currentContentHash, long byteSize, long sortOrder) {
        this.currentContentHash = currentContentHash;
        this.byteSize = byteSize;
        this.sortOrder = sortOrder;
        this.status = DocumentStatus.completed;
        this.processedAt = this.uploadedAt;
    }

    public void initializeDuplicate(
            String sourceDocumentId,
            UUID folderId,
            String currentContentHash,
            long byteSize,
            long sortOrder
    ) {
        this.sourceDocumentId = sourceDocumentId;
        this.folderId = folderId;
        this.currentContentHash = currentContentHash;
        this.byteSize = byteSize;
        this.sortOrder = sortOrder;
        this.status = DocumentStatus.completed;
        this.processedAt = this.uploadedAt;
    }

    /** PDF → Markdown 변환 placeholder: 변환이 끝날 때까지 processing 상태를 유지하고 원본 문서를 연결한다. */
    public void initializeConvertPlaceholder(
            String sourceDocumentId,
            UUID folderId,
            String currentContentHash,
            long byteSize,
            long sortOrder
    ) {
        this.sourceDocumentId = sourceDocumentId;
        this.folderId = folderId;
        this.currentContentHash = currentContentHash;
        this.byteSize = byteSize;
        this.sortOrder = sortOrder;
    }

    /** PDF → Markdown 변환 완료: 변환 본문 기준으로 크기·해시를 갱신하고 completed로 전환한다. */
    public void completeConvert(String contentHash, long byteSize, Instant now) {
        this.contentHash = contentHash;
        this.currentContentHash = contentHash;
        this.byteSize = byteSize;
        this.status = DocumentStatus.completed;
        this.processedAt = now;
        this.errorMessage = null;
        this.updatedAt = now;
    }

    public void placeAtRoot(long sortOrder) {
        this.sortOrder = sortOrder;
        this.folderId = null;
    }

    public void place(UUID folderId, long sortOrder) {
        this.folderId = folderId;
        this.sortOrder = sortOrder;
    }

    /**
     * 편집 가능 Markdown 재ingest: 편집본을 MinIO 원본으로 덮어쓴 뒤 재처리한다. 일반 문서는
     * 파이프라인이 MinIO 원본 전체를 읽으므로 inline 입력(pipelineInputMarkdown)은 두지 않는다.
     */
    public void reopenForReingest(String contentHash, long byteSize) {
        this.contentHash = contentHash;
        this.currentContentHash = contentHash; // 재ingest 스냅샷 시점에는 편집본과 ingest 본이 같다
        this.byteSize = byteSize;
        this.status = DocumentStatus.processing;
        this.processedAt = null;
        this.errorMessage = null;
        this.pipelineInputMarkdown = null;
        this.pipelineInputBlocks = null;
        this.reconciledAt = null; // 재처리하므로 완료 후 다시 reconcile 대상이 되게 리셋
    }

    /** 완료 후처리(reconcile)를 마쳤음을 기록한다. 이후 폴링 조회에서 제외된다. */
    public void markReconciled(Instant now) {
        this.reconciledAt = now;
    }

    public String getId() { return id; }
    public String getWorkspaceId() { return workspaceId; }
    public String getUserId() { return userId; }
    public String getFilename() { return filename; }
    public String getDisplayName() { return displayName; }
    public String getNormalizedFilename() { return normalizedFilename; }
    public String getMimeType() { return mimeType; }
    public long getByteSize() { return byteSize; }
    public DocumentStatus getStatus() { return status; }
    public String getSourceUri() { return sourceUri; }
    public String getExtractedTextUri() { return extractedTextUri; }
    public String getContentHash() { return contentHash; }
    public String getSourceDocumentId() { return sourceDocumentId; }
    public String getCurrentContentHash() { return currentContentHash; }
    public long getCurrentVersion() { return currentVersion; }
    public DocumentRole getDocumentRole() { return documentRole; }
    public UUID getFolderId() { return folderId; }
    public long getSortOrder() { return sortOrder; }
    public Instant getUploadedAt() { return uploadedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public String getErrorMessage() { return errorMessage; }
    public String getPipelineRunId() { return pipelineRunId; }
    public Instant getProcessingStartedAt() { return processingStartedAt; }
    public Instant getProcessingUpdatedAt() { return processingUpdatedAt; }
    public String getProcessingStage() { return processingStage; }
    public String getOrigin() { return origin; }

    public void assignSelectionMode(String selectionMode) { this.selectionMode = selectionMode; }

    /** 채팅 export 문서가 파이프라인에 실어 보낼 입력(본문 + 문답 블록)을 기록한다. */
    public void assignPipelineInput(String pipelineInputMarkdown, String pipelineInputBlocks) {
        this.pipelineInputMarkdown = pipelineInputMarkdown;
        this.pipelineInputBlocks = pipelineInputBlocks;
    }

    public String getSelectionMode() { return selectionMode; }
    public String getPipelineInputMarkdown() { return pipelineInputMarkdown; }
    public String getPipelineInputBlocks() { return pipelineInputBlocks; }
    public Instant getReconciledAt() { return reconciledAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public String getDeletedBy() { return deletedBy; }
    public UUID getDeleteOperationId() { return deleteOperationId; }

    private static boolean isMarkdown(String filename, String mimeType) {
        return "text/markdown".equals(mimeType)
                || "text/x-markdown".equals(mimeType)
                || filename.toLowerCase(Locale.ROOT).endsWith(".md");
    }

    private static String displayNameOf(String filename) {
        int extensionIndex = filename.lastIndexOf('.');
        if (extensionIndex <= 0) {
            return filename;
        }
        return filename.substring(0, extensionIndex);
    }
}
