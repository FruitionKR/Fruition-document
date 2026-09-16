package fruition.core.document.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.shared.util.StorageProperties;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentAsset;
import fruition.core.document.repository.DocumentAssetRepository;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.domain.DocumentStatus;
import fruition.shared.idempotency.IdempotencyService;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.exception.DocumentOriginalNotFoundException;
import fruition.core.document.exception.DocumentUploadException;
import fruition.core.document.exception.DocumentAlreadyProcessingException;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import fruition.core.document.exception.InvalidDocumentFilenameException;
import fruition.core.document.exception.InvalidDocumentVersionException;
import fruition.shared.idempotency.InvalidIdempotencyKeyException;
import fruition.shared.idempotency.IdempotencyConflictException;
import fruition.shared.idempotency.IdempotencyInProgressException;
import fruition.core.document.exception.InvalidMarkdownContentException;
import fruition.core.document.exception.MarkdownContentTooLargeException;
import fruition.core.document.dto.DocumentDetailResponse;
import fruition.core.document.dto.DocumentContentSaveResponse;
import fruition.core.document.dto.DocumentContentDiffResponse;
import fruition.core.document.dto.DocumentContentVersionListResponse;
import fruition.core.document.dto.DocumentContentVersionResponse;
import fruition.core.document.dto.DocumentIngestResponse;
import fruition.core.document.domain.DocumentContentVersion;
import fruition.core.document.domain.DocumentContentVersionId;
import fruition.core.document.exception.DocumentContentVersionNotFoundException;
import fruition.core.document.repository.DocumentContentVersionRepository;
import fruition.core.document.dto.DocumentDuplicateResponse;
import fruition.core.document.dto.DocumentListResponse;
import fruition.core.document.dto.DocumentLifecycleRequest;
import fruition.core.document.dto.DocumentLifecycleResponse;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.dto.DocumentOriginalResult;
import fruition.core.document.dto.DocumentRenameRequest;
import fruition.core.document.dto.DocumentRenameResponse;
import fruition.core.document.dto.InternalPipelineDocumentResponse;
import fruition.core.document.dto.DocumentUploadResponse;
import fruition.core.document.dto.DocumentTrashResponse;
import fruition.core.document.dto.DocumentBlockResponse;
import fruition.core.document.dto.DocumentBlocksResponse;
import fruition.core.document.dto.DocumentWikiPageRef;
import fruition.core.document.domain.DocumentConvertQueue;
import fruition.core.document.exception.DocumentConvertException;
import fruition.core.document.exception.InvalidDocumentConvertRequestException;
import fruition.core.document.repository.ConverterClient;
import fruition.core.document.repository.DocumentConvertQueueRepository;
import fruition.core.document.repository.IngestCommandOutbox;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.repository.PostgresDocumentEditSaveResult;
import fruition.core.document.repository.PostgresDocumentEditStore;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import fruition.core.document.exception.HierarchyItemNotFoundException;
import fruition.core.aihistory.service.AgentApplyOperationStore;
import fruition.core.aihistory.service.IngestOperationStarter;
import fruition.core.aihistory.service.OperationRecorder;
import fruition.core.agent.exception.InvalidAgentTurnRequestException;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.authz.WorkspaceAccessGuard;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DocumentService {

    private static final int MAX_SAVE_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final String INITIAL_NOTE_FILENAME = "새 노트.md";
    private static final String CONVERT_PLACEHOLDER_MARKDOWN = "PDF 변환 중...\n";

    private final DocumentRepository documentRepository;
    private final FolderRepository folderRepository;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final MinioClient minioClient;
    private final StorageProperties storageProps;
    private final IngestCommandOutbox ingestCommandOutbox;
    private final PipelineWikiStateRequester pipelineWikiStateRequester;
    private final DocumentConvertQueueRepository convertQueueRepository;
    private final ConverterClient converterClient;
    private final WorkspaceAiModelClient workspaceAiModelClient;
    private final TransactionTemplate transactionTemplate;
    private final DocumentEditStateInitializer editStateInitializer;
    private final DocumentEditStateRepository editStateRepository;
    private final DocumentItemAssembler documentItemAssembler;
    private final PostgresDocumentEditStore postgresDocumentEditStore;
    private final DocumentContentVersionRepository contentVersionRepository;
    private final MarkdownDiffService markdownDiffService;
    private final DocumentEditLockService editLockService;
    private final IdempotencyService idempotencyService;
    private final DocumentAssetReferenceSynchronizer assetReferenceSynchronizer;
    private final DocumentAssetReferenceParser assetReferenceParser;
    private final DocumentAssetRepository assetRepository;
    private final ObjectMapper objectMapper;
    private final AgentApplyOperationStore applyOperationStore;
    private final OperationRecorder operationRecorder;
    private final IngestOperationStarter ingestOperationStarter;
    private final fruition.core.document.repository.AiCommandOutboxWriter taskWriter;

    public DocumentService(DocumentRepository documentRepository,
                           FolderRepository folderRepository,
                           WorkspaceAccessGuard workspaceAccessGuard,
                           MinioClient minioClient,
                           StorageProperties storageProps,
                           IngestCommandOutbox ingestCommandOutbox,
                           PipelineWikiStateRequester pipelineWikiStateRequester,
                           DocumentConvertQueueRepository convertQueueRepository,
                           ConverterClient converterClient,
                           TransactionTemplate transactionTemplate,
                           DocumentEditStateInitializer editStateInitializer,
                           DocumentEditStateRepository editStateRepository,
                           DocumentItemAssembler documentItemAssembler,
                           PostgresDocumentEditStore postgresDocumentEditStore,
                           DocumentContentVersionRepository contentVersionRepository,
                           MarkdownDiffService markdownDiffService,
                           DocumentEditLockService editLockService,
                           IdempotencyService idempotencyService,
                           DocumentAssetReferenceSynchronizer assetReferenceSynchronizer,
                           DocumentAssetReferenceParser assetReferenceParser,
                           DocumentAssetRepository assetRepository,
                           ObjectMapper objectMapper,
                           AgentApplyOperationStore applyOperationStore,
                           OperationRecorder operationRecorder,
                           IngestOperationStarter ingestOperationStarter,
                           WorkspaceAiModelClient workspaceAiModelClient,
                           fruition.core.document.repository.AiCommandOutboxWriter taskWriter) {
        this.taskWriter = taskWriter;
        this.documentRepository = documentRepository;
        this.folderRepository = folderRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.minioClient = minioClient;
        this.storageProps = storageProps;
        this.ingestCommandOutbox = ingestCommandOutbox;
        this.pipelineWikiStateRequester = pipelineWikiStateRequester;
        this.convertQueueRepository = convertQueueRepository;
        this.converterClient = converterClient;
        this.workspaceAiModelClient = workspaceAiModelClient;
        this.transactionTemplate = transactionTemplate;
        this.editStateInitializer = editStateInitializer;
        this.editStateRepository = editStateRepository;
        this.documentItemAssembler = documentItemAssembler;
        this.postgresDocumentEditStore = postgresDocumentEditStore;
        this.contentVersionRepository = contentVersionRepository;
        this.markdownDiffService = markdownDiffService;
        this.editLockService = editLockService;
        this.idempotencyService = idempotencyService;
        this.assetReferenceSynchronizer = assetReferenceSynchronizer;
        this.assetReferenceParser = assetReferenceParser;
        this.assetRepository = assetRepository;
        this.objectMapper = objectMapper;
        this.applyOperationStore = applyOperationStore;
        this.operationRecorder = operationRecorder;
        this.ingestOperationStarter = ingestOperationStarter;
    }

    private void verifyWorkspaceOwnership(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
    }

    @Transactional
    public DocumentUploadResponse upload(
            String workspaceId,
            String userId,
            String idempotencyKey,
            UUID folderId,
            MultipartFile file
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        verifyFolder(workspaceId, folderId);
        validateIdempotencyKey(idempotencyKey);
        String objectPath = null;
        boolean objectStored = false;
        try {
            byte[] bytes = file.getBytes();
            String filename = file.getOriginalFilename();
            validateFilename(filename);
            String mimeType = resolveMimeType(file);
            boolean markdownUpload = isMarkdown(filename, mimeType);
            DocumentEditingRules.MarkdownContent markdownContent =
                    markdownUpload ? DocumentEditingRules.markdown(bytes) : null;
            String contentHash = markdownUpload ? markdownContent.contentHash() : sha256(bytes);
            String endpointScope = uploadEndpointScope(workspaceId);
            String requestHash = requestHash(
                    filename.trim(), mimeType, contentHash, String.valueOf(folderId));

            Optional<DocumentUploadResponse> replay = replayIdempotentRequest(
                    userId, endpointScope, idempotencyKey, requestHash);
            if (replay.isPresent()) {
                return replay.get();
            }

            log.info("[문서 업로드 요청] workspaceId={} userId={} filename={} contentType={} size={}",
                    workspaceId, userId, file.getOriginalFilename(), file.getContentType(), file.getSize());

            String documentId = newDocumentId();
            objectPath = "sources/documents/" + documentId + "/original";
            log.info("[문서 원본 저장 시작] documentId={} bucket={} objectPath={} mimeType={} byteSize={}",
                    documentId, storageProps.getBucket(), objectPath, mimeType, bytes.length);

            try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(storageProps.getBucket())
                                .object(objectPath)
                                .stream(inputStream, bytes.length, -1)
                                .contentType(mimeType)
                                .build()
                );
            }
            objectStored = true;
            registerMinioRollbackCleanup(objectPath);
            log.info("[문서 원본 저장 완료] documentId={} objectPath={}", documentId, objectPath);

            Document document = new Document(
                    documentId,
                    workspaceId,
                    userId,
                    filename.trim(),
                    mimeType,
                    bytes.length,
                    objectPath,
                    contentHash
            );
            document.place(folderId, placementSortOrder(workspaceId, folderId, document.getDocumentRole()));
            document.updateStatus(DocumentStatus.uploaded, null, null, null);
            documentRepository.save(document);
            if (markdownUpload) {
                editStateRepository.save(new DocumentEditState(
                        documentId, markdownContent.markdown(), markdownContent.contentHash(), 1));
            }
            DocumentUploadResponse response = toUploadResponse(document, markdownUpload);
            saveIdempotencyRecord(userId, endpointScope, idempotencyKey, requestHash, response);
            log.info("[문서 DB 저장 완료] documentId={} workspaceId={} userId={} filename={} status={} sourceUri={}",
                    document.getId(), document.getWorkspaceId(), document.getUserId(),
                    document.getFilename(), document.getStatus(), document.getSourceUri());

            return response;
        } catch (InvalidDocumentFilenameException
                 | InvalidIdempotencyKeyException
                 | IdempotencyConflictException
                 | IdempotencyInProgressException
                 | InvalidMarkdownContentException
                 | MarkdownContentTooLargeException e) {
            throw e;
        } catch (Exception e) {
            if (objectStored) {
                deleteMinioObject(objectPath);
            }
            throw new DocumentUploadException("파일 저장 중 오류가 발생했습니다.", e);
        }
    }

    @Transactional
    public DocumentUploadResponse createMarkdown(
            String workspaceId,
            String userId,
            String idempotencyKey,
            MarkdownDocumentCreateRequest request
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        validateIdempotencyKey(idempotencyKey);
        if (request == null) {
            throw new InvalidMarkdownContentException("Markdown 생성 요청은 필수입니다.");
        }
        verifyFolder(workspaceId, request.folderId());

        DocumentEditingRules.Filename filename =
                DocumentEditingRules.rename(request.displayName(), "document.md");
        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.markdown(request.markdown());
        String endpointScope = markdownEndpointScope(workspaceId);
        String requestHash = requestHash(
                filename.filename(), "text/markdown", content.contentHash(),
                String.valueOf(request.folderId()));

        Optional<DocumentUploadResponse> replay =
                replayIdempotentRequest(userId, endpointScope, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        DocumentUploadResponse response =
                createMarkdownDocument(workspaceId, userId, filename.filename(), content, "direct", request.folderId());
        saveIdempotencyRecord(userId, endpointScope, idempotencyKey, requestHash, response);
        return response;
    }

    @Transactional
    public DocumentDuplicateResponse duplicate(
            String workspaceId,
            String userId,
            String documentId,
            String idempotencyKey
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        validateIdempotencyKey(idempotencyKey);
        Document source = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(source, userId);
        if (source.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new DocumentWriteForbiddenException("편집 가능한 Markdown 문서만 복제할 수 있습니다.");
        }

        verifyFolder(workspaceId, source.getFolderId());
        List<Document> siblings =
                documentRepository.findSiblingPagesForUpdate(workspaceId, source.getFolderId());
        editStateInitializer.initializeIfNeeded(source);
        DocumentEditState sourceEditState = editStateRepository.findById(documentId)
                .orElseThrow(() -> new DocumentWriteForbiddenException(
                        "최신 Markdown 편집본이 있는 문서만 복제할 수 있습니다."));
        String endpointScope = duplicateEndpointScope(workspaceId);
        String requestHash = requestHash(documentId, "duplicate", "");
        Optional<DocumentDuplicateResponse> replay = replayIdempotentRequest(
                userId, endpointScope, idempotencyKey, requestHash,
                DocumentDuplicateResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        Set<String> existingNames = new java.util.HashSet<>(
                documentRepository.findActiveNormalizedFilenames(workspaceId));
        DocumentEditingRules.Filename duplicateFilename =
                DocumentEditingRules.duplicateFilename(source.getDisplayName(), existingNames);
        long sortOrder = siblings.stream()
                .mapToLong(Document::getSortOrder)
                .max()
                .orElse(-1) + 1;
        DocumentEditingRules.MarkdownContent content =
                DocumentEditingRules.markdown(sourceEditState.getMarkdown());

        String duplicateId = newDocumentId();
        Document duplicate = new Document(
                duplicateId,
                workspaceId,
                userId,
                duplicateFilename.filename(),
                "text/markdown",
                content.bytes().length,
                storeMarkdownSource(duplicateId, content.bytes()),
                null,
                "duplicate"
        );
        duplicate.initializeDuplicate(
                source.getId(),
                source.getFolderId(),
                content.contentHash(),
                content.bytes().length,
                sortOrder
        );
        documentRepository.save(duplicate);
        editStateRepository.save(new DocumentEditState(
                duplicateId, content.markdown(), content.contentHash(), 1));
        assetReferenceSynchronizer.copyReferences(documentId, duplicateId);

        DocumentDuplicateResponse response = toDuplicateResponse(duplicate);
        saveIdempotencyRecord(userId, endpointScope, idempotencyKey, requestHash, response);
        return response;
    }

    /** 새 워크스페이스에 직접 생성 Markdown 노트를 저장한다. 실패는 기존처럼 best-effort로 처리한다. */
    @Transactional
    public void createInitialNote(String workspaceId, String userId) {
        try {
            createMarkdownDocument(
                    workspaceId,
                    userId,
                    INITIAL_NOTE_FILENAME,
                    DocumentEditingRules.markdown("# 새 노트\n"),
                    "direct",
                    null
            );
        } catch (Exception e) {
            log.warn("초기 노트 저장 실패로 건너뜁니다. workspaceId={}", workspaceId, e);
        }
    }

    private String resolveMimeType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && !contentType.equals("application/octet-stream")) {
            return contentType;
        }
        String filename = file.getOriginalFilename();
        if (filename != null) {
            String normalizedFilename = filename.toLowerCase(java.util.Locale.ROOT);
            if (normalizedFilename.endsWith(".md") || normalizedFilename.endsWith(".markdown")) {
                return "text/markdown";
            }
        }
        return contentType != null ? contentType : "application/octet-stream";
    }

    private DocumentUploadResponse createMarkdownDocument(
            String workspaceId,
            String userId,
            String filename,
            DocumentEditingRules.MarkdownContent content,
            String origin,
            UUID folderId
    ) {
        String documentId = newDocumentId();
        Document document = new Document(
                documentId,
                workspaceId,
                userId,
                filename,
                "text/markdown",
                content.bytes().length,
                storeMarkdownSource(documentId, content.bytes()),
                null,
                origin
        );
        long sortOrder = placementSortOrder(workspaceId, folderId, DocumentRole.EDITABLE);
        document.initializeDirectMarkdown(
                content.contentHash(),
                content.bytes().length,
                sortOrder
        );
        document.place(folderId, sortOrder);
        documentRepository.save(document);
        editStateRepository.save(new DocumentEditState(
                documentId, content.markdown(), content.contentHash(), 1));
        return toUploadResponse(document, true);
    }

    private long nextRootSortOrder(String workspaceId, DocumentRole documentRole) {
        return documentRepository.findMaxRootSortOrder(workspaceId, documentRole) + 1;
    }

    /** 폴더 지정 시 폴더·문서 혼합 순서 마지막, 미지정 시 역할별 최상위 마지막에 배치한다. */
    private long placementSortOrder(String workspaceId, UUID folderId, DocumentRole documentRole) {
        if (folderId == null) {
            return nextRootSortOrder(workspaceId, documentRole);
        }
        return Math.max(
                folderRepository.findMaxSortOrder(workspaceId, folderId),
                documentRepository.findMaxSortOrderInFolder(workspaceId, folderId)) + 1;
    }

    private void verifyFolder(String workspaceId, UUID folderId) {
        if (folderId != null
                && folderRepository.findActiveForUpdate(folderId, workspaceId).isEmpty()) {
            throw new HierarchyItemNotFoundException("대상 폴더를 찾을 수 없습니다.");
        }
    }

    private boolean isMarkdown(String filename, String mimeType) {
        String normalizedFilename = filename.toLowerCase(java.util.Locale.ROOT);
        return "text/markdown".equals(mimeType)
                || "text/x-markdown".equals(mimeType)
                || normalizedFilename.endsWith(".md")
                || normalizedFilename.endsWith(".markdown");
    }

    private DocumentUploadResponse toUploadResponse(Document document, boolean editable) {
        return new DocumentUploadResponse(
                document.getId(),
                document.getFilename(),
                document.getMimeType(),
                document.getByteSize(),
                document.getStatus(),
                document.getSourceUri(),
                document.getUploadedAt(),
                editable,
                document.getCurrentVersion(),
                document.getDocumentRole()
        );
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        idempotencyService.validateKey(idempotencyKey);
    }

    private <T> Optional<T> replayIdempotentRequest(
            String userId,
            String endpointScope,
            String idempotencyKey,
            String requestHash,
            Class<T> responseType
    ) {
        return idempotencyService.replay(
                userId, endpointScope, idempotencyKey, requestHash, responseType);
    }

    private Optional<DocumentUploadResponse> replayIdempotentRequest(
            String userId,
            String endpointScope,
            String idempotencyKey,
            String requestHash
    ) {
        return replayIdempotentRequest(
                userId, endpointScope, idempotencyKey, requestHash,
                DocumentUploadResponse.class);
    }

    private void saveIdempotencyRecord(
            String userId,
            String endpointScope,
            String idempotencyKey,
            String requestHash,
            Object response,
            String resourceId
    ) {
        idempotencyService.save(
                userId, endpointScope, idempotencyKey, requestHash,
                201, resourceId, response);
    }

    private void saveIdempotencyRecord(
            String userId,
            String endpointScope,
            String idempotencyKey,
            String requestHash,
            DocumentLifecycleResponse response
    ) {
        idempotencyService.save(
                userId, endpointScope, idempotencyKey, requestHash,
                200, response.id(), response);
    }

    private void saveIdempotencyRecord(
            String userId,
            String endpointScope,
            String idempotencyKey,
            String requestHash,
            DocumentUploadResponse response
    ) {
        saveIdempotencyRecord(
                userId, endpointScope, idempotencyKey, requestHash, response, response.id());
    }

    private void saveIdempotencyRecord(
            String userId,
            String endpointScope,
            String idempotencyKey,
            String requestHash,
            DocumentDuplicateResponse response
    ) {
        saveIdempotencyRecord(
                userId, endpointScope, idempotencyKey, requestHash, response, response.id());
    }

    private String uploadEndpointScope(String workspaceId) {
        return "POST:/api/workspaces/" + workspaceId + "/documents";
    }

    private String markdownEndpointScope(String workspaceId) {
        return "POST:/api/workspaces/" + workspaceId + "/documents/markdown";
    }

    private String duplicateEndpointScope(String workspaceId) {
        return "POST:/api/workspaces/" + workspaceId + "/documents/duplicate";
    }

    private DocumentDuplicateResponse toDuplicateResponse(Document document) {
        return new DocumentDuplicateResponse(
                document.getId(),
                document.getFilename(),
                document.getDisplayName(),
                document.getMimeType(),
                document.getByteSize(),
                document.getCurrentVersion(),
                document.getFolderId(),
                document.getSourceDocumentId(),
                document.getSortOrder()
        );
    }

    private String requestHash(String... parts) {
        return idempotencyService.requestHash(parts);
    }

    private String newDocumentId() {
        UUID id = idempotencyService.currentExecutionId().orElseGet(UUID::randomUUID);
        return "doc_" + id.toString().replace("-", "");
    }

    /**
     * 새로 만드는 Markdown 문서의 원본을 object storage에 저장하고 object key를 돌려준다.
     * 파이프라인은 편집 상태를 읽지 않고 source_uri로만 본문을 가져가므로,
     * 문서 행을 만들 때 원본도 같이 만들어야 이후 ingest가 성립한다.
     */
    private String storeMarkdownSource(String documentId, byte[] bytes) {
        String objectPath = "sources/documents/" + documentId + "/original";
        try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(storageProps.getBucket())
                            .object(objectPath)
                            .stream(inputStream, bytes.length, -1)
                            .contentType("text/markdown")
                            .build()
            );
        } catch (Exception e) {
            throw new DocumentUploadException("문서 원본 저장 중 오류가 발생했습니다.", e);
        }
        registerMinioRollbackCleanup(objectPath);
        return objectPath;
    }

    private void registerMinioRollbackCleanup(String objectPath) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteMinioObject(objectPath);
                }
            }
        });
    }

    /** DB 트랜잭션 커밋 뒤 별도 로컬 작업 큐에 등록한다. */
    private void enqueueAfterCommit(String queueLabel, String documentId, Runnable enqueue) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            transactionTemplate.execute(status -> {
                enqueue.run();
                return null;
            });
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                TransactionTemplate requiresNew =
                        new TransactionTemplate(transactionTemplate.getTransactionManager());
                requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                requiresNew.execute(status -> {
                    enqueue.run();
                    log.info("[{} 등록 완료] documentId={} status=pending", queueLabel, documentId);
                    return null;
                });
            }
        });
    }

    /** 채팅 Wiki page화 export 결과. skipped=true면 동일 content가 이미 존재해 새로 만들지 않았다. */
    public record ExportDocumentResult(String documentId, boolean skipped) {}

    /** LLM polish가 실패했을 때 Wiki 페이지 제목이 떨어지는 폴백값(본문 첫 헤딩). 문서 이름으로 쓰지 않는다. */
    private static final String CHAT_EXPORT_FALLBACK_TITLE = "Chat Export";

    /** 목록에서 채팅에서 온 문서임을 알리는 이름 접두사. */
    private static final String CHAT_EXPORT_NAME_PREFIX = "[채팅] ";

    /** 파이프라인에 넘길 source block 1개. blockId가 {@code session_id:pair_id} provenance다. */
    public record PipelineSourceBlock(String blockId, String text) {}

    /**
     * 파이프라인이 만든 Wiki 페이지 제목으로 채팅 export 문서 이름을 확정한다. export 시점 이름은 첫 질문을
     * 줄인 임시값이라, 내용을 요약한 페이지 제목이 나오면 그걸로 바꾼다.
     *
     * <p>페이지 제목이 비었거나 폴백값({@code Chat Export})이면 임시 이름을 그대로 둔다. 폴백은 LLM polish가
     * 실패했을 때 본문 첫 헤딩에서 나오는 상수라, 그대로 쓰면 모든 문서가 같은 이름이 된다.
     * 이미 그 이름이면 아무것도 하지 않는다(멱등).
     */
    @Transactional
    public void confirmChatExportName(Document document, String wikiPageTitle) {
        // 페이지 제목은 AI가 지은 값이라 "CI/CD"처럼 파일명에 못 쓰는 문자가 섞일 수 있다.
        String title = DocumentEditingRules.sanitizeDisplayName(wikiPageTitle);
        if (title.isEmpty() || CHAT_EXPORT_FALLBACK_TITLE.equals(title)) {
            return;
        }
        // 자기 이름은 후보에서 뺀다. 넣어 두면 두 번째 호출이 자기 자신과 충돌한 것으로 보고 (2)를 붙인다.
        String filename = uniqueChatExportFilename(
                document.getWorkspaceId(), title, document.getNormalizedFilename());
        if (filename.equals(document.getFilename())) {
            return;
        }
        document.rename(filename);
        documentRepository.save(document);
        log.info("[채팅 export 문서 이름 확정] documentId={} filename={}", document.getId(), filename);
    }

    /**
     * 채팅 export 문서 이름을 만든다. 채팅에서 온 문서임을 알리는 접두사를 붙이고, 워크스페이스의 기존 문서와
     * 겹치면 {@code (2)}를 더한다. export 시점과 이름 확정 시점이 모두 이 경로를 지나 접두사가 유지된다.
     *
     * <p>이름은 세션 제목이나 AI가 만든 페이지 제목에서 오므로 파일명에 못 쓰는 문자가 섞일 수 있어
     * 여기서 정제한다. 접두사가 늘 남으므로 정제 결과가 비는 일은 없다.
     *
     * <p>동시에 같은 이름을 선택하는 경합은 DB 고유 제약으로 거절한다.
     */
    private String uniqueChatExportFilename(String workspaceId, String displayName,
                                            String excludedNormalizedFilename) {
        Set<String> existingNames = documentRepository.findActiveNormalizedFilenames(workspaceId).stream()
                .filter(name -> excludedNormalizedFilename == null || !name.equals(
                        java.text.Normalizer.normalize(excludedNormalizedFilename.trim(), java.text.Normalizer.Form.NFC)
                                .toLowerCase(java.util.Locale.ROOT)))
                .collect(Collectors.toSet());
        String candidate = DocumentEditingRules.sanitizeDisplayName(CHAT_EXPORT_NAME_PREFIX + displayName);
        return DocumentEditingRules.uniqueFilename(candidate, existingNames).filename();
    }

    /**
     * 채팅 export 문서는 읽기 전용이다. 본문을 사람이 고치면 문답 경계를 다시 알아낼 수 없어
     * source block provenance가 끊긴다. Ingest는 저장된 문답과 출처 블록을 사용한다.
     */
    private void requireNotChatExport(Document document, String message) {
        if ("chat_export".equals(document.getOrigin())) {
            throw new InvalidMarkdownContentException(message);
        }
    }

    /** command payload와 같은 snake_case 키로 저장한다. 꺼낼 때 변환 없이 그대로 실어 보낸다. */
    private String serializePipelineBlocks(List<PipelineSourceBlock> blocks) {
        List<Map<String, String>> payload = blocks.stream()
                .map(block -> Map.of("block_id", block.blockId(), "text", block.text()))
                .toList();
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("채팅 source block 직렬화에 실패했습니다.", e);
        }
    }

    /**
     * 채팅 export는 언제나 선택한 문답만 담은 새 문서다. 중복 판별 unique key(workspace_id, content_hash,
     * selection_mode)와 파이프라인 command가 이 값을 그대로 쓴다.
     */
    private static final String CHAT_EXPORT_SELECTION_MODE = "partial";

    /**
     * 채팅 export Markdown을 원문 문서로 저장한다. Ingest는 별도로 요청한다. (권한 검증은 호출부에서 이미 수행)
     * contentHash로 중복을 확인해, 이미 있으면 기존 문서 id로 skipped 결과를 반환한다.
     */
    @Transactional
    public ExportDocumentResult createChatExportDocument(String workspaceId, String userId,
                                                         String displayName, String markdown, String contentHash,
                                                         List<PipelineSourceBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("채팅 export 문서는 source block이 필요합니다.");
        }
        Optional<Document> existing = documentRepository.findByWorkspaceIdAndOriginAndContentHashAndSelectionModeAndDeletedAtIsNull(
                workspaceId, "chat_export", contentHash, CHAT_EXPORT_SELECTION_MODE);
        if (existing.isPresent()) {
            return new ExportDocumentResult(existing.get().getId(), true);
        }

        String documentId = "chatdoc_" + UUID.randomUUID().toString().replace("-", "");
        String objectPath = "sources/documents/" + documentId + "/original";
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);

        Document candidate = new Document(
                documentId, workspaceId, userId, uniqueChatExportFilename(workspaceId, displayName, null),
                "text/markdown", bytes.length, objectPath, contentHash, "chat_export");
        candidate.assignSelectionMode(CHAT_EXPORT_SELECTION_MODE);
        candidate.updateStatus(DocumentStatus.uploaded, null, null, null);
        if (documentRepository.reserveChatExport(
                candidate.getId(), candidate.getWorkspaceId(), candidate.getUserId(),
                candidate.getFilename(), candidate.getDisplayName(), candidate.getNormalizedFilename(),
                candidate.getMimeType(), candidate.getByteSize(), candidate.getStatus().name(),
                candidate.getSourceUri(), candidate.getContentHash(), candidate.getCurrentContentHash(),
                candidate.getCurrentVersion(), candidate.getDocumentRole().name(), candidate.getSortOrder(),
                candidate.getUploadedAt(), candidate.getUpdatedAt(), CHAT_EXPORT_SELECTION_MODE) == 0) {
            Document canonical = documentRepository
                    .findByWorkspaceIdAndOriginAndContentHashAndSelectionModeAndDeletedAtIsNull(
                            workspaceId, "chat_export", contentHash, CHAT_EXPORT_SELECTION_MODE)
                    .orElseThrow(() -> new IllegalStateException("chat export canonical 문서를 찾을 수 없습니다."));
            return new ExportDocumentResult(canonical.getId(), true);
        }

        try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(storageProps.getBucket())
                            .object(objectPath)
                            .stream(inputStream, bytes.length, -1)
                            .contentType("text/markdown")
                            .build()
            );
        } catch (Exception e) {
            throw new DocumentUploadException("채팅 export 저장 중 오류가 발생했습니다.", e);
        }
        registerMinioRollbackCleanup(objectPath);

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("예약한 chat export 문서를 찾을 수 없습니다."));
        // 파이프라인 입력은 항상 command inline이다. storage 원본은 사용자에게 보여줄 본문으로만 쓴다.
        document.assignPipelineInput(markdown, serializePipelineBlocks(blocks));
        log.info("[채팅 export 문서 DB 저장 완료] documentId={} workspaceId={} userId={} filename={} selectionMode={} status={} sourceUri={}",
                document.getId(), document.getWorkspaceId(), document.getUserId(), document.getFilename(),
                document.getSelectionMode(), document.getStatus(), document.getSourceUri());

        return new ExportDocumentResult(documentId, false);
    }

    /**
     * PDF 원본 문서의 Markdown 변환을 요청한다. 변환 결과를 담을 placeholder Markdown 문서를 즉시 만들어
     * 반환하고, 실제 변환은 convert queue worker가 백그라운드에서 수행한다.
     */
    @Transactional
    public DocumentUploadResponse convertToMarkdown(
            String workspaceId,
            String userId,
            String documentId,
            String idempotencyKey
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        validateIdempotencyKey(idempotencyKey);
        Document source = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!isPdf(source)) {
            throw new InvalidDocumentConvertRequestException("PDF 원본 문서만 Markdown으로 변환할 수 있습니다.");
        }
        if (source.getSourceUri() == null) {
            throw new InvalidDocumentConvertRequestException("원본 파일이 없는 문서는 변환할 수 없습니다.");
        }

        String endpointScope = convertEndpointScope(workspaceId);
        String requestHash = requestHash(documentId, "convert-markdown", "");
        Optional<DocumentUploadResponse> replay =
                replayIdempotentRequest(userId, endpointScope, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        // placeholder: display_name은 원본에서 확장자를 뗀 이름, filename은 <이름>.md
        DocumentEditingRules.Filename filename =
                DocumentEditingRules.rename(source.getDisplayName(), "document.md");
        DocumentEditingRules.MarkdownContent content =
                DocumentEditingRules.markdown(CONVERT_PLACEHOLDER_MARKDOWN);
        String placeholderId = newDocumentId();
        String convertRunId = "convert:" + placeholderId;
        taskWriter.begin(convertRunId, workspaceId, userId, "convert");
        Document placeholder = new Document(
                placeholderId,
                workspaceId,
                userId,
                filename.filename(),
                "text/markdown",
                content.bytes().length,
                storeMarkdownSource(placeholderId, content.bytes()),
                null,
                "convert"
        );
        long sortOrder = placementSortOrder(workspaceId, source.getFolderId(), DocumentRole.EDITABLE);
        placeholder.initializeConvertPlaceholder(
                source.getId(),
                source.getFolderId(),
                content.contentHash(),
                content.bytes().length,
                sortOrder
        );
        placeholder.markPipelineStarted(convertRunId, Instant.now());
        documentRepository.save(placeholder);
        editStateRepository.save(new DocumentEditState(
                placeholderId, content.markdown(), content.contentHash(), 1));
        requestConvertAfterCommit(placeholderId, source.getId());

        DocumentUploadResponse response = toUploadResponse(placeholder, true);
        saveIdempotencyRecord(userId, endpointScope, idempotencyKey, requestHash, response);
        log.info("[문서 변환 요청 등록] workspaceId={} sourceDocumentId={} placeholderDocumentId={}",
                workspaceId, documentId, placeholderId);
        return response;
    }

    private boolean isPdf(Document document) {
        return "application/pdf".equals(document.getMimeType())
                || document.getNormalizedFilename().endsWith(".pdf");
    }

    private String convertEndpointScope(String workspaceId) {
        return "POST:/api/workspaces/" + workspaceId + "/documents/convert-markdown";
    }

    private void requestConvertAfterCommit(String documentId, String sourceDocumentId) {
        enqueueAfterCommit("문서 변환 큐", documentId,
                () -> convertQueueRepository.save(new DocumentConvertQueue(documentId, sourceDocumentId)));
    }

    /**
     * convert queue worker 전용. 원본 PDF를 변환기로 변환해 placeholder Markdown 문서 본문에 반영한다.
     * 실패(변환기 4xx/5xx·timeout·원본 읽기 실패)는 placeholder 문서를 failed로 반영하고 원인을 로그로 남긴다.
     */
    void doConvert(long queueId, String documentId, String sourceDocumentId) {
        Document placeholder = documentRepository.findByIdInActiveWorkspace(documentId).orElse(null);
        if (placeholder == null) {
            log.warn("[문서 변환 생략] documentId={} reason=document_not_found", documentId);
            return;
        }
        if (!taskWriter.active("convert:" + documentId)) return;
        try {
            Document source = documentRepository.findById(sourceDocumentId)
                    .orElseThrow(() -> new DocumentConvertException(
                            "원본 문서를 찾을 수 없습니다: " + sourceDocumentId));
            byte[] pdfBytes = readOriginalBytes(source);
            log.info("[문서 변환 시작] documentId={} sourceDocumentId={} pdfByteSize={}",
                    documentId, sourceDocumentId, pdfBytes.length);
            WorkspaceAiModelClient.AiModelSelection aiModel =
                    workspaceAiModelClient.get(placeholder.getWorkspaceId());
            String markdown = converterClient.convertPdf(
                    source.getFilename(), pdfBytes, aiModel.provider(), aiModel.model(),
                    () -> taskWriter.active("convert:" + documentId));
            DocumentEditingRules.MarkdownContent content = DocumentEditingRules.markdown(markdown);
            applyConvertedMarkdown(queueId, placeholder, content);
            log.info("[문서 변환 완료] documentId={} sourceDocumentId={} markdownByteSize={}",
                    documentId, sourceDocumentId, content.bytes().length);
        } catch (Exception e) {
            // DocumentConvertException 메시지에 변환기 상태 코드(422/504/503 등) 원인이 담겨 온다.
            Instant now = Instant.now();
            transactionTemplate.execute(status -> {
                if (!taskWriter.join("convert:" + documentId)) return null;
                documentRepository.findByIdInActiveWorkspace(documentId).ifPresent(doc ->
                        doc.markProcessingFailed("PDF 변환에 실패했습니다: " + e.getMessage(), now));
                return null;
            });
            log.warn("[문서 변환 실패 반영] documentId={} sourceDocumentId={} error={}",
                    documentId, sourceDocumentId, e.getMessage());
        }
    }

    private byte[] readOriginalBytes(Document source) {
        if (source.getSourceUri() == null) {
            throw new DocumentConvertException("원본 파일 경로가 없습니다: " + source.getId());
        }
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(storageProps.getBucket())
                        .object(normalizeObjectKey(source.getSourceUri()))
                        .build())) {
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new DocumentConvertException("원본 PDF를 읽지 못했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 변환 Markdown을 placeholder 문서에 반영한다. 시스템 쓰기라 revision 충돌 우려가 없어 base_revision 1로
     * 저장하고, write_id({@code convert:<queueId>}) 재시도는 PostgreSQL write receipt가 멱등하게 처리한다.
     */
    private void applyConvertedMarkdown(
            long queueId,
            Document placeholder,
            DocumentEditingRules.MarkdownContent content
    ) {
        TransactionTemplate saveTransaction = requiresNewSaveTransactionTemplate();
        executeWithSaveRetry(() -> saveTransaction.execute(status -> {
            if (!taskWriter.join("convert:" + placeholder.getId())) return null;
            PostgresDocumentEditSaveResult result = postgresDocumentEditStore.save(
                    placeholder.getWorkspaceId(),
                    placeholder.getId(),
                    content.markdown(),
                    content.contentHash(),
                    1L,
                    "convert:" + queueId,
                    placeholder.getUserId(),
                    null
            );
            if (!result.replayed()) {
                projectContentVersions(placeholder.getId(), content.markdown(), result);
                Instant now = Instant.now();
                documentRepository.findByIdInActiveWorkspace(placeholder.getId()).ifPresent(doc ->
                        doc.completeConvert(content.contentHash(), content.bytes().length, now));
            }
            taskWriter.complete("convert:" + placeholder.getId());
            return null;
        }));
    }

    String enqueueIngest(Document document) {
        return enqueueIngest(document, UUID.randomUUID().toString());
    }

    private String enqueueIngest(Document document, String runId) {
        ingestCommandOutbox.begin(runId, document.getWorkspaceId(), document.getUserId());
        String documentId = document.getId();
        Instant startedAt = Instant.now();
        document.markPipelineStarted(runId, startedAt);
        String operationId = ingestOperationStarter.start(
                document.getWorkspaceId(), document.getUserId(), documentId,
                document.getDisplayName(), startedAt);
        ingestCommandOutbox.enqueue(
                runId,
                documentId,
                document.getUserId(),
                document.getWorkspaceId(),
                document.getSelectionMode(),
                document.getPipelineInputMarkdown(),
                document.getPipelineInputBlocks(),
                false,
                operationId,
                document.getCurrentVersion(),
                document.getCurrentContentHash()
        );
        log.info("[문서 처리 command 등록 완료] documentId={} runId={} operationId={}",
                documentId, runId, operationId);
        return runId;
    }

    @Transactional
    public void applyPipelineResult(String documentId, String runId, String status, String error) {
        Document document = documentRepository.findByIdInActiveWorkspace(documentId)
                .orElse(null);
        if (document == null || !runId.equals(document.getPipelineRunId())
                || document.getStatus() != DocumentStatus.processing) {
            return;
        }
        Instant now = Instant.now();
        if ("succeeded".equals(status)) {
            document.updateStatus(DocumentStatus.completed, null, now, null);
        } else if ("failed".equals(status)) {
            document.markProcessingFailed(error, now);
        }
    }

    private String contentHashPrefix(String contentHash) {
        if (contentHash == null) return null;
        return contentHash.substring(0, Math.min(contentHash.length(), 16));
    }

    public DocumentListResponse findAll(String workspaceId, String userId, String query) {
        verifyWorkspaceOwnership(workspaceId, userId);

        List<Document> documents = query == null || query.isBlank()
                ? documentRepository.findVisibleByWorkspaceId(workspaceId)
                : documentRepository.searchVisibleByWorkspaceId(workspaceId, query.trim());

        return new DocumentListResponse(documentItemAssembler.assemble(documents));
    }

    @Transactional(readOnly = true)
    public Optional<InternalPipelineDocumentResponse> findPipelineSource(String documentId) {
        return documentRepository.findByIdInActiveWorkspace(documentId)
                .map(document -> new InternalPipelineDocumentResponse(
                        document.getId(),
                        document.getUserId(),
                        document.getWorkspaceId(),
                        document.getFilename(),
                        document.getMimeType(),
                        document.getSourceUri(),
                        document.getExtractedTextUri(),
                        document.getCurrentVersion(),
                        document.getCurrentContentHash()
                ));
    }

    @Transactional
    public DocumentDetailResponse findById(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Document doc = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        editStateInitializer.initializeIfNeeded(doc);
        Optional<DocumentEditState> editState = postgresDocumentEditStore.findState(documentId);
        long editRevision = editState.map(DocumentEditState::getRevision).orElseGet(() -> {
            if (doc.getDocumentRole() == DocumentRole.EDITABLE) {
                throw new InvalidMarkdownContentException("현재 Markdown 편집 상태를 찾을 수 없습니다.");
            }
            return doc.getCurrentVersion();
        });

        List<DocumentWikiPageRef> wikiPages = pipelineWikiStateRequester
                .documentContext(workspaceId, documentId).pages().stream()
                .map(page -> new DocumentWikiPageRef(
                        page.id(), page.pageType(), page.title(), page.slug(), page.relationType(), page.confidence()))
                .toList();

        return new DocumentDetailResponse(
                doc.getId(),
                doc.getFilename(),
                doc.getMimeType(),
                doc.getByteSize(),
                doc.getStatus(),
                doc.getSourceUri(),
                doc.getExtractedTextUri(),
                doc.getUploadedAt(),
                doc.getProcessedAt(),
                doc.getErrorMessage(),
                wikiPages,
                doc.getPipelineRunId(),
                DocumentItemAssembler.resolveProcessingState(doc),
                doc.getProcessingStage(),
                doc.getDisplayName(),
                DocumentItemAssembler.fileTypeOf(doc),
                doc.getDocumentRole(),
                DocumentItemAssembler.isEditable(doc, editState.isPresent()),
                doc.getCurrentVersion(),
                editRevision,
                doc.getSourceDocumentId(),
                editState.map(DocumentEditState::getUpdatedAt).orElse(doc.getUpdatedAt()),
                editState.map(DocumentEditState::getMarkdown).orElse(null),
                editLockService.getStatus(doc.getId())
        );
    }

    public DocumentContentSaveResponse saveContent(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            Long baseRevision,
            String revisionWriteId,
            String source
    ) {
        return saveContent(workspaceId, userId, documentId, markdown, baseRevision, revisionWriteId, source, null);
    }

    /**
     * 본문 저장은 PostgreSQL store가 한 transaction으로 처리한다(state·write receipt·outbox).
     * PostgreSQL version read model(document_content_versions)도 같은 transaction에 projection한다.
     *
     * @param applyOperationId Agent turn에서 발급한 적용 표. 검증에 성공하면 AI 작업 로그를 남긴다.
     *                         {@code source} 문자열은 클라이언트가 임의로 넣을 수 있어 신뢰하지 않는다.
     */
    public DocumentContentSaveResponse saveContent(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            Long baseRevision,
            String revisionWriteId,
            String source,
            String applyOperationId
    ) {
        return saveContent(workspaceId, userId, documentId, markdown, baseRevision, revisionWriteId,
                source, applyOperationId, false);
    }

    private DocumentContentSaveResponse saveContent(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            Long baseRevision,
            String revisionWriteId,
            String source,
            String applyOperationId,
            boolean applyOperationClaimed
    ) {
        TransactionTemplate saveTransaction = requiresNewSaveTransactionTemplate();
        try {
            return executeWithSaveRetry(() -> saveTransaction.execute(status -> saveContentInTransaction(
                    workspaceId, userId, documentId, markdown, baseRevision, revisionWriteId,
                    source, applyOperationId, applyOperationClaimed)));
        } catch (DocumentVersionConflictException conflict) {
            if (applyOperationId != null && !applyOperationId.isBlank()) {
                recordConflictInIndependentTransaction(
                        workspaceId, userId, documentId, markdown, baseRevision,
                        revisionWriteId, applyOperationId);
            }
            throw conflict;
        }
    }

    /**
     * 복구처럼 본문 저장과 추가 변경내역을 한 작업으로 묶어야 하는 호출자를 위한 저장 진입점이다.
     * 호출자는 이미 실제 PostgreSQL transaction을 소유해야 하며, transient 재시도는 그 소유 단위에서
     * 수행해야 한다.
     */
    public DocumentContentSaveResponse saveContentInCurrentTransaction(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            Long baseRevision,
            String revisionWriteId,
            String source
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("현재 PostgreSQL transaction 안에서 호출해야 합니다.");
        }
        return saveContentInTransaction(
                workspaceId, userId, documentId, markdown, baseRevision, revisionWriteId,
                source, null, false);
    }

    private DocumentContentSaveResponse saveContentInTransaction(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            Long baseRevision,
            String revisionWriteId,
            String source,
            String applyOperationId,
            boolean applyOperationClaimed
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        if (applyOperationId != null && !applyOperationId.isBlank()
                && !applyOperationStore.authorizeSave(applyOperationId, userId, documentId)) {
            throw new InvalidAgentTurnRequestException("취소되었거나 유효하지 않은 Agent 적용 표입니다.");
        }
        if (baseRevision == null || baseRevision < 1) {
            throw new InvalidMarkdownContentException("base_revision은 1 이상이어야 합니다.");
        }
        validateRevisionWriteId(revisionWriteId);

        Document document = documentRepository.findByIdAndWorkspaceIdForUpdate(documentId, workspaceId)
                .filter(value -> value.getDeletedAt() == null)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(document, userId);
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new InvalidMarkdownContentException("편집 가능한 Markdown 문서만 저장할 수 있습니다.");
        }
        requireNotChatExport(document, "채팅 Wiki page화 문서는 편집할 수 없습니다.");
        editLockService.requireWritable(documentId, userId);

        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.markdown(markdown);
        boolean hasApplyOperation = applyOperationId != null && !applyOperationId.isBlank();
        if (!applyOperationClaimed) {
            claimApplyOperation(workspaceId, userId, documentId, revisionWriteId,
                    baseRevision, content.markdown(), applyOperationId);
        }
        editStateInitializer.initializeIfNeeded(document);
        PostgresDocumentEditSaveResult result;
        result = hasApplyOperation
                    ? postgresDocumentEditStore.save(
                            workspaceId, documentId, content.markdown(), content.contentHash(), baseRevision,
                            revisionWriteId, userId, applyOperationId)
                    : postgresDocumentEditStore.save(
                            workspaceId, documentId, content.markdown(), content.contentHash(), baseRevision,
                            revisionWriteId, userId, null);
        if (result.replayed()) {
            return new DocumentContentSaveResponse(
                    documentId, result.revision(), result.contentHash(), result.updatedAt(), result.changed());
        }
        if (hasApplyOperation) {
            transactionTemplate.execute(status -> {
                projectContentVersions(documentId, content.markdown(), result);
                if (result.changed()) {
                    int linked = contentVersionRepository.linkOperation(documentId, result.revision(), applyOperationId);
                    if (linked == 1) {
                        operationRecorder.recordDocumentEdit(applyOperationId, workspaceId, userId, documentId,
                                document.getDisplayName(),
                                result.baseRevision(), result.revision(), result.baseMarkdown(),
                                content.markdown(), result.updatedAt());
                    } else if (!contentVersionRepository.findById(
                            new DocumentContentVersionId(documentId, result.revision()))
                            .map(version -> applyOperationId.equals(version.getOperationId()))
                            .orElse(false)) {
                        throw new IllegalStateException(
                                "문서 버전에 Agent 적용 작업을 연결하지 못했습니다: operationId=" + applyOperationId);
                    }
                } else {
                    operationRecorder.completeDocumentEditNoChange(
                            applyOperationId, workspaceId, userId, documentId, result.updatedAt());
                }
                return null;
            });
        } else {
            projectContentVersions(documentId, content.markdown(), result);
        }
        if (result.changed()) {
            // 재ingest 필요 판단용 projection: 목록 API가 PG만으로 현재 편집본 해시를 비교할 수 있게 한다.
            documentRepository.updateCurrentContentHash(documentId, result.contentHash(), result.updatedAt());
            // 이미지를 첨부하지 않는 저장에서도 본문에 남은 관리 이미지를 기준으로 참조를 맞춘다.
            // 그러지 않으면 본문에서 지운 이미지가 참조된 상태로 남아 정리 대상이 되지 않는다.
            assetReferenceSynchronizer.synchronize(
                    documentId, workspaceId, assetReferenceParser.parse(content.markdown()));
        }

        return new DocumentContentSaveResponse(
                documentId,
                result.revision(),
                result.contentHash(),
                result.updatedAt(),
                result.changed()
        );
    }

    private void validateRevisionWriteId(String revisionWriteId) {
        if (revisionWriteId == null || revisionWriteId.isBlank() || revisionWriteId.length() > 255) {
            throw new InvalidIdempotencyKeyException(
                    "revision_write_id는 1자 이상 255자 이하여야 합니다.");
        }
    }

    void claimApplyOperation(String workspaceId, String userId, String documentId, String revisionWriteId,
                             Long baseRevision, String markdown, String applyOperationId) {
        if (applyOperationId == null || applyOperationId.isBlank()) {
            return;
        }
        transactionTemplate.execute(status -> {
            if (!applyOperationStore.consume(applyOperationId, userId, documentId, revisionWriteId,
                    baseRevision, markdown)) {
                throw new InvalidAgentTurnRequestException(
                        "유효하지 않거나 이미 사용된 Agent 적용 표입니다.");
            }
            operationRecorder.prepareDocumentEdit(
                    applyOperationId, workspaceId, userId, documentId, Instant.now());
            return null;
        });
    }

    /**
     * PostgreSQL 편집 상태·write receipt·version/hash read model·감사·outbox와 같은 transaction에서 projection한다.
     * DB 단계가 실패하면 변경은 함께 rollback되고, object storage 정리는 호출자가 맡는다.
     */
    private void projectContentVersions(
            String documentId,
            String resultMarkdown,
            PostgresDocumentEditSaveResult result
    ) {
        if (!result.changed()) {
            return;
        }
        recordContentVersion(
                documentId,
                result.baseRevision(),
                result.baseMarkdown(),
                result.baseContentHash(),
                result.actorUserId(),
                result.updatedAt()
        );
        recordContentVersion(
                documentId,
                result.revision(),
                resultMarkdown,
                result.contentHash(),
                result.actorUserId(),
                result.updatedAt()
        );
    }

    /**
     * object storage 업로드 전에 인가·역할·편집 잠금을 먼저 확인한다.
     *
     * <p>base revision은 여기서 보지 않는다. 재전송된 같은 요청은 base가 이미 지나가 있어
     * 여기서 막으면 저장 계층의 revision_write_id 중복 판정에 닿지 못하고 첫 저장이
     * 실패로 보인다. revision 판정은 canonical인 PostgreSQL 저장 시점이 담당한다.
     */
    @Transactional(readOnly = true)
    public void validateContentSavePreconditions(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(document, userId);
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new InvalidMarkdownContentException("편집 가능한 Markdown 문서만 저장할 수 있습니다.");
        }
        requireNotChatExport(document, "채팅 Wiki page화 문서는 편집할 수 없습니다.");
        editLockService.requireWritable(documentId, userId);
    }

    /**
     * 이미지 포함 저장. PostgreSQL 편집 상태·write receipt·version/hash read model·asset·감사·outbox를
     * 하나의 transaction으로 기록한다. DB 실패 시 변경은 함께 rollback되고 object storage 정리는 호출자가 맡는다.
     *
     * <p>{@code revisionWriteId}와 asset ID가 모두 요청 내용에서 결정되므로, 같은 요청이
     * 재전송되면 본문까지 동일해져 PostgreSQL receipt가 첫 저장 결과를 그대로 돌려준다.
     */
    public DocumentContentSaveResponse saveContentWithAssets(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            long baseVersion,
            String revisionWriteId,
            Map<UUID, DocumentAssetStorageCoordinator.StoredAsset> storedAssets,
            String applyOperationId
    ) {
        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.markdown(markdown);
        Instant updatedAt = Instant.now();
        List<DocumentAsset> assets = storedAssets.values().stream()
                .map(stored -> new DocumentAsset(
                        stored.assetId(), workspaceId, userId,
                        stored.validated().originalFilename(), stored.validated().contentType(),
                        stored.validated().bytes().length, stored.validated().width(), stored.validated().height(),
                        stored.validated().contentHash(), stored.objectKey(), updatedAt))
                .toList();
        DocumentContentSaveResponse saved;
        TransactionTemplate saveTransaction = requiresNewSaveTransactionTemplate();
        try {
            saved = executeWithSaveRetry(() -> saveTransaction.execute(status -> {
                assetRepository.saveAll(assets);
                DocumentContentSaveResponse value = saveContentInTransaction(
                        workspaceId, userId, documentId, content.markdown(),
                        baseVersion, revisionWriteId, null, applyOperationId, false);
                if (!value.changed()) {
                    // 본문이 그대로면 새 asset row도 남기지 않는다. object storage 정리는 호출부가 한다.
                    assetRepository.deleteAllInBatch(assets);
                }
                return value;
            }));
        } catch (DocumentVersionConflictException conflict) {
            if (applyOperationId != null && !applyOperationId.isBlank()) {
                recordConflictInIndependentTransaction(
                        workspaceId, userId, documentId, content.markdown(), baseVersion,
                        revisionWriteId, applyOperationId);
            }
            throw conflict;
        }
        return new DocumentContentSaveResponse(
                saved.documentId(), saved.currentVersion(), saved.contentHash(), saved.updatedAt(),
                saved.changed(), content.markdown(), List.of());
    }

    private void recordContentVersion(String documentId, long version, String markdown,
                                      String contentHash, String createdBy, Instant createdAt) {
        contentVersionRepository.insertIfAbsent(documentId, version, markdown, contentHash, createdBy, createdAt);
    }

    private <T> T executeWithSaveRetry(java.util.function.Supplier<T> operation) {
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException exception) {
                if (attempt >= MAX_SAVE_ATTEMPTS || !isTransientSaveFailure(exception)) {
                    throw exception;
                }
            }
        }
    }

    private boolean isTransientSaveFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DuplicateKeyException
                    || cause instanceof DeadlockLoserDataAccessException
                    || cause instanceof CannotSerializeTransactionException) {
                return true;
            }
        }
        return false;
    }

    private void recordConflictInIndependentTransaction(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            Long baseRevision,
            String revisionWriteId,
            String applyOperationId
    ) {
        TransactionTemplate conflictTransaction = requiresNewSaveTransactionTemplate();
        conflictTransaction.execute(status -> {
            DocumentEditingRules.MarkdownContent content = DocumentEditingRules.markdown(markdown);
            if (!applyOperationStore.consume(
                    applyOperationId, userId, documentId, revisionWriteId,
                    baseRevision, content.markdown())) {
                throw new InvalidAgentTurnRequestException(
                        "유효하지 않거나 이미 사용된 Agent 적용 표입니다.");
            }
            operationRecorder.recordConflict(
                    applyOperationId, workspaceId, userId, documentId, Instant.now());
            return null;
        });
    }

    private TransactionTemplate requiresNewSaveTransactionTemplate() {
        TransactionTemplate requiresNew =
                new TransactionTemplate(transactionTemplate.getTransactionManager());
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return requiresNew;
    }

    /** 콘텐츠 버전 이력 목록(메타데이터만, 최신 버전 순). */
    @Transactional
    public DocumentContentVersionListResponse listContentVersions(
            String workspaceId, String userId, String documentId) {
        Document document = loadEditableForVersion(workspaceId, userId, documentId);
        editStateInitializer.initializeIfNeeded(document);
        long editRevision = postgresDocumentEditStore.findState(documentId)
                .map(DocumentEditState::getRevision)
                .orElseThrow(() -> new InvalidMarkdownContentException(
                        "현재 Markdown 편집 상태를 찾을 수 없습니다."));
        List<DocumentContentVersionListResponse.Item> items = contentVersionRepository.findSummaries(documentId).stream()
                .map(s -> new DocumentContentVersionListResponse.Item(
                        s.getVersion(), s.getContentHash(), s.getCreatedBy(), s.getCreatedAt()))
                .toList();
        return new DocumentContentVersionListResponse(documentId, editRevision, items);
    }

    /** 특정 버전의 전체 Markdown. */
    @Transactional(readOnly = true)
    public DocumentContentVersionResponse getContentVersion(
            String workspaceId, String userId, String documentId, long version) {
        loadEditableForVersion(workspaceId, userId, documentId);
        DocumentContentVersion snapshot = contentVersionRepository
                .findById(new DocumentContentVersionId(documentId, version))
                .orElseThrow(() -> new DocumentContentVersionNotFoundException(documentId, version));
        return new DocumentContentVersionResponse(documentId, snapshot.getVersion(), snapshot.getMarkdown(),
                snapshot.getContentHash(), snapshot.getCreatedBy(), snapshot.getCreatedAt());
    }

    /** 두 콘텐츠 버전의 줄 단위 변경 사항. */
    @Transactional(readOnly = true)
    public DocumentContentDiffResponse compareContentVersions(
            String workspaceId, String userId, String documentId, long fromVersion, long toVersion) {
        loadEditableForVersion(workspaceId, userId, documentId);
        DocumentContentVersion before = contentVersionRepository
                .findById(new DocumentContentVersionId(documentId, fromVersion))
                .orElseThrow(() -> new DocumentContentVersionNotFoundException(documentId, fromVersion));
        DocumentContentVersion after = contentVersionRepository
                .findById(new DocumentContentVersionId(documentId, toVersion))
                .orElseThrow(() -> new DocumentContentVersionNotFoundException(documentId, toVersion));
        return markdownDiffService.compare(
                documentId, fromVersion, before.getMarkdown(), toVersion, after.getMarkdown());
    }

    /**
     * 과거 버전을 새 버전으로 복원한다(비파괴적). 해당 버전의 Markdown을 현재 편집본으로 저장해 version을 1 증가시킨다.
     * base_version 낙관적 잠금으로 동시 편집 충돌을 막는다.
     */
    @Transactional
    public DocumentContentSaveResponse restoreContentVersion(
            String workspaceId, String userId, String documentId, long version, Long baseVersion) {
        loadEditableForVersion(workspaceId, userId, documentId);
        DocumentContentVersion target = contentVersionRepository
                .findById(new DocumentContentVersionId(documentId, version))
                .orElseThrow(() -> new DocumentContentVersionNotFoundException(documentId, version));
        return saveContent(
                workspaceId,
                userId,
                documentId,
                target.getMarkdown(),
                baseVersion,
                "restore:" + version + ":" + baseVersion,
                null
        );
    }

    private Document loadEditableForVersion(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(document, userId);
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new InvalidMarkdownContentException("편집 가능한 Markdown 문서만 버전 이력을 제공합니다.");
        }
        return document;
    }

    /**
     * 편집 가능 Markdown 문서를 최신 편집본으로 재ingest한다. DB 편집본을 MinIO 원본으로 승격(덮어쓰기)한 뒤
     * 파이프라인 처리 큐에 재등록해, 업로드 당시가 아닌 편집한 내용으로 Wiki가 만들어지게 한다.
     */
    @Transactional
    public DocumentIngestResponse ingest(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceIdForUpdate(documentId, workspaceId)
                .filter(value -> value.getDeletedAt() == null)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(document, userId);
        if ("chat_export".equals(document.getOrigin())) {
            if (document.getStatus() == DocumentStatus.processing) {
                throw new DocumentAlreadyProcessingException("이미 처리 중인 문서입니다.");
            }
            // 저장한 문답과 provenance를 그대로 사용한다. 편집본 승격은 필요하지 않다.
            document.updateStatus(DocumentStatus.processing, document.getExtractedTextUri(), null, null);
            document.markReconciled(null);
            String runId = enqueueIngest(document);
            return new DocumentIngestResponse(documentId, runId, document.getStatus());
        }
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new InvalidMarkdownContentException("편집 가능한 Markdown 문서만 재처리할 수 있습니다.");
        }
        editLockService.requireWritable(documentId, userId);
        if (document.getStatus() == DocumentStatus.processing) {
            throw new DocumentAlreadyProcessingException("이미 처리 중인 문서입니다.");
        }

        editStateInitializer.initializeIfNeeded(document);
        DocumentEditState editState = editStateRepository.findById(documentId)
                .orElseThrow(() -> new InvalidMarkdownContentException(
                        "현재 Markdown 편집 상태를 찾을 수 없습니다."));
        // 최신 편집본은 PostgreSQL 편집 상태가 canonical이다.
        String currentMarkdown = editState.getMarkdown();
        String currentContentHash = editState.getContentHash();

        // 원본 경로가 없으면 빈 키로 putObject가 나가 원인을 알기 어려운 500이 된다.
        // 문서 생성 시점에 원본을 만들지 않던 시절의 행이 여기로 들어온다.
        if (document.getSourceUri() == null || document.getSourceUri().isBlank()) {
            throw new InvalidMarkdownContentException(
                    "원본 파일 경로가 없는 문서는 재처리할 수 없습니다: " + documentId);
        }

        byte[] bytes = currentMarkdown.getBytes(StandardCharsets.UTF_8);
        try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(storageProps.getBucket())
                            .object(normalizeObjectKey(document.getSourceUri()))
                            .stream(inputStream, bytes.length, -1)
                            .contentType("text/markdown")
                            .build()
            );
        } catch (Exception e) {
            throw new DocumentUploadException("문서 원본 갱신 중 오류가 발생했습니다.", e);
        }

        document.reopenForReingest(currentContentHash, bytes.length);
        log.info("[문서 재ingest DB 갱신 완료] documentId={} contentHashPrefix={} byteSize={}",
                documentId, contentHashPrefix(currentContentHash), bytes.length);
        String runId = enqueueIngest(document);
        return new DocumentIngestResponse(documentId, runId, document.getStatus());
    }

    @Transactional
    public DocumentRenameResponse rename(
            String workspaceId,
            String userId,
            String documentId,
            DocumentRenameRequest request
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        if (request == null || request.baseVersion() == null || request.baseVersion() < 1) {
            throw new InvalidDocumentFilenameException("base_version은 1 이상이어야 합니다.");
        }

        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(document, userId);
        if (document.getCurrentVersion() != request.baseVersion()) {
            throw versionConflict();
        }

        DocumentEditingRules.Filename filename =
                DocumentEditingRules.rename(request.displayName(), document.getFilename());
        if (filename.filename().equals(document.getFilename())) {
            return new DocumentRenameResponse(
                    documentId,
                    document.getFilename(),
                    document.getDisplayName(),
                    document.getCurrentVersion(),
                    document.getUpdatedAt(),
                    false
            );
        }

        Instant updatedAt = Instant.now();
        int updated = documentRepository.renameIfVersionMatches(
                documentId,
                workspaceId,
                request.baseVersion(),
                filename.filename(),
                filename.displayName(),
                filename.normalizedFilename(),
                updatedAt
        );
        if (updated == 0) {
            throw conditionalUpdateFailure(workspaceId, documentId);
        }
        return new DocumentRenameResponse(
                documentId,
                filename.filename(),
                filename.displayName(),
                request.baseVersion() + 1,
                updatedAt,
                true
        );
    }

    private void verifyDocumentOwner(Document document, String userId) {
        if (!document.getUserId().equals(userId)) {
            throw new DocumentWriteForbiddenException("문서 소유자만 변경할 수 있습니다.");
        }
    }

    private DocumentVersionConflictException versionConflict() {
        return new DocumentVersionConflictException(
                "다른 변경이 먼저 저장되었습니다. 최신 문서를 다시 조회해 주세요.");
    }

    private RuntimeException conditionalUpdateFailure(String workspaceId, String documentId) {
        if (documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .isEmpty()) {
            return new DocumentNotFoundException(documentId);
        }
        return versionConflict();
    }

    @Transactional
    public DocumentLifecycleResponse delete(
            String workspaceId,
            String userId,
            String documentId,
            String idempotencyKey,
            DocumentLifecycleRequest request
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        validateIdempotencyKey(idempotencyKey);
        validateLifecycleRequest(request);
        Document document = documentRepository
                .findByIdAndWorkspaceIdForUpdate(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(document, userId);
        String endpointScope = "DELETE:/api/workspaces/" + workspaceId + "/documents";
        String requestHash = requestHash(
                documentId, "delete", Long.toString(request.baseVersion()));
        Optional<DocumentLifecycleResponse> replay = replayIdempotentRequest(
                userId, endpointScope, idempotencyKey, requestHash,
                DocumentLifecycleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (document.getDeletedAt() != null) {
            throw new DocumentNotFoundException(documentId);
        }
        Instant deletedAt = Instant.now();
        int updated = documentRepository.softDeleteIfVersionMatches(
                documentId,
                workspaceId,
                request.baseVersion(),
                userId,
                deletedAt,
                UUID.randomUUID()
        );
        if (updated == 0) {
            throw conditionalUpdateFailure(workspaceId, documentId);
        }

        DocumentLifecycleResponse response = new DocumentLifecycleResponse(
                documentId,
                request.baseVersion() + 1,
                true,
                deletedAt,
                document.getSortOrder()
        );
        saveIdempotencyRecord(
                userId, endpointScope, idempotencyKey, requestHash, response);
        return response;
    }

    public DocumentTrashResponse trash(String workspaceId, String userId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        return new DocumentTrashResponse(
                documentRepository
                        .findAllByWorkspaceIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(workspaceId)
                        .stream()
                        .map(document -> new DocumentTrashResponse.DocumentTrashItem(
                                document.getId(),
                                document.getFilename(),
                                document.getDisplayName(),
                                document.getDocumentRole(),
                                document.getCurrentVersion(),
                                document.getDeletedAt(),
                                document.getDeletedBy(),
                                document.getDeleteOperationId(),
                                document.getSourceDocumentId()
                        ))
                        .toList()
        );
    }

    @Transactional
    public DocumentLifecycleResponse restore(
            String workspaceId,
            String userId,
            String documentId,
            String idempotencyKey,
            DocumentLifecycleRequest request
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        validateIdempotencyKey(idempotencyKey);
        validateLifecycleRequest(request);
        Document document = documentRepository
                .findByIdAndWorkspaceIdForUpdate(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(document, userId);
        String endpointScope = "POST:/api/workspaces/" + workspaceId + "/documents/restore";
        String requestHash = requestHash(
                documentId, "restore", Long.toString(request.baseVersion()));
        Optional<DocumentLifecycleResponse> replay = replayIdempotentRequest(
                userId, endpointScope, idempotencyKey, requestHash,
                DocumentLifecycleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (document.getDeletedAt() == null) {
            throw new DocumentNotFoundException(documentId);
        }
        List<Document> rootItems = documentRepository.findRootItemsForUpdate(
                workspaceId, document.getDocumentRole());
        long sortOrder = rootItems.stream()
                .mapToLong(Document::getSortOrder)
                .max()
                .orElse(-1) + 1;
        Instant restoredAt = Instant.now();
        int updated = documentRepository.restoreIfVersionMatches(
                documentId,
                workspaceId,
                request.baseVersion(),
                sortOrder,
                restoredAt
        );
        if (updated == 0) {
            if (documentRepository
                    .findByIdAndWorkspaceIdAndDeletedAtIsNotNull(documentId, workspaceId)
                    .isPresent()) {
                throw versionConflict();
            }
            throw new DocumentNotFoundException(documentId);
        }

        DocumentLifecycleResponse response = new DocumentLifecycleResponse(
                documentId,
                request.baseVersion() + 1,
                false,
                null,
                sortOrder
        );
        saveIdempotencyRecord(
                userId, endpointScope, idempotencyKey, requestHash, response);
        return response;
    }

    /** 워크스페이스 삭제 시 소속 문서를 함께 정리한다. DB에 workspace_id FK CASCADE가 없어 애플리케이션에서 직접 처리한다. */
    @Transactional
    public void deleteAllByWorkspaceId(String workspaceId) {
        documentRepository.findAllByWorkspaceId(workspaceId).forEach(this::deleteInternal);
    }

    private void deleteInternal(Document document) {
        String documentId = document.getId();
        String sourceUri = document.getSourceUri();
        String extractedTextUri = document.getExtractedTextUri();

        // document 삭제
        documentRepository.delete(document);
        ingestCommandOutbox.enqueueDelete(documentId, document.getWorkspaceId());

        // commit 이후 MinIO 오브젝트 삭제
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteMinioObject(sourceUri);
                if (extractedTextUri != null) {
                    deleteMinioObject(extractedTextUri);
                }
            }
        });
    }

    private void validateLifecycleRequest(DocumentLifecycleRequest request) {
        if (request == null || request.baseVersion() == null || request.baseVersion() < 1) {
            throw new InvalidDocumentVersionException(
                    "base_version은 1 이상의 정수여야 합니다.");
        }
    }

    private void deleteMinioObject(String uri) {
        if (uri == null || uri.isBlank()) return;
        String objectKey = normalizeObjectKey(uri);
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(storageProps.getBucket())
                            .object(objectKey)
                            .build()
            );
        } catch (Exception e) {
            log.warn("[MinIO 오브젝트 삭제 실패] uri={} error={}", uri, e.getMessage());
        }
    }

    public DocumentBlocksResponse blocks(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        List<DocumentBlockResponse> blocks = pipelineWikiStateRequester.documentContext(workspaceId, documentId)
                .sourceBlocks().stream()
                .map(block -> new DocumentBlockResponse(block.blockId(), block.text()))
                .toList();

        return new DocumentBlocksResponse(documentId, blocks);
    }

    public DocumentOriginalResult getOriginal(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        String objectKey = normalizeObjectKey(document.getSourceUri());
        try {
            InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(storageProps.getBucket())
                            .object(objectKey)
                            .build());
            return new DocumentOriginalResult(document.getMimeType(), document.getFilename(), stream);
        } catch (Exception e) {
            throw new DocumentOriginalNotFoundException(documentId);
        }
    }

    private String normalizeObjectKey(String sourceUri) {
        String bucketPrefix = "s3://" + storageProps.getBucket() + "/";
        if (sourceUri.startsWith(bucketPrefix)) {
            return sourceUri.substring(bucketPrefix.length());
        }
        if (sourceUri.startsWith("s3://")) {
            int objectStart = sourceUri.indexOf('/', "s3://".length());
            return objectStart >= 0 ? sourceUri.substring(objectStart + 1) : sourceUri;
        }
        return sourceUri;
    }

    private void validateFilename(String filename) {
        if (filename == null) {
            throw new InvalidDocumentFilenameException("문서 이름은 1자 이상 255자 이하여야 합니다.");
        }
        String trimmed = filename.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255) {
            throw new InvalidDocumentFilenameException("문서 이름은 1자 이상 255자 이하여야 합니다.");
        }
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.indexOf('\0') >= 0) {
            throw new InvalidDocumentFilenameException("문서 이름에 허용되지 않는 문자가 포함되어 있습니다.");
        }
    }

    private String stripExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
    }


    private String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            throw new RuntimeException("해시 계산 실패", e);
        }
    }
}
