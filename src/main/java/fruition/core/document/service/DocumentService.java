package fruition.core.document.service;

import fruition.shared.i18n.ResponseLanguage;
import fruition.core.aitask.AiErrorMessages;
import fruition.shared.logging.CommittedLog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.shared.util.StorageProperties;
import fruition.shared.util.RetryingObjectPut;
import fruition.core.document.domain.ConversionReport;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentAsset;
import fruition.core.document.repository.DocumentAssetRepository;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.domain.DocumentStatus;
import fruition.shared.idempotency.IdempotencyService;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.exception.DocumentTitleLockedException;
import fruition.core.document.exception.DocumentOriginalNotFoundException;
import fruition.core.document.exception.DocumentUploadException;
import fruition.core.document.exception.InvalidDocumentOriginException;
import fruition.core.document.exception.DocumentAlreadyProcessingException;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.core.document.exception.TrashRetentionExpiredException;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import fruition.core.document.exception.InvalidDocumentFilenameException;
import fruition.core.document.exception.InvalidDocumentVersionException;
import fruition.shared.idempotency.InvalidIdempotencyKeyException;
import fruition.shared.idempotency.IdempotencyConflictException;
import fruition.shared.idempotency.IdempotencyInProgressException;
import fruition.core.document.exception.InvalidMarkdownContentException;
import fruition.core.document.exception.MarkdownContentTooLargeException;
import fruition.core.document.exception.UnsupportedDocumentFileException;
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
import fruition.core.document.repository.ConvertCommandOutbox;
import fruition.core.document.repository.DocumentConvertBatchStore;
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
import fruition.core.agent.repository.AgentRunCommandRepository;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspacePermissions;
import fruition.core.authz.WorkspaceSettingsClient;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays; import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class DocumentService {

    /** 서버가 만드는 문서 ID(doc_·chatdoc_ + hex)를 넉넉히 덮는다. 조회 전에 걸러 입력 크기와 문자를 제한한다. */
    private static final java.util.regex.Pattern CLIENT_DOCUMENT_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,128}");

    @org.springframework.beans.factory.annotation.Autowired
    private fruition.core.analytics.DocumentAnalytics analytics;

    @org.springframework.beans.factory.annotation.Autowired
    private AgentRunCommandRepository agentRuns;

    /** 사용자별 저장 용량. 단위 테스트처럼 주입되지 않으면 검사하지 않는다. */
    @org.springframework.beans.factory.annotation.Autowired
    private fruition.core.upload.UploadLimitService uploadLimits;

    /** 가져오기 세션 확인·항목 집계. 가져오기 쓰기(세션이 넘어온 호출)에서만 쓴다. */
    @org.springframework.beans.factory.annotation.Autowired
    private fruition.core.imports.ImportSessionStore importSessions;

    /**
     * 생성 경로의 업로드 한도 적용 방식. 내부 생성(AI 도구·회의록·초기 노트)은 {@link #NONE}으로 저장 용량을 보지 않는다.
     * 가져오기 쓰기도 저장 용량을 그대로 보고, 만든 문서를 그 세션으로 표시한다.
     */
    private record UploadAccounting(boolean limited, fruition.core.imports.ImportSessionStore.OpenSession importSession) {
        static final UploadAccounting NONE = new UploadAccounting(false, null);
        static final UploadAccounting UPLOAD = new UploadAccounting(true, null);
    }

    private static final int MAX_SAVE_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final String INITIAL_NOTE_FILENAME = "새 노트.md";
    private static final String CONVERT_PLACEHOLDER_MARKDOWN = Document.CONVERT_PLACEHOLDER_MARKDOWN;
    // 크기 상한을 넘은 변환 이미지 자리 표시. 정규식·치환 특수 문자가 없는 값이어야 한다.
    private static final String OVERSIZED_CONVERTED_IMAGE = "fruition-oversized-converted-image";
    private static final String PDF_MIME_TYPE = "application/pdf";
    private static final String MARKDOWN_MIME_TYPE = "text/markdown";
    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final DocumentRepository documentRepository;
    private final FolderRepository folderRepository;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final MinioClient minioClient;
    private final StorageProperties storageProps;
    private final IngestCommandOutbox ingestCommandOutbox;
    private final DocumentWikiRetirement documentWikiRetirement;
    private final PipelineWikiStateRequester pipelineWikiStateRequester;
    private final DocumentConvertQueueRepository convertQueueRepository;
    private final DocumentConvertBatchStore convertBatchStore;
    private final ConvertCommandOutbox convertCommandOutbox;
    private final fruition.core.aitask.repository.PipelineTaskCancellationClient pipelineCancellation;
    private final int convertPagesPerBatch;
    private final int convertMaxInFlight;
    private final WorkspaceAiModelClient workspaceAiModelClient;
    private final WorkspaceSettingsClient workspaceSettingsClient;
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
    private final DocumentAccessPolicy documentAccessPolicy;
    private final Duration trashRetention;

    public DocumentService(DocumentRepository documentRepository,
                           FolderRepository folderRepository,
                           WorkspaceAccessGuard workspaceAccessGuard,
                           MinioClient minioClient,
                           StorageProperties storageProps,
                           IngestCommandOutbox ingestCommandOutbox,
                           PipelineWikiStateRequester pipelineWikiStateRequester,
                           DocumentConvertQueueRepository convertQueueRepository,
                           DocumentConvertBatchStore convertBatchStore,
                           ConvertCommandOutbox convertCommandOutbox,
                           fruition.core.aitask.repository.PipelineTaskCancellationClient pipelineCancellation,
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
                           WorkspaceSettingsClient workspaceSettingsClient,
                           fruition.core.document.repository.AiCommandOutboxWriter taskWriter,
                           DocumentWikiRetirement documentWikiRetirement,
                           DocumentAccessPolicy documentAccessPolicy,
                           @Value("${app.document-trash.retention:30d}") Duration trashRetention,
                           @Value("${app.convert.pages-per-batch:10}") int convertPagesPerBatch,
                           @Value("${app.convert.max-in-flight:3}") int convertMaxInFlight) {
        this.convertPagesPerBatch = convertPagesPerBatch;
        this.convertMaxInFlight = convertMaxInFlight;
        this.documentAccessPolicy = documentAccessPolicy;
        this.trashRetention = trashRetention;
        this.taskWriter = taskWriter;
        this.documentWikiRetirement = documentWikiRetirement;
        this.documentRepository = documentRepository;
        this.folderRepository = folderRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.minioClient = minioClient;
        this.storageProps = storageProps;
        this.ingestCommandOutbox = ingestCommandOutbox;
        this.pipelineWikiStateRequester = pipelineWikiStateRequester;
        this.convertQueueRepository = convertQueueRepository;
        this.convertBatchStore = convertBatchStore;
        this.convertCommandOutbox = convertCommandOutbox;
        this.pipelineCancellation = pipelineCancellation;
        this.workspaceAiModelClient = workspaceAiModelClient;
        this.workspaceSettingsClient = workspaceSettingsClient;
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

    public DocumentUploadResponse upload(
            String workspaceId,
            String userId,
            String idempotencyKey,
            UUID folderId,
            MultipartFile file
    ) {
        return upload(workspaceId, userId, idempotencyKey, folderId, file, null);
    }

    /**
     * 바깥 트랜잭션 없이 {@link IdempotencyService#execute}로 멱등 선점 → 실행 트랜잭션 순서로 연다.
     * 바깥 트랜잭션이 연결을 쥔 채 선점(REQUIRES_NEW)이 연결을 하나 더 잡으면, 풀 크기만큼 동시에 들어올 때
     * 모두가 두 번째 연결을 기다리며 풀이 교착한다. 폴더를 지정하면 {@link #inFolderLockTransaction}을 본다.
     */
    public DocumentUploadResponse upload(
            String workspaceId,
            String userId,
            String idempotencyKey,
            UUID folderId,
            MultipartFile file,
            String origin
    ) {
        return inFolderLockTransaction(folderId,
                () -> uploadOnce(workspaceId, userId, idempotencyKey, folderId, file, origin, UploadAccounting.UPLOAD));
    }

    /**
     * 가져오기 세션의 PDF 직접 업로드 확정({@code X-Fruition-Import-Id}). 열린 세션이어야 하고(아니면 400 IMPORT_INVALID)
     * 만든 문서를 그 세션으로 표시하며 항목 수를 센다. 저장 용량·멱등성은 일반 업로드와 같고, PDF 업로드는 원래 자동 편입하지 않는다.
     * 일반 업로드처럼 바깥 트랜잭션 없이 연다({@link #upload} 참고).
     */
    public DocumentUploadResponse uploadImported(
            String workspaceId,
            String userId,
            String idempotencyKey,
            UUID folderId,
            MultipartFile file,
            String importId
    ) {
        return inFolderLockTransaction(folderId, () -> {
            var session = importSessions.requireOpen(workspaceId, userId, importId);
            return uploadOnce(workspaceId, userId, idempotencyKey, folderId, file, null,
                    new UploadAccounting(true, session));
        });
    }

    private DocumentUploadResponse uploadOnce(
            String workspaceId,
            String userId,
            String idempotencyKey,
            UUID folderId,
            MultipartFile file,
            String origin,
            UploadAccounting accounting
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        boolean skillReference = isSkillReferenceOrigin(origin);
        requireNoFolderForSkillReference(skillReference, folderId);
        verifyFolder(workspaceId, folderId);
        validateIdempotencyKey(idempotencyKey);
        try {
            String filename = file.getOriginalFilename();
            validateFilename(filename);
            // txt는 내용상 Markdown이므로 .md 이름의 편집 문서로 받는다.
            filename = filename.trim().replaceFirst("(?i)\\.txt$", ".md");
            String mimeType = resolveMimeType(filename);
            boolean markdownUpload = MARKDOWN_MIME_TYPE.equals(mimeType);
            if (skillReference && !markdownUpload) {
                throw new UnsupportedDocumentFileException("스킬 참고 문서는 Markdown 또는 txt 파일만 업로드할 수 있습니다.");
            }
            if (!markdownUpload && !(file instanceof StoredOriginal)) {
                requirePdfSignature(file);
            }
            DocumentEditingRules.MarkdownContent markdownContent =
                    markdownUpload ? readUploadedMarkdown(file) : null;
            String contentHash = markdownUpload ? markdownContent.contentHash()
                    : file instanceof StoredOriginal stored ? stored.storageFingerprint() : uploadedFileHash(file);
            String endpointScope = uploadEndpointScope(workspaceId);
            // 일반 업로드의 해시는 그대로 두어 배포 전후 같은 키 재요청이 충돌하지 않게 한다.
            String requestHash = skillReference
                    ? requestHash(filename.trim(), mimeType, contentHash, String.valueOf(folderId), origin)
                    : requestHash(filename.trim(), mimeType, contentHash, String.valueOf(folderId));
            String uploadFilename = filename;

            // 멱등 재요청은 execute가 저장된 응답을 돌려준다. 원본 저장은 실행 트랜잭션을 열기 전에 한다.
            AtomicReference<String> storedKey = new AtomicReference<>();
            long startedAt = System.nanoTime();
            return deleteStoredOnFailure("document_upload", storedKey, () -> idempotencyService.execute(userId, endpointScope,
                    idempotencyKey, requestHash, DocumentUploadResponse.class, 201, DocumentUploadResponse::id,
                    () -> {
                        documentAccessPolicy.requirePlacement(workspaceId, folderId, userId);
                        return storeUploadOriginal(workspaceId, userId, file, mimeType, storedKey);
                    },
                    documentId -> {
                        registerStoredSourceCleanup("document_upload", storedKey);
                        return storeUpload(workspaceId, userId, folderId, file, skillReference, uploadFilename,
                                mimeType, markdownContent, contentHash, documentId, storedKey.get(), accounting, startedAt);
                    }));
        } catch (Exception e) {
            if (isUploadRejection(e)) logUploadRejected(workspaceId, userId, e);
            // storeUpload가 이미 감싼 실패는 그대로 올린다.
            if (e instanceof DocumentUploadException || isUploadRejection(e)) {
                throw (RuntimeException) e;
            }
            throw new DocumentUploadException("파일 저장 중 오류가 발생했습니다.", e);
        }
    }

    /**
     * 실행 트랜잭션을 열기 전(선점 뒤)에 한도를 미리 보고 원본을 저장한다. 큰 원본을 쓰는 동안 DB 연결을 쥐지 않는다.
     * 저장한 키는 {@code storedKey}에 남겨 실패 정리에 쓴다. 문서 ID를 돌려준다.
     */
    private String storeUploadOriginal(String workspaceId, String userId, MultipartFile file, String mimeType,
                                       AtomicReference<String> storedKey) {
        String documentId = null;
        try {
            // 새 업로드는 원본을 저장하기 전에 잠금 없이 한도를 미리 본다.
            if (uploadLimits != null) {
                uploadLimits.precheck(userId, file.getSize());
            }
            log.info("[문서 업로드 요청] workspaceId={} userId={} filename={} contentType={} size={}",
                    workspaceId, userId, file.getOriginalFilename(), file.getContentType(), file.getSize());

            documentId = newDocumentId();
            String objectPath = "sources/documents/" + documentId + "/original";
            log.debug("[문서 원본 저장 시작] documentId={} bucket={} objectPath={} mimeType={} byteSize={}",
                    documentId, storageProps.getBucket(), objectPath, mimeType, file.getSize());

            if (file instanceof StoredOriginal stored) {
                stored.copyTo(objectPath);
            } else try (InputStream inputStream = file.getInputStream()) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(storageProps.getBucket())
                                .object(objectPath)
                                .stream(inputStream, file.getSize(), 16L * 1024 * 1024)
                                .contentType(mimeType)
                                .build()
                );
            }
            storedKey.set(objectPath);
            log.debug("[문서 원본 저장 완료] documentId={} workspaceId={} userId={} objectPath={}",
                    documentId, workspaceId, userId, objectPath);
            return documentId;
        } catch (Exception e) {
            if (isUploadRejection(e)) {
                throw (RuntimeException) e;
            }
            log.error("[문서 업로드 실패] operation=document_upload stage=storage errorCode=STORAGE_WRITE_FAILED retryable=true documentId={} workspaceId={} userId={} errorType={} 원인=원본 파일을 S3에 저장하지 못했습니다. 문서는 등록되지 않았습니다.",
                    documentId, workspaceId, userId, e.getClass().getSimpleName());
            throw new DocumentUploadException("파일 저장 중 오류가 발생했습니다.", e);
        }
    }

    private DocumentUploadResponse storeUpload(
            String workspaceId,
            String userId,
            UUID folderId,
            MultipartFile file,
            boolean skillReference,
            String filename,
            String mimeType,
            DocumentEditingRules.MarkdownContent markdownContent,
            String contentHash,
            String documentId,
            String objectPath,
            UploadAccounting accounting,
            long startedAt
    ) {
        boolean markdownUpload = markdownContent != null;
        try {
            // 같은 폴더에 같은 이름이 있으면 거절하지 않고 "이름 (2).pdf"처럼 번호를 붙인다.
            // 스킬 참고 문서는 참고 문서끼리만 비교한다. 동시에 같은 이름을 고르는 경합은 DB 고유 제약이 막는다.
            List<String> existingNames = skillReference
                    ? documentRepository.findActiveSkillReferenceNames(workspaceId)
                    : documentRepository.findActiveSiblingNames(workspaceId, folderId);
            String storedFilename = DocumentEditingRules.uniqueUploadFilename(filename,
                    new java.util.HashSet<>(existingNames)).filename();

            // 사용자 잠금은 저장이 끝난 뒤 문서 행을 쓰기 직전에 잡아 커밋까지 짧게 쥔다. 큰 원본을 쓰는 동안 잡으면
            // 같은 사용자의 다른 업로드가 그 시간 내내 줄을 선다. 잠금 아래에서 다시 확인해 병렬 업로드가 함께 한도를
            // 넘지 못하게 하고, 거절되면 롤백 정리(registerStoredSourceCleanup)가 방금 저장한 원본을 지운다.
            if (accounting.importSession() != null) {
                importSessions.countItems(accounting.importSession(), 1);
            }
            if (uploadLimits != null) {
                uploadLimits.enforce(userId, file.getSize());
            }

            Document document = new Document(
                    documentId,
                    workspaceId,
                    userId,
                    storedFilename,
                    mimeType,
                    file.getSize(),
                    objectPath,
                    contentHash,
                    skillReference ? Document.SKILL_REFERENCE_ORIGIN
                            : accounting.importSession() != null ? Document.NOTION_IMPORT_ORIGIN : "upload"
            );
            document.place(folderId, placementSortOrder(workspaceId, folderId, document.getDocumentRole()));
            if (accounting.importSession() != null) {
                document.markImported(accounting.importSession().id());
            }
            document.updateStatus(DocumentStatus.uploaded, null, null, null);
            if (!markdownUpload && !(file instanceof StoredOriginal)) {
                // 서버를 거친 PDF는 위에서 파일 전체 해시를 이미 계산했다. 직접 업로드는 worker가 나중에 채운다.
                document.recordOriginalSha256(contentHash);
            }
            documentRepository.save(document);
            if (analytics != null) analytics.uploaded(document);
            if (markdownUpload) {
                editStateRepository.save(new DocumentEditState(
                        documentId, markdownContent.markdown(), markdownContent.contentHash(), 1));
            }
            DocumentUploadResponse response = toUploadResponse(document, markdownUpload);
            // 스킬 참고 문서는 편입 대상이 아니다. 자동 편입은 업로드 커밋 뒤 별도 트랜잭션에서 한다.
            if (markdownUpload && !skillReference && accounting.importSession() == null) {
                autoIngestAfterCommit(workspaceId, userId, documentId,
                        markdownContent.contentHash(), markdownContent.bytes().length);
            }
            CommittedLog.debug(log, "[문서 DB 저장 완료] documentId={} workspaceId={} userId={} filename={} status={} sourceUri={}",
                    document.getId(), document.getWorkspaceId(), document.getUserId(),
                    document.getFilename(), document.getStatus(), document.getSourceUri());
            CommittedLog.info(log, "[문서 업로드 완료] operation=document_upload stage=completed documentId={} workspaceId={} userId={} status={} origin={} size={} elapsedMs={} 설명=원본 파일 저장과 문서 등록이 완료됐습니다.",
                    document.getId(), document.getWorkspaceId(), document.getUserId(), document.getStatus(), document.getOrigin(),
                    file.getSize(), (System.nanoTime() - startedAt) / 1_000_000);

            return response;
        } catch (Exception e) {
            if (isUploadRejection(e)) {
                throw (RuntimeException) e;
            }
            // 미리 저장한 원본은 호출자(deleteStoredOnFailure)와 롤백 정리가 지운다.
            throw new DocumentUploadException("파일 저장 중 오류가 발생했습니다.", e);
        }
    }

    /** 사용자 입력·권한·한도로 거절한 업로드. 공통 예외 로그에 없는 단계와 안정적인 코드를 남긴다. */
    private static void logUploadRejected(String workspaceId, String userId, Exception e) {
        String code = switch (e) {
            case UnsupportedDocumentFileException ignored when INVALID_PDF_MESSAGE.equals(e.getMessage()) -> "INVALID_PDF";
            case UnsupportedDocumentFileException ignored -> "UNSUPPORTED_DOCUMENT_FILE";
            case InvalidDocumentFilenameException ignored -> "INVALID_DOCUMENT_FILENAME";
            case InvalidDocumentOriginException ignored -> "INVALID_DOCUMENT_ORIGIN";
            case InvalidMarkdownContentException ignored -> "INVALID_MARKDOWN_CONTENT";
            case MarkdownContentTooLargeException ignored -> "MARKDOWN_CONTENT_TOO_LARGE";
            case DocumentWriteForbiddenException ignored -> "DOCUMENT_WRITE_FORBIDDEN";
            case InvalidIdempotencyKeyException ignored -> "INVALID_IDEMPOTENCY_KEY";
            case IdempotencyConflictException ignored -> "IDEMPOTENCY_KEY_CONFLICT";
            case IdempotencyInProgressException ignored -> "IDEMPOTENCY_IN_PROGRESS";
            case fruition.core.imports.DocumentImportException ignored -> "IMPORT_INVALID";
            case fruition.core.upload.UploadLimitException limit -> limit.getCode();
            default -> "UPLOAD_REJECTED";
        };
        String stage = switch (code) {
            case "DOCUMENT_WRITE_FORBIDDEN" -> "permission";
            case "FILE_TOO_LARGE", "TOO_MANY_FILES", "STORAGE_QUOTA_EXCEEDED" -> "limit";
            case "INVALID_IDEMPOTENCY_KEY", "IDEMPOTENCY_KEY_CONFLICT", "IDEMPOTENCY_IN_PROGRESS" -> "idempotency";
            default -> "validation";
        };
        log.warn("[문서 업로드 거부] operation=document_upload stage={} errorCode={} retryable={} workspaceId={} userId={} 원인={}",
                stage, code, "IDEMPOTENCY_IN_PROGRESS".equals(code), workspaceId, userId, CommittedLog.reason(code));
    }

    /** 업로드 거절(4xx)로 그대로 올리는 예외. 나머지는 DocumentUploadException으로 감싼다. */
    private static boolean isUploadRejection(Exception e) {
        return e instanceof DocumentWriteForbiddenException
                || e instanceof InvalidDocumentFilenameException
                || e instanceof InvalidDocumentOriginException
                || e instanceof InvalidIdempotencyKeyException
                || e instanceof IdempotencyConflictException
                || e instanceof IdempotencyInProgressException
                || e instanceof InvalidMarkdownContentException
                || e instanceof MarkdownContentTooLargeException
                || e instanceof UnsupportedDocumentFileException
                || e instanceof fruition.core.upload.UploadLimitException
                || e instanceof fruition.core.imports.DocumentImportException;
    }

    public DocumentUploadResponse createMarkdown(
            String workspaceId,
            String userId,
            String idempotencyKey,
            MarkdownDocumentCreateRequest request
    ) {
        return inFolderLockTransaction(folderOf(request), () ->
                createMarkdownWithDraft(workspaceId, userId, idempotencyKey, request, UploadAccounting.NONE));
    }

    /** MCP receipt is the idempotency boundary; the caller owns the surrounding DB transaction. */
    public DocumentUploadResponse createAgentMarkdownInCurrentTransaction(
            String workspaceId, String userId, MarkdownDocumentCreateRequest request) {
        if (request == null) throw new InvalidMarkdownContentException("Markdown 생성 요청은 필수입니다.");
        verifyFolder(workspaceId, request.folderId());
        DocumentEditingRules.Filename filename = DocumentEditingRules.rename(request.displayName(), "document.md");
        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.markdown(request.markdown());
        return createMarkdownDocument(workspaceId, userId, null, null, filename.filename(), content,
                "direct", request.folderId(), Document.AGENT_ORIGIN, true, UploadAccounting.NONE);
    }

    /**
     * MCP 승인 계획의 PDF 변환본 생성. 외부 AI가 만든 Markdown을 웹 변환 결과와 같은 규칙(data: 이미지 asset 분리,
     * 변환본 sanitize)으로 저장하고 원본 PDF를 연결한다. 내부 모델 변환이나 자동 편입은 시작하지 않는다.
     * 실행 시점에 원본이 그대로 PDF이고 hash가 승인 때와 같으며 요청자가 아직 변환할 수 있어야 한다.
     */
    public DocumentUploadResponse createConvertedMarkdownInCurrentTransaction(
            String workspaceId, String userId, String sourceDocumentId, String sourceContentHash,
            MarkdownDocumentCreateRequest request) {
        if (request == null) throw new InvalidMarkdownContentException("Markdown 생성 요청은 필수입니다.");
        Document source = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(sourceDocumentId, workspaceId)
                .filter(DocumentAccessPolicy::isPdf)
                .orElseThrow(() -> new DocumentNotFoundException(sourceDocumentId));
        documentAccessPolicy.requireConvert(source, userId);
        if (!McpDocumentDataGateway.pdfHash(source).equals(sourceContentHash)) {
            throw new DocumentVersionConflictException("원본 PDF가 승인 뒤 바뀌었습니다.");
        }
        verifyFolder(workspaceId, request.folderId());
        DocumentEditingRules.Filename filename = DocumentEditingRules.rename(request.displayName(), "document.md");
        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.markdown(
                AiMarkdownSanitizer.sanitizeConverted(externalizeConvertedImages(workspaceId, userId, request.markdown())));
        DocumentUploadResponse created = createMarkdownDocument(workspaceId, userId, null, null, filename.filename(),
                content, "direct", request.folderId(), Document.AGENT_ORIGIN, true, UploadAccounting.NONE);
        documentRepository.findById(created.id()).orElseThrow().linkSourceDocument(source.getId());
        assetReferenceSynchronizer.synchronize(created.id(), workspaceId, assetReferenceParser.parse(content.markdown()));
        return created;
    }

    /**
     * {@code POST /documents/markdown} 진입점. 사용자가 올린 문서로 보고 다른 업로드처럼 저장 용량 한도를 적용한다
     * (잠금 없는 사전 확인 → 원본 저장 → 사용자 잠금 아래 재확인).
     *
     * @param importId {@code X-Fruition-Import-Id}. 있으면 그 가져오기 세션의 문서로 만든다: 저장 용량은 그대로 보고,
     *                 세션 항목 수를 세며, 자동 편입하지 않는다. 열린 세션이 아니면 400 IMPORT_INVALID다.
     */
    public DocumentUploadResponse createMarkdownAsUpload(
            String workspaceId,
            String userId,
            String idempotencyKey,
            MarkdownDocumentCreateRequest request,
            String importId
    ) {
        return inFolderLockTransaction(folderOf(request), () ->
                createMarkdownAsUploadOnce(workspaceId, userId, idempotencyKey, request, importId));
    }

    private DocumentUploadResponse createMarkdownAsUploadOnce(
            String workspaceId,
            String userId,
            String idempotencyKey,
            MarkdownDocumentCreateRequest request,
            String importId
    ) {
        if (importId == null) {
            return createMarkdownWithDraft(workspaceId, userId, idempotencyKey, request, UploadAccounting.UPLOAD);
        }
        var session = importSessions.requireOpen(workspaceId, userId, importId);
        if (request != null && (request.origin() != null || request.agentRunId() != null)) {
            throw new InvalidDocumentOriginException("가져오기 문서에는 origin·agent_run_id를 지정할 수 없습니다.");
        }
        return createMarkdown(workspaceId, userId, idempotencyKey, request, null,
                new UploadAccounting(true, session));
    }

    /** 가져오기 세션으로 기존 문서 본문을 저장할 때: 열린 세션이고 그 세션이 만든 문서여야 한다. 아니면 400 IMPORT_INVALID. */
    public void requireImportDocument(String workspaceId, String userId, String documentId, String importId) {
        importSessions.requireDocument(importSessions.requireOpen(workspaceId, userId, importId), documentId);
    }

    private DocumentUploadResponse createMarkdownWithDraft(
            String workspaceId,
            String userId,
            String idempotencyKey,
            MarkdownDocumentCreateRequest request,
            UploadAccounting accounting
    ) {
        String agentRun = request == null ? null : ownAgentDraft(workspaceId, userId, request.agentRunId());
        return createMarkdown(workspaceId, userId, idempotencyKey, request,
                agentRun == null ? null : Document.AGENT_ORIGIN, accounting, agentRun);
    }

    /** 작업 탭 초안은 요청자 본인의 markdown_create run일 때만 AI 문서로 본다. 아니면 일반 문서로 만든다. */
    private String ownAgentDraft(String workspaceId, String userId, String agentRunId) {
        if (agentRunId == null || agentRunId.isBlank() || agentRuns == null) return null;
        return agentRuns.find(workspaceId, userId, agentRunId)
                .filter(run -> run.result() != null && "markdown_create".equals(run.result().path("action").asText()))
                .map(AgentRunCommandRepository.RunView::runId)
                .orElse(null);
    }

    /**
     * @param aiOrigin AI가 만든 문서면 {@link Document#AGENT_ORIGIN} 또는 {@link Document#MEETING_NOTES_ORIGIN}.
     *                 요청 본문의 origin과 달리 서버 내부 호출만 넘긴다. 호출자 트랜잭션이 있으면 실행이 거기에 합류한다.
     */
    public DocumentUploadResponse createMarkdown(
            String workspaceId,
            String userId,
            String idempotencyKey,
            MarkdownDocumentCreateRequest request,
            String aiOrigin
    ) {
        return inFolderLockTransaction(folderOf(request), () ->
                createMarkdown(workspaceId, userId, idempotencyKey, request, aiOrigin, UploadAccounting.NONE));
    }

    /**
     * 폴더를 지정한 생성은 지금처럼 바깥 트랜잭션에서 폴더 행을 잠근 뒤 멱등 선점을 한다. 같은 키의 동시 재요청이
     * 이 잠금에서 기다렸다가 첫 요청의 응답을 재생받기 때문이다. 잠금을 선점 뒤로 옮기면 그 사이에 온 재요청이
     * 409(처리 중)를 받게 되어 동작이 바뀌므로 이 경로는 그대로 둔다. 폴더가 없으면 선점 전에 잡는 잠금이 없어
     * 바깥 트랜잭션 없이 실행 트랜잭션만 연다(선점과 실행이 연결을 동시에 둘 쥐지 않는다).
     */
    private <T> T inFolderLockTransaction(UUID folderId, java.util.function.Supplier<T> work) {
        return folderId == null ? work.get() : transactionTemplate.execute(status -> work.get());
    }

    private static UUID folderOf(MarkdownDocumentCreateRequest request) {
        return request == null ? null : request.folderId();
    }

    private DocumentUploadResponse createMarkdown(
            String workspaceId,
            String userId,
            String idempotencyKey,
            MarkdownDocumentCreateRequest request,
            String aiOrigin,
            UploadAccounting accounting
    ) {
        return createMarkdown(workspaceId, userId, idempotencyKey, request, aiOrigin, accounting, null);
    }

    /** @param agentRun 작업 탭 초안이면 그 run ID. 새로 만든 AI 문서의 분석 기록을 문서와 같은 트랜잭션에 남긴다. */
    private DocumentUploadResponse createMarkdown(
            String workspaceId,
            String userId,
            String idempotencyKey,
            MarkdownDocumentCreateRequest request,
            String aiOrigin,
            UploadAccounting accounting,
            String agentRun
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        validateIdempotencyKey(idempotencyKey);
        if (request == null) {
            throw new InvalidMarkdownContentException("Markdown 생성 요청은 필수입니다.");
        }
        boolean skillReference = isSkillReferenceOrigin(request.origin());
        requireNoFolderForSkillReference(skillReference, request.folderId());
        verifyFolder(workspaceId, request.folderId());

        DocumentEditingRules.Filename filename =
                DocumentEditingRules.rename(request.displayName(), "document.md");
        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.markdown(request.markdown());
        String endpointScope = markdownEndpointScope(workspaceId);
        String requestHash = skillReference
                ? requestHash(filename.filename(), "text/markdown", content.contentHash(),
                        String.valueOf(request.folderId()), request.origin())
                : requestHash(filename.filename(), "text/markdown", content.contentHash(),
                        String.valueOf(request.folderId()));

        String origin = skillReference ? Document.SKILL_REFERENCE_ORIGIN
                : accounting.importSession() != null ? Document.NOTION_IMPORT_ORIGIN : "direct";
        // 멱등 재요청은 execute가 저장된 응답을 돌려준다. 한도 사전 확인과 원본 저장은 실행 트랜잭션을 열기 전에 한다.
        AtomicReference<String> storedKey = new AtomicReference<>();
        return deleteStoredOnFailure("document_create", storedKey, () -> idempotencyService.execute(userId, endpointScope, idempotencyKey,
                requestHash, DocumentUploadResponse.class, 201, DocumentUploadResponse::id, () -> {
                    documentAccessPolicy.requirePlacement(workspaceId, request.folderId(), userId);
                    // 새 문서는 원본을 저장하기 전에 잠금 없이 한도를 미리 본다.
                    if (accounting.limited() && uploadLimits != null) {
                        uploadLimits.precheck(userId, content.bytes().length);
                    }
                    String documentId = newDocumentId();
                    storedKey.set(putMarkdownSource(documentId, content.bytes()));
                    return documentId;
                }, documentId -> {
                    registerStoredSourceCleanup("document_create", storedKey);
                    DocumentUploadResponse created = createMarkdownDocument(workspaceId, userId, documentId,
                            storedKey.get(), filename.filename(), content, origin, request.folderId(), aiOrigin, true,
                            accounting);
                    // 같은 키의 재요청은 저장된 응답만 돌려주므로 처음 만들 때 한 번만 기록한다.
                    if (agentRun != null && analytics != null) {
                        documentRepository.findById(created.id())
                                .filter(document -> Document.AGENT_ORIGIN.equals(document.getAiOrigin()))
                                .ifPresent(document -> analytics.aiDocumentCreated(agentRun, document));
                    }
                    CommittedLog.info(log, "[문서 생성 완료] operation=document_create stage=completed documentId={} workspaceId={} userId={} origin={} runId={} 설명=원본 저장과 문서 등록이 완료됐습니다.",
                            created.id(), workspaceId, userId, origin, agentRun);
                    return created;
                }));
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
        // 복제본은 요청한 사람 소유의 새 문서다. 원본을 읽을 수 있고 원본이 있는 폴더에 넣을 수 있는 멤버면 복제할 수 있다.
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
        documentAccessPolicy.requirePlacement(workspaceId, source.getFolderId(), userId);

        Set<String> existingNames = new java.util.HashSet<>(
                documentRepository.findActiveSiblingNames(workspaceId, source.getFolderId()));
        DocumentEditingRules.Filename duplicateFilename =
                DocumentEditingRules.duplicateFilename(source.getDisplayName(), existingNames);
        long sortOrder = siblings.stream()
                .mapToLong(Document::getSortOrder)
                .max()
                .orElse(-1) + 1;
        DocumentEditingRules.MarkdownContent content =
                DocumentEditingRules.storedMarkdown(sourceEditState.getMarkdown());

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
        // 복제본도 내용은 AI 결과물이므로 AI 출처 표시를 잇는다.
        duplicate.markAiOrigin(source.getAiOrigin());
        documentRepository.save(duplicate);
        editStateRepository.save(new DocumentEditState(
                duplicateId, content.markdown(), content.contentHash(), 1));
        assetReferenceSynchronizer.copyReferences(documentId, duplicateId);

        DocumentDuplicateResponse response = toDuplicateResponse(duplicate);
        saveIdempotencyRecord(userId, endpointScope, idempotencyKey, requestHash, response);
        CommittedLog.info(log, "[문서 복제 완료] operation=document_duplicate stage=completed documentId={} sourceDocumentId={} workspaceId={} userId={}",
                duplicateId, documentId, workspaceId, userId);
        return response;
    }

    /** 새 워크스페이스에 직접 생성 Markdown 노트를 저장한다. 실패는 기존처럼 best-effort로 처리한다. */
    @Transactional
    public void createInitialNote(String workspaceId, String userId) {
        try {
            createMarkdownDocument(
                    workspaceId,
                    userId,
                    null,
                    null,
                    INITIAL_NOTE_FILENAME,
                    DocumentEditingRules.markdown("# 새 노트\n"),
                    "direct",
                    null,
                    null,
                    false,
                    UploadAccounting.NONE
            );
        } catch (Exception e) {
            log.warn("초기 노트 저장 실패로 건너뜁니다. workspaceId={}", workspaceId, e);
        }
    }

    /** 사용자가 지정할 수 있는 origin은 스킬 참고 문서뿐이다. 생략하면 일반 문서다. */
    private static boolean isSkillReferenceOrigin(String origin) {
        if (origin == null) {
            return false;
        }
        if (!Document.SKILL_REFERENCE_ORIGIN.equals(origin)) {
            throw new InvalidDocumentOriginException("origin은 skill_reference만 지정할 수 있습니다.");
        }
        return true;
    }

    /** 스킬 참고 문서는 문서 트리 밖에 있으므로 폴더에 둘 수 없다. */
    private static void requireNoFolderForSkillReference(boolean skillReference, UUID folderId) {
        if (skillReference && folderId != null) {
            throw new InvalidDocumentOriginException("스킬 참고 문서는 폴더를 지정할 수 없습니다.");
        }
    }

    private DocumentEditingRules.MarkdownContent readUploadedMarkdown(MultipartFile file) throws IOException {
        // 직접 업로드하는 Markdown 파일의 크기는 읽기 전에 검사한다.
        if (file.getSize() > DocumentEditingRules.MAX_MARKDOWN_BYTES) {
            throw new MarkdownContentTooLargeException("Markdown 본문은 UTF-8 기준 5MB 이하여야 합니다.");
        }
        try (InputStream stream = file.getInputStream()) {
            return DocumentEditingRules.markdown(stream.readNBytes(DocumentEditingRules.MAX_MARKDOWN_BYTES + 1));
        }
    }

    private String uploadedFileHash(MultipartFile file) throws IOException {
        try (InputStream stream = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int length; (length = stream.read(buffer)) != -1;) {
                digest.update(buffer, 0, length);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    /** 클라이언트가 보낸 Content-Type은 믿지 않고, 확장자로 서버가 정한 MIME만 저장한다. */
    private String resolveMimeType(String filename) {
        String normalizedFilename = filename.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalizedFilename.endsWith(".pdf")) {
            return PDF_MIME_TYPE;
        }
        if (normalizedFilename.endsWith(".md") || normalizedFilename.endsWith(".markdown")) {
            return MARKDOWN_MIME_TYPE;
        }
        throw new UnsupportedDocumentFileException("PDF, Markdown 또는 txt 파일만 업로드할 수 있습니다.");
    }

    private static final String INVALID_PDF_MESSAGE = "PDF 파일 내용이 올바르지 않습니다.";

    private void requirePdfSignature(MultipartFile file) throws IOException {
        try (InputStream stream = file.getInputStream()) {
            if (!Arrays.equals(stream.readNBytes(PDF_SIGNATURE.length), PDF_SIGNATURE)) {
                throw new UnsupportedDocumentFileException(INVALID_PDF_MESSAGE);
            }
        }
    }

    /**
     * @param documentId 원본을 트랜잭션 밖에서 미리 저장했으면 그 문서 ID와 {@code storedSourceKey}. 둘 다 null이면
     *                   여기서 ID를 정하고 원본을 저장한다.
     */
    private DocumentUploadResponse createMarkdownDocument(
            String workspaceId,
            String userId,
            String documentId,
            String storedSourceKey,
            String filename,
            DocumentEditingRules.MarkdownContent content,
            String origin,
            UUID folderId,
            String aiOrigin,
            boolean recordUpload,
            UploadAccounting accounting
    ) {
        if (documentId == null) documentId = newDocumentId();
        String sourceKey;
        if (storedSourceKey == null) {
            sourceKey = storeMarkdownSource(documentId, content.bytes());
        } else {
            // 미리 저장한 원본의 정리는 호출자가 registerStoredSourceCleanup으로 등록했다.
            sourceKey = storedSourceKey;
        }
        Document document = new Document(
                documentId,
                workspaceId,
                userId,
                filename,
                "text/markdown",
                content.bytes().length,
                sourceKey,
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
        document.markAiOrigin(aiOrigin);
        enforceMarkdownUpload(accounting, userId, content.bytes().length);
        if (accounting.importSession() != null) {
            document.markImported(accounting.importSession().id());
        }
        documentRepository.save(document);
        if (recordUpload && analytics != null) analytics.uploaded(document);
        editStateRepository.save(new DocumentEditState(
                documentId, content.markdown(), content.contentHash(), 1));
        return toUploadResponse(document, true);
    }

    /**
     * 원본 저장이 끝난 뒤 문서 행을 쓰기 직전에 사용자 잠금을 잡고 한도를 다시 확인한다(잠금은 커밋까지 짧게 쥔다).
     * 거절되면 롤백 정리(registerMinioRollbackCleanup)가 방금 저장한 원본을 지운다. 가져오기는 세션 항목을 하나 센다
     * (세션이 그 사이 완료·만료됐으면 400 IMPORT_INVALID).
     */
    private void enforceMarkdownUpload(UploadAccounting accounting, String userId, long bytes) {
        if (accounting.importSession() != null) {
            importSessions.countItems(accounting.importSession(), 1);
        }
        if (accounting.limited() && uploadLimits != null) {
            uploadLimits.enforce(userId, bytes);
        }
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
                document.getDocumentRole(),
                document.getFolderId()
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
        String objectPath = putMarkdownSource(documentId, bytes);
        registerMinioRollbackCleanup(objectPath);
        return objectPath;
    }

    /** 원본만 저장한다. 트랜잭션 밖에서 부르면 실패 정리는 호출자가 {@link #deleteStoredOnFailure}로 맡는다. */
    private String putMarkdownSource(String documentId, byte[] bytes) {
        String objectPath = "sources/documents/" + documentId + "/original";
        try {
            RetryingObjectPut.put(minioClient, storageProps.getBucket(), objectPath, bytes, "text/markdown");
        } catch (Exception e) {
            log.error("[문서 원본 저장 실패] operation=document_source_store stage=storage errorCode=STORAGE_WRITE_FAILED retryable=true documentId={} errorType={} 원인=Markdown 원본을 S3에 저장하지 못했습니다.",
                    documentId, e.getClass().getSimpleName());
            throw new DocumentUploadException("문서 원본 저장 중 오류가 발생했습니다.", e);
        }
        return objectPath;
    }

    /**
     * 실행 트랜잭션 전에 저장한 원본({@code storedKey})을 실패하면 지운다. 실행 안의 롤백 정리
     * (registerStoredSourceCleanup)는 트랜잭션을 열지 못한 실패(연결 대기 시간 초과 등)를 보지 못한다.
     * 실행이 최종 커밋됐으면 그 정리가 키를 비우므로, 커밋 뒤에 난 예외로는 원본을 지우지 않는다.
     * 두 정리가 겹쳐도 없는 객체 삭제라 무해하다.
     */
    private <T> T deleteStoredOnFailure(String operation, AtomicReference<String> storedKey, java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            String stored = storedKey.get();
            if (stored != null) {
                boolean cleaned = stored.startsWith(CLEANED_SOURCE);
                String key = cleaned ? stored.substring(CLEANED_SOURCE.length()) : stored;
                // 크레딧·권한 같은 거절도 여기를 지난다. 저장·DB 장애일 때만 등록 실패로 남긴다.
                if (e instanceof DocumentUploadException || e instanceof org.springframework.dao.DataAccessException
                        || e instanceof org.springframework.transaction.TransactionException) {
                    log.error("[문서 등록 실패] operation={} stage=db_register errorCode=DOCUMENT_REGISTER_FAILED retryable=true partial=true documentId={} errorType={} 원인=원본은 저장했지만 문서 DB 등록 또는 커밋에 실패해 트랜잭션을 롤백했습니다.",
                            operation, documentIdOf(key), e.getClass().getSimpleName());
                }
                if (!cleaned) {
                    storedKey.set(CLEANED_SOURCE + key);
                    cleanupStoredSource(operation, key, "request_failed");
                }
            }
            throw e;
        }
    }

    /** storedKey 표시: 롤백 정리가 이미 지웠다. 실패 처리가 같은 원본을 다시 지우거나 정리 로그를 두 번 남기지 않게 한다. */
    private static final String CLEANED_SOURCE = "cleaned:";

    private void cleanupStoredSource(String operation, String key, String trigger) {
        if (deleteMinioObject(key)) {
            log.info("[문서 원본 정리 완료] operation={} stage=cleanup trigger={} documentId={} 설명=등록하지 못한 원본 파일을 지웠습니다.",
                    operation, trigger, documentIdOf(key));
        } else {
            log.error("[문서 원본 정리 실패] operation={} stage=cleanup trigger={} errorCode=STORAGE_CLEANUP_FAILED retryable=false documentId={} objectPath={} 원인=등록하지 못한 원본 파일이 저장소에 남았습니다. 수동 확인이 필요합니다.",
                    operation, trigger, documentIdOf(key), key);
        }
    }

    /** sources/documents/{documentId}/original 형식의 키에서 문서 ID를 꺼낸다. */
    private static String documentIdOf(String key) {
        String[] parts = key.split("/");
        return parts.length > 2 ? parts[2] : "unknown";
    }

    /**
     * 트랜잭션 밖에서 미리 부른 외부 호출의 결과. 실패는 원래 그 호출을 하던 자리에서 {@link #get()}이 같은 예외로 던져,
     * 앞선 검증 오류가 먼저 나가는 순서를 지킨다.
     */
    private record Prefetched<T>(T value, RuntimeException failure) {
        static <T> Prefetched<T> of(java.util.function.Supplier<T> call) {
            try {
                return new Prefetched<>(call.get(), null);
            } catch (RuntimeException e) {
                return new Prefetched<>(null, e);
            }
        }

        T get() {
            if (failure != null) throw failure;
            return value;
        }
    }

    /**
     * 실행 트랜잭션(폴더를 지정해 바깥 트랜잭션에 합류했으면 그 트랜잭션)이 끝날 때, 커밋됐으면 {@code storedKey}를
     * 비워 {@link #deleteStoredOnFailure}가 지우지 않게 하고, 아니면 원본을 지운다. 실행 블록 첫머리에서 부른다.
     */
    private void registerStoredSourceCleanup(String operation, AtomicReference<String> storedKey) {
        String objectPath = storedKey.get();
        if (objectPath == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    storedKey.compareAndSet(objectPath, null);
                } else if (storedKey.compareAndSet(objectPath, CLEANED_SOURCE + objectPath)) {
                    cleanupStoredSource(operation, objectPath, "transaction_rollback");
                }
            }
        });
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

    /** 이름 앞에 이미 붙어 있는 접두사({@code [채팅]}, 괄호 안팎 공백 변형, 여러 번 반복 포함). */
    private static final java.util.regex.Pattern EXISTING_CHAT_EXPORT_PREFIX =
            java.util.regex.Pattern.compile("^(?:\\s*\\[\\s*채팅\\s*\\]\\s*)+");

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
                document.getWorkspaceId(), document.getFolderId(), title, document.getNormalizedFilename());
        if (filename.equals(document.getFilename())) {
            return;
        }
        document.rename(filename);
        documentRepository.save(document);
        // ai 소스 페이지는 이미 이 생성 제목을 쓴다. 다시 보내면 "[채팅] " 접두사가 위키 제목에 붙는다.
        log.info("[채팅 export 문서 이름 확정] documentId={} filename={}", document.getId(), filename);
    }

    /**
     * 채팅 export 문서 이름을 만든다. 채팅에서 온 문서임을 알리는 접두사를 붙이고, 같은 폴더의 기존 항목과
     * 겹치면 {@code (2)}를 더한다. export 시점과 이름 확정 시점이 모두 이 경로를 지나 접두사가 유지된다.
     * Wiki 페이지 제목처럼 이미 접두사가 붙은 이름이 오면 떼고 하나만 붙인다({@code [채팅] [채팅]} 방지).
     *
     * <p>이름은 세션 제목이나 AI가 만든 페이지 제목에서 오므로 파일명에 못 쓰는 문자가 섞일 수 있어
     * 여기서 정제한다. 접두사가 늘 남으므로 정제 결과가 비는 일은 없다.
     *
     * <p>동시에 같은 이름을 선택하는 경합은 DB 고유 제약으로 거절한다.
     */
    private String uniqueChatExportFilename(String workspaceId, UUID folderId, String displayName,
                                            String excludedNormalizedFilename) {
        Set<String> existingNames = documentRepository.findActiveSiblingNames(workspaceId, folderId).stream()
                .filter(name -> excludedNormalizedFilename == null || !name.equals(
                        java.text.Normalizer.normalize(excludedNormalizedFilename.trim(), java.text.Normalizer.Form.NFC)
                                .toLowerCase(java.util.Locale.ROOT)))
                .collect(Collectors.toSet());
        // AI 제목이 NFD로 오면 "채팅"이 자모로 풀려 접두사 검사를 빠져나가므로 NFC로 맞춘 뒤 비교한다.
        String baseName = displayName == null ? "" : EXISTING_CHAT_EXPORT_PREFIX.matcher(
                java.text.Normalizer.normalize(displayName, java.text.Normalizer.Form.NFC)).replaceFirst("");
        String candidate = DocumentEditingRules.sanitizeDisplayName(CHAT_EXPORT_NAME_PREFIX + baseName);
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

    /**
     * PDF 변환 중에는 묶음마다 본문이 이어 붙어 revision이 오르므로 그동안의 사용자·Agent 저장은 막는다.
     * 파트 문서는 부모(변환 placeholder)의 변환 상태를 따른다.
     */
    private void requireNotConverting(Document document) {
        Document converting = "convert_part".equals(document.getOrigin()) && document.getSourceDocumentId() != null
                ? documentRepository.findById(document.getSourceDocumentId()).orElse(null)
                : document;
        if (converting != null && converting.getStatus() == DocumentStatus.processing
                && converting.getPipelineRunId() != null && converting.getPipelineRunId().startsWith("convert:")) {
            throw new DocumentAlreadyProcessingException("PDF 변환 중에는 편집할 수 없습니다.");
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
                documentId, workspaceId, userId, uniqueChatExportFilename(workspaceId, null, displayName, null),
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

        try {
            RetryingObjectPut.put(minioClient, storageProps.getBucket(), objectPath, bytes, "text/markdown");
        } catch (Exception e) {
            throw new DocumentUploadException("채팅 export 저장 중 오류가 발생했습니다.", e);
        }
        registerMinioRollbackCleanup(objectPath);

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("예약한 chat export 문서를 찾을 수 없습니다."));
        // 파이프라인 입력은 항상 command inline이다. storage 원본은 사용자에게 보여줄 본문으로만 쓴다.
        document.assignPipelineInput(markdown, serializePipelineBlocks(blocks));
        if (analytics != null) analytics.uploaded(document);
        log.info("[채팅 export 문서 DB 저장 완료] documentId={} workspaceId={} userId={} filename={} selectionMode={} status={} sourceUri={}",
                document.getId(), document.getWorkspaceId(), document.getUserId(), document.getFilename(),
                document.getSelectionMode(), document.getStatus(), document.getSourceUri());

        return new ExportDocumentResult(documentId, false);
    }

    /**
     * PDF 원본 문서의 Markdown 변환을 요청한다. 변환 결과를 담을 placeholder Markdown 문서를 즉시 만들어
     * 반환하고, 실제 변환은 첫 batch command를 Kafka로 발행해 converter worker가 비동기로 수행한다.
     *
     * <p>바깥 트랜잭션을 두지 않고 {@link IdempotencyService#execute}로 멱등 선점 → 실행 트랜잭션 순서로 연다.
     * 바깥 트랜잭션이 연결을 쥔 채 선점(REQUIRES_NEW)이 연결을 하나 더 잡으면, 풀 크기만큼 동시에 들어올 때
     * 모두가 두 번째 연결을 기다리며 풀이 교착한다(connectionTimeout까지 무관한 API도 멈춤).
     */
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
        // 남이 올린 PDF의 변환은 업로더 본인·OWNER·document.convert 권한자만 요청한다. 폴더 판정(아래)까지 합치면 can_convert와 같다.
        documentAccessPolicy.requireConvert(source, userId);

        String endpointScope = convertEndpointScope(workspaceId);
        String requestHash = requestHash(documentId, "convert-markdown", "");
        // placeholder 원본 저장과 모델 설정(access) 조회는 실행 트랜잭션을 열기 전에 한다. 실패는 원래 호출하던 자리에서
        // 던져(Prefetched) 크레딧 예약 거절 같은 앞선 오류가 먼저 나가는 순서를 지킨다.
        DocumentEditingRules.MarkdownContent content =
                DocumentEditingRules.markdown(CONVERT_PLACEHOLDER_MARKDOWN);
        AtomicReference<String> storedKey = new AtomicReference<>();
        return deleteStoredOnFailure("document_convert", storedKey, () -> idempotencyService.execute(userId, endpointScope,
                idempotencyKey, requestHash, DocumentUploadResponse.class, 201, DocumentUploadResponse::id,
                () -> {
                    // 변환본은 원본과 같은 폴더에 새 문서로 들어간다.
                    documentAccessPolicy.requirePlacement(workspaceId, source.getFolderId(), userId);
                    String placeholderId = newDocumentId();
                    Prefetched<String> stored = Prefetched.of(() -> putMarkdownSource(placeholderId, content.bytes()));
                    storedKey.set(stored.value());
                    return new ConvertPrepared(placeholderId, stored,
                            Prefetched.of(() -> workspaceAiModelClient.get(workspaceId)),
                            // 조회 실패는 꺼진 것으로 돌려받는다. 외부 AI 전송은 명시적으로 켠 워크스페이스만 한다.
                            workspaceSettingsClient.describePdfFigures(workspaceId),
                            // 변환이 끝나면 자동 편입할지는 요청 때 정해 문서에 남긴다. 도중에 설정이 바뀌어도 이 변환은 따른다.
                            // 가져온 PDF의 변환본은 자동 편입하지 않고 가져오기 편입으로 고른다.
                            source.getImportId() == null
                                    && autoIngestAllowed(workspaceId, userId, AutoIngestPath.PDF_CONVERT));
                },
                prepared -> {
                    registerStoredSourceCleanup("document_convert", storedKey);
                    return startConvertPlaceholder(workspaceId, userId, documentId, source, content, prepared);
                }));
    }

    private record ConvertPrepared(String placeholderId, Prefetched<String> sourceKey,
                                   Prefetched<WorkspaceAiModelClient.AiModelSelection> aiModel,
                                   boolean describeFigures, boolean autoIngest) {}

    private DocumentUploadResponse startConvertPlaceholder(String workspaceId, String userId, String documentId,
                                                           Document source,
                                                           DocumentEditingRules.MarkdownContent content,
                                                           ConvertPrepared prepared) {
        // placeholder: display_name은 원본에서 확장자를 뗀 이름, filename은 <이름>.md
        DocumentEditingRules.Filename filename =
                DocumentEditingRules.rename(source.getDisplayName(), "document.md");
        String placeholderId = prepared.placeholderId();
        String convertRunId = "convert:" + placeholderId;
        taskWriter.begin(convertRunId, workspaceId, userId, "convert");
        Document placeholder = new Document(
                placeholderId,
                workspaceId,
                userId,
                filename.filename(),
                "text/markdown",
                content.bytes().length,
                prepared.sourceKey().get(),
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
        // 가져온 PDF의 변환본도 같은 가져오기 문서로 표시한다. 가져온 문서는 자동 편입하지 않고 가져오기 편입으로 고른다.
        if (source.getImportId() != null) {
            placeholder.markImported(source.getImportId());
        }
        placeholder.scheduleAutoIngestOnConvert(prepared.autoIngest());
        documentRepository.save(placeholder);
        editStateRepository.save(new DocumentEditState(
                placeholderId, content.markdown(), content.contentHash(), 1));
        startConvert(placeholder, source, prepared.aiModel(), prepared.describeFigures());

        DocumentUploadResponse response = toUploadResponse(placeholder, true);
        CommittedLog.info(log, "[문서 변환 요청 접수] operation=document_convert stage=accepted workspaceId={} userId={} sourceDocumentId={} documentId={} 설명=변환 작업을 등록했습니다. 변환 완료는 별도 로그로 남습니다.",
                workspaceId, userId, documentId, placeholderId);
        return response;
    }

    public String originalReadUrl(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!"aws".equals(storageProps.getCredentialsMode()) || !DocumentAccessPolicy.isPdf(document)) return null;
        try {
            return minioClient.getPresignedObjectUrl(io.minio.GetPresignedObjectUrlArgs.builder()
                    .bucket(storageProps.getBucket()).object(normalizeObjectKey(document.getSourceUri()))
                    .method(io.minio.http.Method.GET).expiry(3600)
                    .extraQueryParams(java.util.Map.of("response-content-type", "application/pdf",
                            "response-content-disposition", "inline"))
                    .build());
        } catch (Exception e) { throw new DocumentConvertException("원본 읽기 URL 발급 실패", e); }
    }

    private String convertEndpointScope(String workspaceId) {
        return "POST:/api/workspaces/" + workspaceId + "/documents/convert-markdown";
    }

    /**
     * 변환 요청 트랜잭션 안에서 진행 상태 행을 만들고 첫 batch command를 outbox에 넣는다. 변환 run 등록·예약과
     * placeholder 생성이 같은 커밋이라 command만 사라지거나 run 없이 command가 나가지 않는다.
     * 이후 batch는 결과 이벤트({@link #applyConvertBatch})가 진행한다.
     */
    private void startConvert(Document placeholder, Document source,
                              Prefetched<WorkspaceAiModelClient.AiModelSelection> aiModel, boolean describeFigures) {
        // queue 행이 placeholder를 FK로 참조한다. IDENTITY라 저장 즉시 INSERT되므로 placeholder를 먼저 내보낸다.
        documentRepository.flush();
        var queue = convertQueueRepository.save(new DocumentConvertQueue(placeholder.getId(), source.getId()));
        var model = aiModel.get();
        dispatchConvertBatch(queue, placeholder, normalizeObjectKey(source.getSourceUri()),
                model.provider(), model.model(), describeFigures, 0, convertPagesPerBatch);
        queue.dispatchedUpTo(convertPagesPerBatch);
    }

    private void dispatchConvertBatch(DocumentConvertQueue queue, Document placeholder, String sourceKey,
                                      String provider, String model, boolean describeFigures,
                                      int startPage, int pageCount) {
        String command = convertCommandOutbox.enqueue(placeholder.getId(), placeholder.getWorkspaceId(),
                placeholder.getUserId(), sourceKey, startPage, pageCount, provider, model, describeFigures);
        convertBatchStore.insertDispatched(queue.getId(), startPage, pageCount, command);
        log.info("[문서 변환 batch 발행] documentId={} startPage={} pageCount={}", placeholder.getId(), startPage, pageCount);
    }

    /** 변환 결과 이벤트가 같은 문서의 대기열 행 잠금을 기다리는 최대 시간. */
    private static final String CONVERT_QUEUE_LOCK_TIMEOUT = "5s";

    private static final java.util.regex.Pattern CONVERT_BATCH_RUN_ID =
            java.util.regex.Pattern.compile("^convert:([^:]+):(\\d+)$");

    /** 변환 batch 이벤트를 반영한 결과. 트랜잭션이 끝난 뒤에 할 일(취소 요청·자동 ingest)을 담는다. */
    private record ConvertOutcome(Document placeholder, boolean completed, List<DocumentConvertBatchStore.Batch> toCancel) {}

    /**
     * {@code ai.task.event}의 {@code convert_batch} 결과를 반영한다. placeholder 삭제·run 종료·중복 이벤트는 로그만 남기고 무시한다.
     * 성공이면 결과를 기록하고 쪽 순서로 이어진 결과를 모두 병합한 뒤 비어 가는 만큼 다음 batch를 발행하고,
     * 모두 병합되면 정산하고 자동 ingest를 시작한다. 실패는 변환을 실패로 닫고 남은 batch를 취소한다.
     */
    public void applyConvertBatch(JsonNode event) {
        // MCP 외부 AI 변환의 전처리 batch다. 세션은 AI가 갖고 문서는 승인 뒤에만 만든다.
        if (event.path("run_id").asText("").startsWith("mcpconvert:")) return;
        var matcher = CONVERT_BATCH_RUN_ID.matcher(event.path("run_id").asText(""));
        if (!matcher.matches()) {
            log.warn("[문서 변환 이벤트 무시] runId={} reason=invalid_run_id", event.path("run_id").asText(""));
            return;
        }
        String documentId = matcher.group(1);
        int startPage = Integer.parseInt(matcher.group(2));
        ConvertOutcome outcome = transactionTemplate.execute(status -> mergeConvertBatch(event, documentId, startPage));
        if (outcome == null) return;
        // 남은 batch 취소는 HTTP라 커밋 뒤에 한다. 실패해도 변환은 이미 닫혔고 AI의 batch 마감이 정리한다.
        for (var batch : outcome.toCancel()) {
            try {
                pipelineCancellation.cancel(ConvertCommandOutbox.batchRunId(documentId, batch.startPage()),
                        outcome.placeholder().getWorkspaceId(), outcome.placeholder().getUserId(),
                        objectMapper.readTree(batch.command()));
            } catch (Exception e) {
                log.warn("[문서 변환 batch 취소 실패] operation=document_convert stage=cancel_batch retryable=false documentId={} startPage={} errorType={} 원인=남은 변환 조각의 취소 요청을 전달하지 못했습니다. 변환은 이미 닫혔습니다.",
                        documentId, batch.startPage(), e.getClass().getSimpleName());
            }
        }
        if (outcome.completed()) ingestConvertedParts(outcome.placeholder());
    }

    private ConvertOutcome mergeConvertBatch(JsonNode event, String documentId, int startPage) {
        // 같은 문서의 이벤트가 동시에 와도 결과 기록·병합·발행을 한 번에 하나씩 처리한다.
        // 잠금을 쥔 쪽은 MinIO·이미지 작업을 하므로 기다리는 쪽이 DB 연결을 오래 잡지 않게 대기를 제한한다.
        // 시간이 지나면 예외로 트랜잭션을 되돌리고, ai.task.event error handler가 같은 레코드를 다시 소비한다.
        String lockTimeout = convertQueueRepository.currentLockTimeout();
        convertQueueRepository.setLocalLockTimeout(CONVERT_QUEUE_LOCK_TIMEOUT);
        var queue = convertQueueRepository.findByDocumentIdForUpdate(documentId).orElse(null);
        convertQueueRepository.setLocalLockTimeout(lockTimeout);
        // placeholder는 잠금을 잡은 뒤에 읽는다. 먼저 읽으면 앞 batch가 커밋한 변환 요약을 덮어써 잃는다.
        Document placeholder = documentRepository.findByIdInActiveWorkspace(documentId).orElse(null);
        if (placeholder == null) {
            log.info("[문서 변환 이벤트 무시] documentId={} startPage={} reason=document_not_found", documentId, startPage);
            return null;
        }
        String convertRunId = "convert:" + documentId;
        if (queue == null || !taskWriter.join(convertRunId)) {
            log.info("[문서 변환 이벤트 무시] documentId={} startPage={} reason=run_not_active", documentId, startPage);
            return null;
        }
        var batch = convertBatchStore.find(queue.getId(), startPage).orElse(null);
        if (batch == null || !DocumentConvertBatchStore.DISPATCHED.equals(batch.status())) {
            log.info("[문서 변환 이벤트 무시] documentId={} startPage={} reason=duplicate", documentId, startPage);
            return null;
        }
        if (!"succeeded".equals(event.path("status").asText())) {
            return failConvert(queue, placeholder, event.path("error").asText(null), startPage);
        }
        JsonNode payload = event.path("payload");
        int pageEnd = payload.path("page_end").asInt(-1);
        int total = payload.path("total_pages").asInt(-1);
        String resultKey = payload.path("result_key").asText("");
        String expectedKey = "tmp/convert/" + placeholder.getWorkspaceId() + "/" + documentId + "/" + startPage + ".md";
        int expectedEnd = startPage + batch.pageCount();
        // 이후 batch를 시작 쪽으로 이어 붙이므로 쪽 범위가 발행한 범위와 맞지 않으면 병합할 수 없다.
        if (payload.path("page_start").asInt(-1) != startPage + 1 || total < 1 || pageEnd < startPage + 1
                || pageEnd != Math.min(expectedEnd, total) || !expectedKey.equals(resultKey)) {
            log.warn("[문서 변환 결과 거절] operation=document_convert stage=validation errorCode=CONVERT_RESULT_INVALID documentId={} startPage={} 원인=AI가 돌려준 쪽 범위나 결과 위치가 요청과 다릅니다.", documentId, startPage);
            return failConvert(queue, placeholder, "internal_error", startPage);
        }
        convertBatchStore.markReady(queue.getId(), startPage, pageEnd, resultKey);
        queue.recordTotalPages(total);
        // 복원 요약·그림 설명 집계는 선택 필드다. 중복 이벤트는 위에서 걸러 두 번 더하지 않는다.
        placeholder.mergeConversionReport(ConversionReport.fromBatchPayload(payload));
        try {
            while (queue.getCompletedPages() < total) {
                var next = convertBatchStore.find(queue.getId(), queue.getCompletedPages()).orElse(null);
                if (next == null || !DocumentConvertBatchStore.READY.equals(next.status())) break;
                mergeConvertedBatch(queue, placeholder, readConvertResult(next.resultKey()),
                        next.startPage() + 1, next.pageEnd(), total);
                convertBatchStore.delete(queue.getId(), next.startPage());
            }
        } catch (DocumentConvertException e) {
            log.warn("[문서 변환 병합 실패] operation=document_convert stage=merge errorCode=CONVERT_MERGE_FAILED documentId={} errorType={} error={} 원인=변환 결과를 읽거나 문서에 이어 붙이지 못했습니다.", documentId, e.getClass().getSimpleName(), e.getMessage());
            return failConvert(queue, placeholder, "internal_error", -1);
        }
        if (queue.getCompletedPages() >= total) {
            documentRepository.findByIdInActiveWorkspace(documentId).ifPresent(doc -> {
                var content = postgresDocumentEditStore.findState(doc.getId()).orElseThrow();
                if (doc.getPipelineRunId() == null || doc.getPipelineRunId().startsWith("convert:")) {
                    doc.completeConvert(content.getContentHash(),
                            content.getMarkdown().getBytes(StandardCharsets.UTF_8).length, Instant.now());
                }
            });
            // 변환 실행은 자동 ingest를 시작하기 전에 닫고 예약을 푼다. 남겨 두면 그 예약이 가용 잔액을 줄여 ingest를 거절시킨다.
            taskWriter.completeAndSettle(convertRunId);
            if (analytics != null) analytics.converted(placeholder, queue.getSourceDocumentId(), total, null);
            convertQueueRepository.delete(queue);
            CommittedLog.info(log, "[문서 변환 완료] operation=document_convert stage=completed documentId={} workspaceId={} userId={} totalPages={} 설명=변환 결과를 문서에 저장했습니다. 위키 편입은 별도 작업입니다.",
                    documentId, placeholder.getWorkspaceId(), placeholder.getUserId(), total);
            logConversionReport(placeholder, total);
            return new ConvertOutcome(placeholder, true, List.of());
        }
        // 결과를 기다리는 batch와 순서를 기다리는 결과를 합쳐 최대 maxInFlight개만 문서에 열어 둔다.
        JsonNode template = readCommand(batch.command());
        int inFlight = convertBatchStore.count(queue.getId());
        int nextStart = queue.getNextStartPage();
        while (inFlight < convertMaxInFlight && nextStart < total) {
            dispatchConvertBatch(queue, placeholder, template.path("source_key").asText(),
                    template.path("provider").asText(), template.path("model").asText(),
                    // 변환 요청 때 정한 그림 설명 여부를 모든 batch가 따른다. 도중에 설정이 바뀌어도 한 문서 안에서는 같다.
                    template.path("describe_figures").asBoolean(false), nextStart, batch.pageCount());
            nextStart += batch.pageCount();
            inFlight++;
        }
        queue.dispatchedUpTo(nextStart);
        return new ConvertOutcome(placeholder, false, List.of());
    }

    /** 원문으로 대신한 쪽이 있으면 운영 확인용으로 쪽 수·사유 코드만 남긴다. 본문은 남기지 않는다. */
    private static void logConversionReport(Document placeholder, int totalPages) {
        ConversionReport report = placeholder.getConversionReport();
        if (report == null || report.fallbackPageCount() == 0) return;
        var restoration = report.restorationSummary();
        var figures = report.figureDescriptions();
        log.warn("[문서 변환 원문 대체] operation=document_convert stage=partial partial=true documentId={} workspaceId={} totalPages={} brokenPages={} repairedPages={} "
                        + "fallbackPages={} reasons={} figuresRequested={} figuresDescribed={} figuresFailed={}",
                placeholder.getId(), placeholder.getWorkspaceId(), totalPages, restoration.brokenPages(),
                restoration.repairedPages(), report.fallbackPageCount(), report.fallbackReasonCounts(),
                figures != null && figures.requested(), figures == null ? 0 : figures.described(),
                figures == null ? 0 : figures.failed());
    }

    private JsonNode readCommand(String command) {
        try {
            return objectMapper.readTree(command);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 변환 command를 읽을 수 없습니다.", e);
        }
    }

    /**
     * 변환을 실패로 닫는다. 문구는 AI가 준 오류 코드로만 만들고 예외 원문은 싣지 않는다.
     * 결과를 기다리는 나머지 batch는 호출자가 커밋 뒤에 취소한다.
     */
    private ConvertOutcome failConvert(DocumentConvertQueue queue, Document placeholder, String code, int failedStartPage) {
        var toCancel = convertBatchStore.findDispatched(queue.getId()).stream()
                .filter(batch -> batch.startPage() != failedStartPage).toList();
        if (placeholder.getPipelineRunId() == null || placeholder.getPipelineRunId().startsWith("convert:")) {
            placeholder.markProcessingFailed(failureCode(code), AiErrorMessages.convert(code), Instant.now());
        }
        placeholder.finishAutoIngestSchedule();
        // 실패 상태로 닫힌 작업의 변경은 DB journal guard가 거절한다. 지연된 JPA 문서 UPDATE와
        // queue 삭제를 running 상태에서 먼저 flush하고, 같은 트랜잭션 안에서 실행과 예약을 닫는다.
        convertQueueRepository.delete(queue);
        documentRepository.flush();
        taskWriter.failAndSettle("convert:" + placeholder.getId());
        if (analytics != null) analytics.converted(placeholder, queue.getSourceDocumentId(), 0, code == null ? "internal_error" : code);
        CommittedLog.failure(log, code, "[문서 변환 실패 확정] operation=document_convert stage=result errorCode={} upstreamCode={} retryable=false documentId={} workspaceId={} userId={} startPage={} 원인={}",
                CommittedLog.stableCode(code), CommittedLog.code(code), placeholder.getId(), placeholder.getWorkspaceId(), placeholder.getUserId(), failedStartPage, CommittedLog.reason(code));
        return new ConvertOutcome(placeholder, false, toCancel);
    }

    /** 변환 문서를 AI 큐로 보낸다. 이전 버전에서 생성한 파트도 호환 처리한다. */
    private void ingestConvertedParts(Document placeholder) {
        // 설정은 변환 요청 때 정해 문서에 남긴 값을 따른다. 권한은 변환을 요청한 사용자 기준으로 끝나는 시점에 다시 보고
        // (트랜잭션 밖), 도중에 회수됐으면 편입하지 않는다. 값을 남기기 전에 요청된 변환(null)은 지금 설정으로 판정한다.
        // 가져온 PDF의 변환본(import_id 있음)은 자동 편입하지 않는다. 변환 결과는 그대로 저장한다.
        Boolean scheduled = placeholder.getAutoIngestScheduled();
        boolean autoIngest = placeholder.getImportId() == null && (scheduled == null
                ? autoIngestAllowed(placeholder.getWorkspaceId(), placeholder.getUserId(), AutoIngestPath.PDF_CONVERT)
                : scheduled && ingestPermitted(placeholder.getWorkspaceId(), placeholder.getUserId()));
        // 모델 설정(access) 조회도 문서 행 잠금·트랜잭션 밖에서 한 번 한다. 실패는 편입 등록 자리에서 던진다.
        Prefetched<WorkspaceAiModelClient.AiModelSelection> aiModel = autoIngest
                ? Prefetched.of(() -> workspaceAiModelClient.get(placeholder.getWorkspaceId())) : null;
        java.util.List<Document> parts = new java.util.ArrayList<>();
        parts.add(documentRepository.findById(placeholder.getId()).orElseThrow());
        parts.addAll(documentRepository.findConvertedParts(placeholder.getWorkspaceId(), placeholder.getId()));
        for (Document part : parts) {
            try {
                transactionTemplate.execute(status -> {
                    if (!taskWriter.join("convert:" + placeholder.getId())) return null;
                    Document doc = documentRepository.findByIdAndWorkspaceIdForUpdate(part.getId(), part.getWorkspaceId())
                            .filter(value -> value.getDeletedAt() == null).orElse(null);
                    if (doc == null) return null;
                    var content = postgresDocumentEditStore.findState(doc.getId()).orElseThrow();
                    recordContentVersion(doc.getId(), content.getRevision(), content.getMarkdown(),
                            content.getContentHash(), doc.getUserId(), Instant.now());
                    // 편입을 등록하든 건너뛰든 여기서 예정이 끝난다. 화면은 이 값이 false가 되면 편입 여부를 묻는다.
                    doc.finishAutoIngestSchedule();
                    if (autoIngest && (doc.getPipelineRunId() == null || doc.getPipelineRunId().startsWith("convert:"))) {
                        doc.reopenForReingest(content.getContentHash(), content.getMarkdown().getBytes(StandardCharsets.UTF_8).length);
                        enqueueIngest(doc, doc.getUserId(), "auto", aiModel);
                    }
                    return null;
                });
            } catch (fruition.core.usage.service.CreditService.InsufficientCreditException e) {
                // 변환은 성공했고 다시 시도하지 않는다. 자동 ingest만 크레딧 부족으로 시작하지 못했음을 문서에 남긴다.
                markAutoIngestRejected(part, "INSUFFICIENT_CREDIT");
            } catch (fruition.core.usage.limit.AiUsageLimitExceededException e) {
                // 사용자별 AI 사용 한도도 같은 방식으로 남긴다. 예외를 올리면 변환 결과 이벤트가 다시 처리된다.
                markAutoIngestRejected(part, "AI_USAGE_LIMIT_EXCEEDED");
            } catch (RuntimeException e) {
                // 편입 등록이 실패해 트랜잭션이 롤백돼도 변환은 이미 끝났고 이 편입은 다시 시도되지 않는다.
                // 예정 표시만 남으면 화면이 편입 여부를 영영 묻지 않으므로 따로 지운다.
                finishAutoIngestSchedule(part);
                throw e;
            }
        }
    }

    /** converter worker가 쓴 batch 결과(Markdown, 이미지는 data: URI)를 읽는다. */
    private String readConvertResult(String resultKey) {
        try (InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(storageProps.getBucket()).object(resultKey).build())) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (io.minio.errors.ErrorResponseException e) {
            // 결과 object가 없거나 접근할 수 없으면 같은 이벤트를 다시 받아도 같다.
            throw new DocumentConvertException("변환 결과를 읽지 못했습니다: " + resultKey, e);
        } catch (Exception e) {
            // 일시적인 저장소 장애는 이벤트를 다시 받아 재시도한다.
            throw new IllegalStateException("변환 결과를 읽는 중 오류가 발생했습니다: " + resultKey, e);
        }
    }

    /**
     * 사용자가 placeholder를 삭제하면 변환 run을 닫고 결과를 기다리는 batch를 취소한다. 삭제 뒤 도착한 이벤트는 무시되므로
     * 취소하지 않으면 쓰이지 않을 모델 비용만 계속 든다.
     */
    private void cancelConvertOnDelete(Document placeholder) {
        var batches = convertBatchStore.findDispatchedByDocument(placeholder.getId());
        taskWriter.fail("convert:" + placeholder.getId());
        convertQueueRepository.findByDocumentId(placeholder.getId()).ifPresent(convertQueueRepository::delete);
        if (batches.isEmpty()) return;
        Runnable cancel = () -> batches.forEach(batch -> {
            try {
                pipelineCancellation.cancel(ConvertCommandOutbox.batchRunId(placeholder.getId(), batch.startPage()),
                        placeholder.getWorkspaceId(), placeholder.getUserId(), readCommand(batch.command()));
            } catch (RuntimeException e) {
                log.warn("[문서 변환 batch 취소 실패] operation=document_convert stage=cancel_batch retryable=false documentId={} startPage={} errorType={} 원인=남은 변환 조각의 취소 요청을 전달하지 못했습니다. 변환은 이미 닫혔습니다.",
                        placeholder.getId(), batch.startPage(), e.getClass().getSimpleName());
            }
        });
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cancel.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { cancel.run(); }
        });
    }

    /** 변환 뒤 자동 ingest가 크레딧 부족·AI 사용 한도로 거절됐을 때 문서에 그 코드로 기록한다. 변환 결과는 그대로 둔다. */
    private void markAutoIngestRejected(Document part, String code) {
        Instant now = Instant.now();
        transactionTemplate.execute(status -> {
            documentRepository.findByIdAndWorkspaceIdForUpdate(part.getId(), part.getWorkspaceId())
                    .filter(value -> value.getDeletedAt() == null)
                    .ifPresent(doc -> {
                        doc.finishAutoIngestSchedule();
                        doc.markProcessingFailed(code, AiErrorMessages.query(code), now);
                    });
            return null;
        });
        log.warn("[변환 뒤 위키 편입 거절] operation=wiki_ingest stage=auto_rejected errorCode={} documentId={} 원인={}", code, part.getId(), CommittedLog.reason(code));
    }

    /**
     * batch 하나를 동일한 문서에 이어 붙이고 체크포인트를 옮긴다.
     * 호출자의 트랜잭션·queue 행 잠금·run join 안에서 쓴다.
     */
    private void mergeConvertedBatch(DocumentConvertQueue queue, Document parent, String batchMarkdown,
                                     int pageStart, int pageEnd, int totalPages) {
        if (queue.getCompletedPages() >= pageEnd) return;
        // data: 이미지를 내부 주소로 바꾼 뒤에 걸러야 '원본 이미지 보기' 링크가 남는다.
        String markdown = ConvertedMarkdownChunks.renumberPages(AiMarkdownSanitizer.sanitizeConverted(
                externalizeConvertedImages(parent.getWorkspaceId(), parent.getUserId(), batchMarkdown)), pageStart);
        // 변환 batch는 내부 처리 단위일 뿐이다. 크기와 관계없이 원본 PDF의 모든 쪽을 같은 문서에 저장한다.
        // JDBC 저장 직후에도 최신 본문을 읽도록 JPA 1차 캐시를 거치지 않는다.
        var state = postgresDocumentEditStore.findState(parent.getId()).orElseThrow();
        String existing = CONVERT_PLACEHOLDER_MARKDOWN.equals(state.getMarkdown()) ? "" : state.getMarkdown();
        String merged = existing.isEmpty() ? markdown : existing.stripTrailing() + "\n\n" + markdown;
        var content = DocumentEditingRules.storedMarkdown(merged);
        postgresDocumentEditStore.save(parent.getWorkspaceId(), parent.getId(), content.markdown(),
                content.contentHash(), state.getRevision(), "convert:" + queue.getId() + ":" + pageEnd + ":0",
                parent.getUserId(), null);
        storeMarkdownSource(parent.getId(), content.bytes());
        assetReferenceSynchronizer.synchronize(parent.getId(), parent.getWorkspaceId(),
                assetReferenceParser.parse(content.markdown()));
        queue.checkpoint(pageEnd, totalPages);
        documentRepository.findById(parent.getId()).ifPresent(doc -> doc.markProcessingHeartbeat(
                "PDF " + pageEnd + "/" + totalPages + "페이지 변환 완료", Instant.now()));
    }

    /** 변환 결과의 data: 이미지를 asset으로 저장하고 내부 주소로 바꾼다. */
    private String externalizeConvertedImages(String workspaceId, String userId, String markdown) {
        var pattern = java.util.regex.Pattern.compile("data:(image/(?:png|jpeg|gif));base64,([A-Za-z0-9+/=]+)");
        var matcher = pattern.matcher(markdown);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            byte[] bytes = java.util.Base64.getDecoder().decode(matcher.group(2));
            if (bytes.length > DocumentAssetValidator.MAX_FILE_BYTES) {
                matcher.appendReplacement(output, OVERSIZED_CONVERTED_IMAGE);
                continue;
            }
            String hash = sha256(bytes);
            UUID id = UUID.nameUUIDFromBytes((workspaceId + ":" + hash).getBytes(StandardCharsets.UTF_8));
            String key = "assets/" + workspaceId + "/" + id + "/content";
            if (!assetRepository.existsById(id)) {
                try (var image = javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                    var readers = javax.imageio.ImageIO.getImageReaders(image);
                    if (!readers.hasNext()) throw new DocumentConvertException("변환 이미지 형식을 읽을 수 없습니다.");
                    var reader = readers.next();
                    int width, height;
                    try { reader.setInput(image); width = reader.getWidth(0); height = reader.getHeight(0); }
                    finally { reader.dispose(); }
                    if (width > DocumentAssetValidator.MAX_DIMENSION || height > DocumentAssetValidator.MAX_DIMENSION) {
                        matcher.appendReplacement(output, OVERSIZED_CONVERTED_IMAGE);
                        continue;
                    }
                    RetryingObjectPut.put(minioClient, storageProps.getBucket(), key, bytes, matcher.group(1));
                    registerMinioRollbackCleanup(key);
                    assetRepository.save(new fruition.core.document.domain.DocumentAsset(id, workspaceId,
                            userId, "converted-" + id, matcher.group(1), bytes.length, width, height, hash, key, Instant.now()));
                } catch (Exception e) { throw new DocumentConvertException("변환 이미지 저장 실패", e); }
            }
            matcher.appendReplacement(output, java.util.regex.Matcher.quoteReplacement(
                    "/api/workspaces/" + workspaceId + "/assets/" + id + "/content"));
        }
        matcher.appendTail(output);
        // 생략한 이미지는 이미지·링크 문법째 문구로 바꾸고, 문법 밖에 남은 표시는 지운다.
        return output.toString()
                .replaceAll("!?\\[[^\\]\\n]*\\]\\(" + OVERSIZED_CONVERTED_IMAGE + "\\)", "(이미지가 너무 커서 생략됨)")
                .replace(OVERSIZED_CONVERTED_IMAGE, "");
    }

    /** 자동 편입 경로. 경로마다 워크스페이스 설정이 따로 있다. */
    private enum AutoIngestPath { MARKDOWN_UPLOAD, PDF_CONVERT }

    /**
     * 업로드·변환 뒤 자동 편입 여부. 경로별 워크스페이스 설정(Markdown 업로드: auto_ingest_markdown_on_upload,
     * PDF 변환: auto_ingest_pdf_on_convert)이 켜져 있고 요청자에게 wiki.ingest 권한이 있을 때만 true다.
     * 판정에 실패하면 업로드·변환을 막지 않고 편입하지 않는다.
     */
    private boolean autoIngestAllowed(String workspaceId, String requesterId, AutoIngestPath path) {
        try {
            boolean enabled = path == AutoIngestPath.MARKDOWN_UPLOAD
                    ? workspaceSettingsClient.autoIngestMarkdownOnUpload(workspaceId)
                    : workspaceSettingsClient.autoIngestPdfOnConvert(workspaceId);
            return enabled
                    && workspaceAccessGuard.hasPermission(workspaceId, requesterId, WorkspacePermissions.WIKI_INGEST);
        } catch (RuntimeException e) {
            log.warn("[자동 편입] 판정 실패 — 편입하지 않음 workspaceId={} userId={} path={} cause={}",
                    workspaceId, requesterId, path, e.toString());
            return false;
        }
    }

    /** 변환 요청 때 자동 편입하기로 정한 변환이 끝났을 때 요청자에게 아직 wiki.ingest가 있는지. 실패하면 편입하지 않는다. */
    private boolean ingestPermitted(String workspaceId, String requesterId) {
        try {
            return workspaceAccessGuard.hasPermission(workspaceId, requesterId, WorkspacePermissions.WIKI_INGEST);
        } catch (RuntimeException e) {
            log.warn("[자동 편입] 권한 판정 실패 — 편입하지 않음 workspaceId={} userId={} cause={}",
                    workspaceId, requesterId, e.toString());
            return false;
        }
    }

    /** 편입 등록이 실패한 변환본의 자동 편입 예정 표시를 지운다. 실패해도 원래 예외를 가리지 않는다. */
    private void finishAutoIngestSchedule(Document part) {
        try {
            transactionTemplate.execute(status -> {
                documentRepository.findByIdAndWorkspaceIdForUpdate(part.getId(), part.getWorkspaceId())
                        .ifPresent(Document::finishAutoIngestSchedule);
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("[자동 편입] 예정 표시 정리 실패 documentId={} cause={}", part.getId(), e.toString());
        }
    }

    /**
     * 업로드 커밋 뒤 자동 편입을 판정하고 별도 트랜잭션에서 등록한다. 설정·권한 HTTP 조회는 DB 트랜잭션 밖이고,
     * 등록이 실패하면 그 트랜잭션만 롤백돼 문서는 uploaded(신규)로 남는다. 업로드 원본이 곧 본문이라 원본은 다시 쓰지 않는다.
     */
    private void autoIngestAfterCommit(String workspaceId, String userId, String documentId,
                                       String contentHash, long byteSize) {
        Runnable autoIngest = () -> {
            try {
                if (!autoIngestAllowed(workspaceId, userId, AutoIngestPath.MARKDOWN_UPLOAD)) return;
                // 모델 설정(access) 조회도 트랜잭션 밖에서 한다. 실패는 편입 등록 자리에서 던진다.
                Prefetched<WorkspaceAiModelClient.AiModelSelection> aiModel =
                        Prefetched.of(() -> workspaceAiModelClient.get(workspaceId));
                requiresNewSaveTransactionTemplate().execute(status -> {
                    documentRepository.findByIdInActiveWorkspace(documentId)
                            .filter(doc -> doc.getStatus() == DocumentStatus.uploaded)
                            .ifPresent(doc -> {
                                doc.reopenForReingest(contentHash, byteSize);
                                enqueueIngest(doc, userId, "auto", aiModel);
                            });
                    return null;
                });
            } catch (RuntimeException e) {
                log.warn("[자동 편입] 업로드 직후 편입 등록 실패 — 문서는 신규로 남음 documentId={} cause={}",
                        documentId, e.toString());
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            autoIngest.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                autoIngest.run();
            }
        });
    }

    String enqueueIngest(Document document) {
        return enqueueIngest(document, document.getUserId(), "auto");
    }

    private String enqueueIngest(Document document, String requesterId, String trigger) {
        return enqueueIngest(document, requesterId, trigger,
                Prefetched.of(() -> workspaceAiModelClient.get(document.getWorkspaceId())));
    }

    /**
     * requesterId는 ingest를 요청한 사용자다. 공동 편집에서는 소유자와 다를 수 있다.
     * aiModel은 트랜잭션 밖에서 미리 조회한 모델 설정이다(수동 편입). 자동 편입은 여기서 바로 조회한다.
     */
    private String enqueueIngest(Document document, String requesterId, String trigger,
                                 Prefetched<WorkspaceAiModelClient.AiModelSelection> prefetchedModel) {
        String runId = UUID.randomUUID().toString();
        // access HTTP 조회는 크레딧 예약(계정 행 잠금) 전에 한다. 잠금을 쥔 채 기다리면 같은 사용자의 AI 요청이 줄을 선다.
        var aiModel = prefetchedModel.get();
        ingestCommandOutbox.begin(runId, document.getWorkspaceId(), requesterId);
        String documentId = document.getId();
        Instant startedAt = Instant.now();
        document.markPipelineStarted(runId, startedAt);
        String operationId = ingestOperationStarter.start(
                document.getWorkspaceId(), requesterId, documentId,
                document.getDisplayName(), startedAt);
        ingestCommandOutbox.enqueue(
                runId,
                documentId,
                requesterId,
                document.getWorkspaceId(),
                document.getSelectionMode(),
                document.getPipelineInputMarkdown(),
                document.getPipelineInputBlocks(),
                false,
                operationId,
                document.getCurrentVersion(),
                document.getCurrentContentHash(),
                aiModel
        );
        if (analytics != null) analytics.ingestRequested(document, runId, operationId, requesterId, startedAt, trigger);
        CommittedLog.info(log, "[위키 편입 요청 접수] operation=wiki_ingest stage=accepted documentId={} runId={} operationId={} workspaceId={} userId={} trigger={}",
                documentId, runId, operationId, document.getWorkspaceId(), requesterId, trigger);
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
        if ("indexing".equals(status)) {
            document.markProcessingHeartbeat("위키 검색을 준비하고 있어요", now);
            return;
        }
        if ("succeeded".equals(status)) {
            document.updateStatus(DocumentStatus.completed, null, now, null);
            CommittedLog.info(log, "[위키 편입 완료] operation=wiki_ingest stage=completed documentId={} runId={} workspaceId={}",
                    documentId, runId, document.getWorkspaceId());
        } else if ("failed".equals(status)) {
            document.markProcessingFailed(failureCode(error), AiErrorMessages.ingest(error), now);
            CommittedLog.failure(log, error, "[위키 편입 실패 확정] operation=wiki_ingest stage=result errorCode={} upstreamCode={} retryable=false documentId={} runId={} workspaceId={} 원인={}",
                    CommittedLog.stableCode(error), CommittedLog.code(error), documentId, runId, document.getWorkspaceId(), CommittedLog.reason(error));
        }
    }

    /** 저장하는 실패에는 코드가 빠지지 않게 한다. AI가 코드를 주지 않은 실패는 기본 문구와 같은 internal_error다. */
    private static String failureCode(String code) {
        return code == null || code.isBlank() ? "internal_error" : code;
    }

    private String contentHashPrefix(String contentHash) {
        if (contentHash == null) return null;
        return contentHash.substring(0, Math.min(contentHash.length(), 16));
    }

    public DocumentListResponse findAll(String workspaceId, String userId, String query) {
        return findAll(workspaceId, userId, query, null);
    }

    /** origin=skill_reference면 스킬 참고 문서만 최근 업로드 순으로 돌려준다. 이때 query는 쓰지 않는다. */
    public DocumentListResponse findAll(String workspaceId, String userId, String query, String origin) {
        verifyWorkspaceOwnership(workspaceId, userId);

        List<Document> documents = isSkillReferenceOrigin(origin)
                ? documentRepository.findSkillReferences(workspaceId)
                : query == null || query.isBlank()
                ? documentRepository.findVisibleByWorkspaceId(workspaceId)
                : documentRepository.searchVisibleByWorkspaceId(workspaceId, query.trim());

        return new DocumentListResponse(documentItemAssembler.assemble(workspaceId, userId, documents));
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

    /**
     * DB 작업은 짧은 트랜잭션으로 끝내고 AI 위키 연결 조회는 트랜잭션 밖에서 한다. 연결을 쥔 채 AI 응답을 기다리면
     * AI가 느릴 때 연결 풀이 바닥나 AI와 무관한 API까지 멈춘다.
     */
    /**
     * 이 사용자가 읽을 수 있고 편집은 못 하는 이 워크스페이스의 살아 있는 문서인지. 클라이언트가 보낸 ID를 믿지 않으려는 확인이라
     * 없는 문서·형식 오류도 예외 없이 false다. 멤버인지는 호출하는 쪽이 먼저 확인한다.
     * 형식 검사는 조회 전에 한다. NUL 문자는 PostgreSQL이 거절해 500이 된다.
     */
    public boolean isReadOnlyFor(String workspaceId, String userId, String documentId) {
        if (documentId == null || !CLIENT_DOCUMENT_ID.matcher(documentId).matches()) {
            return false;
        }
        return documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .map(document -> {
                    DocumentAccessPolicy.Viewer viewer = documentAccessPolicy.viewer(workspaceId, userId);
                    return viewer.canRead(document) && !viewer.canEdit(document);
                })
                .orElse(false);
    }

    /**
     * 모두 이 사용자가 읽을 수 있는 이 워크스페이스의 살아 있는 Markdown 문서이고 실제 본문이 있는지.
     * 없는 문서·형식 오류도 예외 없이 false다. 멤버인지는 호출하는 쪽이 먼저 확인한다.
     */
    public boolean canUseAsAgentReferences(String workspaceId, String userId, List<String> documentIds) {
        DocumentAccessPolicy.Viewer viewer = documentAccessPolicy.viewer(workspaceId, userId);
        return documentIds.stream().allMatch(documentId -> CLIENT_DOCUMENT_ID.matcher(documentId).matches()
                && documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                        .filter(viewer::canRead)
                        .filter(document -> document.getDocumentRole() == DocumentRole.EDITABLE)
                        .filter(this::hasConvertedBody)
                        .isPresent());
    }

    /**
     * 그중 이 사용자가 지금 읽을 수 있는 이 워크스페이스의 살아 있는 문서의 현재 제목. 없는 문서·형식 오류는 결과에서 빠진다.
     * 멤버인지는 호출하는 쪽이 먼저 확인한다.
     */
    public Map<String, String> readableDocumentTitles(String workspaceId, String userId, List<String> documentIds) {
        DocumentAccessPolicy.Viewer viewer = documentAccessPolicy.viewer(workspaceId, userId);
        return documentIds.stream()
                .filter(documentId -> documentId != null && CLIENT_DOCUMENT_ID.matcher(documentId).matches())
                .distinct()
                .flatMap(documentId -> documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                        .filter(viewer::canRead)
                        .stream())
                .collect(java.util.stream.Collectors.toMap(Document::getId, Document::getDisplayName));
    }

    /** PDF 변환이 진행 중이거나 첫 batch 전에 실패한 문서의 본문은 안내 문구이거나 일부뿐이라 근거가 되지 못한다. */
    private boolean hasConvertedBody(Document document) {
        if (document.isConverting()) {
            return false;
        }
        return document.getStatus() != DocumentStatus.failed
                || postgresDocumentEditStore.findState(document.getId())
                        .map(state -> !document.isFailedConvertPlaceholder(state.getMarkdown()))
                        .orElse(true);
    }

    public DocumentDetailResponse findById(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        record Loaded(Document doc, Optional<DocumentEditState> editState) {}
        Loaded loaded = transactionTemplate.execute(status -> {
            Document found = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                    .orElseThrow(() -> new DocumentNotFoundException(documentId));
            editStateInitializer.initializeIfNeeded(found);
            return new Loaded(found, postgresDocumentEditStore.findState(documentId));
        });
        Document doc = loaded.doc();
        Optional<DocumentEditState> editState = loaded.editState();
        long editRevision = editState.map(DocumentEditState::getRevision).orElseGet(() -> {
            if (doc.getDocumentRole() == DocumentRole.EDITABLE) {
                throw new InvalidMarkdownContentException("현재 Markdown 편집 상태를 찾을 수 없습니다.");
            }
            return doc.getCurrentVersion();
        });

        DocumentAccessPolicy.Viewer viewer = documentAccessPolicy.viewer(workspaceId, userId);
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
                AiErrorMessages.localizeStored(doc.getErrorCode(), doc.getErrorMessage(), ResponseLanguage.current()),
                doc.getErrorCode(),
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
                editLockService.getStatus(doc.getId()),
                doc.getFolderId(),
                viewer.canEdit(doc),
                viewer.canDelete(doc),
                viewer.canConvert(doc),
                viewer.override(doc),
                doc.getUpdatedBy(),
                doc.getAiOrigin(),
                documentItemAssembler.lastAiChanges(List.of(documentId)).get(documentId),
                DocumentItemAssembler.restorationSummaryOf(doc),
                DocumentItemAssembler.figureDescriptionsOf(doc),
                doc.isAutoIngestScheduled()
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
        String versionSource = applyOperationId != null && !applyOperationId.isBlank() ? "agent" : "user";
        return saveContent(workspaceId, userId, documentId, markdown, baseRevision, revisionWriteId,
                versionSource, applyOperationId, false, null, StaleBasePolicy.MERGE_OR_OVERWRITE);
    }

    /**
     * 승인된 Agent 도구의 문서 편집 저장. 안 겹치는 변경은 병합하고, 겹치거나 기준 스냅샷이 없으면 덮어쓰지 않고 409다.
     * 사용자가 보고 있지 않은 문서라 ADR 0032의 last-writer-wins를 적용하지 않는다.
     */
    public DocumentContentSaveResponse saveContentMergeOnly(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            Long baseRevision,
            String revisionWriteId,
            String applyOperationId
    ) {
        return saveContent(workspaceId, userId, documentId, markdown, baseRevision, revisionWriteId,
                "agent", applyOperationId, false, null, StaleBasePolicy.MERGE_ONLY);
    }

    /** 사용자가 수락한 회의록을 기존 문서에 덧붙인다. 버전 이력에 meeting_notes로 남는다. */
    public DocumentContentSaveResponse saveMeetingNotesContent(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            Long baseRevision,
            String revisionWriteId
    ) {
        return saveContent(workspaceId, userId, documentId, markdown, baseRevision, revisionWriteId,
                "meeting_notes", null, false, null, StaleBasePolicy.MERGE_OR_OVERWRITE);
    }

    private DocumentContentSaveResponse saveContent(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            Long baseRevision,
            String revisionWriteId,
            String versionSource,
            String applyOperationId,
            boolean applyOperationClaimed,
            Long restoredFromVersion,
            StaleBasePolicy staleBase
    ) {
        TransactionTemplate saveTransaction = requiresNewSaveTransactionTemplate();
        try {
            return executeWithSaveRetry(() -> saveTransaction.execute(status -> saveContentInTransaction(
                    workspaceId, userId, documentId, markdown, baseRevision, revisionWriteId,
                    versionSource, applyOperationId, applyOperationClaimed, restoredFromVersion, staleBase)));
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
     *
     * @param versionSource 버전 이력에 남길 저장 경로(user | restore 등). 서버 내부 호출자가 정한다.
     */
    public DocumentContentSaveResponse saveContentInCurrentTransaction(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            Long baseRevision,
            String revisionWriteId,
            String versionSource
    ) {
        return saveContentInCurrentTransaction(
                workspaceId, userId, documentId, markdown, baseRevision, revisionWriteId, versionSource, false);
    }

    /** MCP Agent edit plus its I5 receipt are committed by one caller-owned PostgreSQL transaction. */
    public DocumentContentSaveResponse saveAgentContentInCurrentTransaction(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            Long baseRevision,
            String revisionWriteId,
            String applyOperationId
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("현재 PostgreSQL transaction 안에서 호출해야 합니다.");
        }
        return saveContentInTransaction(workspaceId, userId, documentId, markdown, baseRevision, revisionWriteId,
                "agent", applyOperationId, false, null, StaleBasePolicy.MERGE_ONLY);
    }

    /**
     * @param autoMerge true면 기준 revision이 지나간 저장을 자동 병합한다(ADR 0032). 버전 복원처럼 정확히 그 본문이어야
     *                  하는 저장은 false로 두어 기존처럼 409로 거절한다.
     */
    public DocumentContentSaveResponse saveContentInCurrentTransaction(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            Long baseRevision,
            String revisionWriteId,
            String versionSource,
            boolean autoMerge
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("현재 PostgreSQL transaction 안에서 호출해야 합니다.");
        }
        return saveContentInTransaction(
                workspaceId, userId, documentId, markdown, baseRevision, revisionWriteId,
                versionSource, null, false, null,
                autoMerge ? StaleBasePolicy.MERGE_OR_OVERWRITE : StaleBasePolicy.REJECT);
    }

    /** 기준 revision이 지나간 저장을 어떻게 처리할지. */
    private enum StaleBasePolicy {
        /** 병합하지 않고 409. 버전 복원처럼 정확히 그 본문이어야 하는 저장. */
        REJECT,
        /** 병합하고, 겹치면 요청 본으로 덮어쓴다(ADR 0032). */
        MERGE_OR_OVERWRITE,
        /** 병합하고, 겹치면 409. */
        MERGE_ONLY
    }

    private DocumentContentSaveResponse saveContentInTransaction(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            Long baseRevision,
            String revisionWriteId,
            String versionSource,
            String applyOperationId,
            boolean applyOperationClaimed,
            Long restoredFromVersion,
            StaleBasePolicy staleBase
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
        documentAccessPolicy.requireEdit(document, userId);
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new InvalidMarkdownContentException("편집 가능한 Markdown 문서만 저장할 수 있습니다.");
        }
        requireNotChatExport(document, "채팅 Wiki page화 문서는 편집할 수 없습니다.");
        requireNotConverting(document);
        editLockService.requireWritable(documentId, userId);

        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.storedMarkdown(markdown);
        boolean hasApplyOperation = applyOperationId != null && !applyOperationId.isBlank();
        if (!applyOperationClaimed) {
            claimApplyOperation(workspaceId, userId, documentId, revisionWriteId,
                    baseRevision, content.markdown(), applyOperationId);
        }
        editStateInitializer.initializeIfNeeded(document);
        // 기준 버전이 지나간 저장은 409 대신 서버에서 자동 병합한다(ADR 0032). 버전 복원·충돌 해결처럼
        // 정확히 그 본문이어야 하는 내부 저장은 병합하지 않고 기존처럼 충돌로 거절한다.
        ContentSaveMerge merge = switch (staleBase) {
            case REJECT -> null;
            case MERGE_OR_OVERWRITE ->
                    new ContentSaveMerge(contentVersionRepository, documentId, baseRevision, content.markdown());
            case MERGE_ONLY ->
                    ContentSaveMerge.mergeOnly(contentVersionRepository, documentId, baseRevision, content.markdown());
        };
        PostgresDocumentEditSaveResult result = postgresDocumentEditStore.save(
                workspaceId, documentId, content.markdown(), content.contentHash(), baseRevision,
                revisionWriteId, userId, hasApplyOperation ? applyOperationId : null, merge);
        if (result.replayed()) {
            return replayedSave(documentId, baseRevision, content, result);
        }
        String savedMarkdown = merge != null ? merge.savedMarkdown() : content.markdown();
        DocumentContentSaveResponse.Merge mergeOutcome =
                merge != null ? merge.outcome() : DocumentContentSaveResponse.Merge.NONE;
        if (hasApplyOperation) {
            transactionTemplate.execute(status -> {
                projectContentVersions(documentId, savedMarkdown, result, lastAuthor(document));
                if (result.changed()) {
                    int linked = contentVersionRepository.linkOperation(documentId, result.revision(), applyOperationId);
                    if (linked == 1) {
                        operationRecorder.recordDocumentEdit(applyOperationId, workspaceId, userId, documentId,
                                document.getDisplayName(),
                                result.baseRevision(), result.revision(), result.baseMarkdown(),
                                savedMarkdown, result.updatedAt());
                        CommittedLog.info(log, "[AI 편집 문서 반영 완료] operation=agent_apply stage=document_saved applyOperationId={} documentId={} workspaceId={} userId={} fromVersion={} newVersion={}",
                                applyOperationId, documentId, workspaceId, userId, result.baseRevision(), result.revision());
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
                    CommittedLog.info(log, "[AI 편집 문서 반영 생략] operation=agent_apply stage=no_change applyOperationId={} documentId={} workspaceId={} userId={} 설명=편집안이 현재 본문과 같아 새 버전을 만들지 않았습니다.",
                            applyOperationId, documentId, workspaceId, userId);
                }
                return null;
            });
        } else {
            projectContentVersions(documentId, savedMarkdown, result, lastAuthor(document));
        }
        if (result.changed()) {
            if (restoredFromVersion != null) {
                contentVersionRepository.markRestoredFrom(documentId, result.revision(), restoredFromVersion);
                // 같은 쓰기 ID의 재요청은 앞에서 돌아가므로 여기에는 처음 복원만 온다.
                CommittedLog.info(log, "[문서 버전 복원 완료] operation=document_version_restore stage=completed documentId={} workspaceId={} userId={} fromVersion={} toVersion={} newVersion={}",
                        documentId, workspaceId, userId, result.baseRevision(), restoredFromVersion, result.revision());
            }
            if (versionSource != null) {
                contentVersionRepository.markSource(documentId, result.revision(), versionSource);
            }
            if (mergeOutcome.applied()) {
                contentVersionRepository.markMergeStatus(documentId, result.revision(), mergeOutcome.status());
            }
            // 재ingest 필요 판단용 projection: 목록 API가 PG만으로 현재 편집본 해시를 비교할 수 있게 한다.
            documentRepository.updateCurrentContentHash(documentId, result.contentHash(), result.updatedAt(), userId);
            // 이미지를 첨부하지 않는 저장에서도 본문에 남은 관리 이미지를 기준으로 참조를 맞춘다.
            // 그러지 않으면 본문에서 지운 이미지가 참조된 상태로 남아 정리 대상이 되지 않는다.
            assetReferenceSynchronizer.synchronize(
                    documentId, workspaceId, assetReferenceParser.parse(savedMarkdown));
        }

        DocumentContentSaveResponse response = new DocumentContentSaveResponse(
                documentId,
                result.revision(),
                result.contentHash(),
                result.updatedAt(),
                result.changed()
        );
        return mergeOutcome.applied() ? response.withMerge(savedMarkdown, mergeOutcome) : response;
    }

    /**
     * 같은 revision_write_id 재전송. 처음 저장이 자동 병합으로 요청과 다른 본문을 남겼으면(해시가 다르면)
     * 그 버전 스냅샷을 merged로 돌려줘 클라이언트가 실제 저장본으로 맞추게 한다.
     */
    private DocumentContentSaveResponse replayedSave(String documentId, Long baseRevision,
                                                     DocumentEditingRules.MarkdownContent content,
                                                     PostgresDocumentEditSaveResult result) {
        DocumentContentSaveResponse response = new DocumentContentSaveResponse(
                documentId, result.revision(), result.contentHash(), result.updatedAt(), result.changed());
        if (Objects.equals(result.contentHash(), content.contentHash())) {
            return response;
        }
        return contentVersionRepository.findById(new DocumentContentVersionId(documentId, result.revision()))
                .map(saved -> response.withMerge(saved.getMarkdown(), new DocumentContentSaveResponse.Merge(
                        DocumentContentSaveResponse.Merge.STATUS_MERGED, baseRevision,
                        result.changed() ? result.revision() - 1 : result.revision(), null)))
                .orElse(response);
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
     *
     * <p>저장 전 버전과 저장 결과를 버전 이력에 남긴다. 저장 전 버전은 대개 이전 저장 때 이미 들어가 있고, 없을 때만 넣는다.
     * 그 작성자는 이번에 저장한 사람이 아니라 직전 수정자(없으면 소유자)다. 공동 편집에서 둘이 다르다.
     */
    private void projectContentVersions(
            String documentId,
            String resultMarkdown,
            PostgresDocumentEditSaveResult result,
            String baseAuthor
    ) {
        if (!result.changed()) {
            return;
        }
        recordContentVersion(
                documentId,
                result.baseRevision(),
                result.baseMarkdown(),
                result.baseContentHash(),
                baseAuthor,
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
        documentAccessPolicy.requireEdit(document, userId);
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new InvalidMarkdownContentException("편집 가능한 Markdown 문서만 저장할 수 있습니다.");
        }
        requireNotChatExport(document, "채팅 Wiki page화 문서는 편집할 수 없습니다.");
        requireNotConverting(document);
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
        return saveContentWithAssets(workspaceId, userId, documentId, markdown, baseVersion, revisionWriteId,
                storedAssets, applyOperationId, null);
    }

    /**
     * @param importSession 가져오기 세션 쓰기면 그 세션. 새 이미지를 세션 항목으로 세고, 저장 용량은 그대로 본다.
     */
    public DocumentContentSaveResponse saveContentWithAssets(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            long baseVersion,
            String revisionWriteId,
            Map<UUID, DocumentAssetStorageCoordinator.StoredAsset> storedAssets,
            String applyOperationId,
            fruition.core.imports.ImportSessionStore.OpenSession importSession
    ) {
        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.storedMarkdown(markdown);
        Instant updatedAt = Instant.now();
        List<DocumentAsset> assets = storedAssets.values().stream()
                // 이미 커밋된 asset 행은 건드리지 않는다. 다시 저장하면 이 요청의 메타데이터로 덮이고,
                // 본문 무변경이면 아래 deleteAllInBatch가 다른 문서가 참조하는 행까지 지우려 한다.
                .filter(stored -> !stored.preexisting())
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
                // 같은 요청이 겹쳐도 먼저 커밋된 asset의 storage_key를 merge로 덮지 않는다.
                // 이 트랜잭션이 새로 만든 행만 무변경 응답 때 정리한다.
                List<DocumentAsset> insertedAssets = new java.util.ArrayList<>();
                for (DocumentAsset asset : assets) {
                    int inserted = assetRepository.insertIfAbsent(asset);
                    if (inserted == 1) insertedAssets.add(asset);
                }
                // 새로 들어간 행이 있을 때만 용량을 다시 본다(먼저 커밋된 재전송 행은 이미 확인했다). 행을 이미 썼으므로 추가분은 0이다.
                if (importSession != null && !insertedAssets.isEmpty()) {
                    importSessions.countItems(importSession, insertedAssets.size());
                }
                if (uploadLimits != null && !insertedAssets.isEmpty()) {
                    uploadLimits.enforce(userId, 0);
                }
                DocumentContentSaveResponse value = saveContentInTransaction(
                        workspaceId, userId, documentId, content.markdown(),
                        baseVersion, revisionWriteId, null, applyOperationId, false, null,
                        StaleBasePolicy.MERGE_OR_OVERWRITE);
                if (!value.changed()) {
                    // 본문이 그대로면 새 asset row도 남기지 않는다. object storage 정리는 호출부가 한다.
                    assetRepository.deleteAllInBatch(insertedAssets);
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
                saved.changed(), saved.markdown() != null ? saved.markdown() : content.markdown(), List.of(),
                saved.merge());
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
            DocumentEditingRules.MarkdownContent content = DocumentEditingRules.storedMarkdown(markdown);
            if (!applyOperationStore.consume(
                    applyOperationId, userId, documentId, revisionWriteId,
                    baseRevision, content.markdown())) {
                throw new InvalidAgentTurnRequestException(
                        "유효하지 않거나 이미 사용된 Agent 적용 표입니다.");
            }
            operationRecorder.recordConflict(
                    applyOperationId, workspaceId, userId, documentId, Instant.now());
            CommittedLog.warn(log, "[AI 편집 문서 반영 충돌] operation=agent_apply stage=conflict errorCode=AGENT_APPLY_CONFLICT retryable=false applyOperationId={} documentId={} workspaceId={} userId={} baseRevision={} 원인=편집안의 기준 버전 이후 문서가 바뀌어 자동으로 반영하지 못했습니다.",
                    applyOperationId, documentId, workspaceId, userId, baseRevision);
            if (analytics != null) analytics.applyConflicted(applyOperationId);
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
                        s.getVersion(), s.getContentHash(), s.getCreatedBy(), s.getCreatedAt(),
                        s.getRestoredFromVersion(), s.getSource(), s.getOperationId(), s.getMergeStatus()))
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
                "restore",
                null,
                false,
                version,
                StaleBasePolicy.REJECT
        );
    }

    private Document loadEditableForVersion(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        // 버전 조회는 읽기다. 복원은 본문 저장 경로에서 편집 권한을 다시 확인한다.
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new InvalidMarkdownContentException("편집 가능한 Markdown 문서만 버전 이력을 제공합니다.");
        }
        return document;
    }

    /**
     * 편집 가능 Markdown 문서를 최신 편집본으로 재ingest한다. DB 편집본을 MinIO 원본으로 승격(덮어쓰기)한 뒤
     * 파이프라인 처리 큐에 재등록해, 업로드 당시가 아닌 편집한 내용으로 Wiki가 만들어지게 한다.
     */
    public DocumentIngestResponse ingest(String workspaceId, String userId, String documentId) {
        // 권한 확인과 모델 설정 조회(access)는 트랜잭션·문서 행 잠금 전에 한다. 권한 확인은 원래도 가장 먼저였고,
        // 모델 설정 조회의 실패는 원래 호출하던 자리(검증 뒤)에서 던진다(Prefetched).
        verifyWorkspaceOwnership(workspaceId, userId);
        workspaceAccessGuard.requirePermission(workspaceId, userId, WorkspacePermissions.WIKI_INGEST);
        Prefetched<WorkspaceAiModelClient.AiModelSelection> aiModel =
                Prefetched.of(() -> workspaceAiModelClient.get(workspaceId));
        return transactionTemplate.execute(status -> ingestInTransaction(workspaceId, userId, documentId, aiModel));
    }

    private DocumentIngestResponse ingestInTransaction(String workspaceId, String userId, String documentId,
                                                       Prefetched<WorkspaceAiModelClient.AiModelSelection> aiModel) {
        Document document = documentRepository.findByIdAndWorkspaceIdForUpdate(documentId, workspaceId)
                .filter(value -> value.getDeletedAt() == null)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        documentAccessPolicy.requireEdit(document, userId);
        if (document.isSkillReference()) {
            throw new InvalidMarkdownContentException("스킬 참고 문서는 위키에 편입할 수 없습니다.");
        }
        if ("chat_export".equals(document.getOrigin())) {
            if (document.getStatus() == DocumentStatus.processing) {
                throw new DocumentAlreadyProcessingException("이미 처리 중인 문서입니다.");
            }
            // 저장한 문답과 provenance를 그대로 사용한다. 편집본 승격은 필요하지 않다.
            document.updateStatus(DocumentStatus.processing, document.getExtractedTextUri(), null, null);
            document.markReconciled(null);
            String runId = enqueueIngest(document, userId, "manual", aiModel);
            return new DocumentIngestResponse(documentId, runId, document.getStatus());
        }
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new InvalidMarkdownContentException("편집 가능한 Markdown 문서만 재처리할 수 있습니다.");
        }
        editLockService.requireWritable(documentId, userId);
        if (document.getStatus() == DocumentStatus.processing) {
            throw new DocumentAlreadyProcessingException("이미 처리 중인 문서입니다.");
        }

        return new DocumentIngestResponse(documentId, promoteAndEnqueueIngest(document, userId, aiModel),
                document.getStatus());
    }

    /** 최신 편집본을 원본으로 승격하고 ingest outbox에 등록한다(수동 편입). 자동 편입은 업로드 본문이 곧 원본이라 승격 없이 등록한다. */
    private String promoteAndEnqueueIngest(Document document, String userId,
                                           Prefetched<WorkspaceAiModelClient.AiModelSelection> aiModel) {
        String documentId = document.getId();
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
        try {
            RetryingObjectPut.put(minioClient, storageProps.getBucket(),
                    normalizeObjectKey(document.getSourceUri()), bytes, "text/markdown");
        } catch (Exception e) {
            throw new DocumentUploadException("문서 원본 갱신 중 오류가 발생했습니다.", e);
        }

        document.reopenForReingest(currentContentHash, bytes.length);
        log.info("[문서 재ingest DB 갱신 완료] documentId={} contentHashPrefix={} byteSize={}",
                documentId, contentHashPrefix(currentContentHash), bytes.length);
        return enqueueIngest(document, userId, "manual", aiModel);
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
        documentAccessPolicy.requireEdit(document, userId);
        return renameAuthorized(workspaceId, userId, documentId, request, document);
    }

    /** MCP caller supplies current authorization; mutation semantics stay identical to the web path. */
    public DocumentRenameResponse renameAgentInCurrentTransaction(
            String workspaceId, String userId, String documentId, DocumentRenameRequest request) {
        if (request == null || request.baseVersion() == null || request.baseVersion() < 1)
            throw new InvalidDocumentFilenameException("base_version은 1 이상이어야 합니다.");
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return renameAuthorized(workspaceId, userId, documentId, request, document);
    }

    private DocumentRenameResponse renameAuthorized(String workspaceId, String userId, String documentId,
                                                     DocumentRenameRequest request, Document document) {
        // 채팅에서 만든 문서는 이름도 문답 출처의 일부라 본문과 함께 잠근다. 이동·삭제는 그대로 허용한다.
        if ("chat_export".equals(document.getOrigin())) {
            throw new DocumentTitleLockedException("채팅에서 만든 문서는 이름을 바꿀 수 없습니다.");
        }
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
        if (document.getDocumentRole() == DocumentRole.EDITABLE) {
            ingestCommandOutbox.enqueueRename(documentId, workspaceId, filename.displayName());
        }
        CommittedLog.info(log, "[문서 이름 변경 완료] operation=document_rename stage=completed documentId={} workspaceId={} userId={} newVersion={}",
                documentId, workspaceId, userId, request.baseVersion() + 1);
        return new DocumentRenameResponse(
                documentId,
                filename.filename(),
                filename.displayName(),
                request.baseVersion() + 1,
                updatedAt,
                true
        );
    }

    private static String lastAuthor(Document document) {
        return document.getUpdatedBy() != null ? document.getUpdatedBy() : document.getUserId();
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
        // 변환 결과 반영(mergeConvertBatch)과 같은 순서로 변환 대기열 행을 문서 행보다 먼저 잠근다.
        // 반대 순서면 삭제와 반영이 서로를 기다려 교착한다. 변환 문서가 아니면 행이 없어 아무것도 잠그지 않는다.
        convertQueueRepository.findByDocumentIdForUpdate(documentId);
        Document document = documentRepository
                .findByIdAndWorkspaceIdForUpdate(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        documentAccessPolicy.requireDelete(document, userId);
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
        documentWikiRetirement.retire(workspaceId, List.of(documentId));
        if ("convert".equals(document.getOrigin())) cancelConvertOnDelete(document);

        DocumentLifecycleResponse response = new DocumentLifecycleResponse(
                documentId,
                request.baseVersion() + 1,
                true,
                deletedAt,
                document.getSortOrder()
        );
        saveIdempotencyRecord(
                userId, endpointScope, idempotencyKey, requestHash, response);
        CommittedLog.info(log, "[문서 휴지통 이동 완료] operation=document_delete stage=completed documentId={} workspaceId={} userId={} newVersion={} 설명=문서를 휴지통으로 옮겼습니다. 영구 삭제는 보관 기간 뒤 별도로 처리됩니다.",
                documentId, workspaceId, userId, request.baseVersion() + 1);
        return response;
    }

    public DocumentTrashResponse trash(String workspaceId, String userId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Instant now = Instant.now();
        return new DocumentTrashResponse(
                documentRepository
                        .findAllByWorkspaceIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(workspaceId)
                        .stream()
                        .filter(document -> !isTrashExpired(document.getDeletedAt(), now))
                        .map(document -> new DocumentTrashResponse.DocumentTrashItem(
                                document.getId(),
                                document.getFilename(),
                                document.getDisplayName(),
                                document.getDocumentRole(),
                                document.getCurrentVersion(),
                                document.getDeletedAt(),
                                document.getDeletedBy(),
                                document.getDeleteOperationId(),
                                document.getSourceDocumentId(),
                                document.getDeletedAt().plus(trashRetention)
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
        // 삭제해도 folder_id는 남으므로 원래 폴더로 돌아간다. 폴더 삭제·이동과 같은 순서로 폴더 행을 문서 행보다 먼저 잠근다.
        // 반대 순서면 교착하고, 잠그지 않으면 빈 폴더 검사와 폴더 삭제 사이에 문서가 들어갈 수 있다.
        UUID originalFolderId = documentRepository.findFolderIdById(documentId, workspaceId).orElse(null);
        boolean originalFolderActive = originalFolderId != null
                && folderRepository.findActiveForUpdate(originalFolderId, workspaceId).isPresent();
        Document document = documentRepository
                .findByIdAndWorkspaceIdForUpdate(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        documentAccessPolicy.requireDelete(document, userId);
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
        // 영구 삭제 작업이 원본 객체를 먼저 지우므로, 보관 기간이 지난 문서를 되살리면 원본 없는 문서가 된다.
        if (isTrashExpired(document.getDeletedAt(), Instant.now())) {
            throw new TrashRetentionExpiredException("휴지통 보관 기간이 지나 복구할 수 없습니다.");
        }
        // 잠그기 전에 읽은 폴더와 다르면 그사이 복구·이동·재삭제가 있었던 것이라 잠근 폴더를 믿을 수 없다.
        if (!Objects.equals(originalFolderId, document.getFolderId())) {
            throw versionConflict();
        }
        UUID targetFolderId = originalFolderActive ? originalFolderId : null;
        long sortOrder = targetFolderId != null
                ? placementSortOrder(workspaceId, targetFolderId, document.getDocumentRole())
                : documentRepository.findRootItemsForUpdate(workspaceId, document.getDocumentRole()).stream()
                        .mapToLong(Document::getSortOrder)
                        .max()
                        .orElse(-1) + 1;
        Instant restoredAt = Instant.now();
        int updated = documentRepository.restoreIfVersionMatches(
                documentId,
                workspaceId,
                request.baseVersion(),
                targetFolderId,
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
        CommittedLog.info(log, "[문서 복구 완료] operation=document_restore stage=completed documentId={} workspaceId={} userId={} newVersion={} folderId={}",
                documentId, workspaceId, userId, request.baseVersion() + 1, targetFolderId);
        return response;
    }

    private boolean isTrashExpired(Instant deletedAt, Instant now) {
        return !deletedAt.plus(trashRetention).isAfter(now);
    }

    private void validateLifecycleRequest(DocumentLifecycleRequest request) {
        if (request == null || request.baseVersion() == null || request.baseVersion() < 1) {
            throw new InvalidDocumentVersionException(
                    "base_version은 1 이상의 정수여야 합니다.");
        }
    }

    private boolean deleteMinioObject(String uri) {
        if (uri == null || uri.isBlank()) return true;
        String objectKey = normalizeObjectKey(uri);
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(storageProps.getBucket())
                            .object(objectKey)
                            .build()
            );
            return true;
        } catch (Exception e) {
            log.warn("[MinIO 오브젝트 삭제 실패] uri={} error={}", uri, e.getMessage());
            return false;
        }
    }

    public DocumentBlocksResponse blocks(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        PipelineWikiStateRequester.DocumentWikiContext context =
                pipelineWikiStateRequester.documentContext(workspaceId, documentId);
        // block_id는 영구 ID라 문서 순서와 다르다. 위치가 없는 block은 받은 순서대로 뒤에 둔다.
        List<DocumentBlockResponse> blocks = context.sourceBlocks().stream()
                .sorted(Comparator.comparing(PipelineWikiStateRequester.SourceBlock::position,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(block -> new DocumentBlockResponse(block.blockId(), block.position(), block.lineStart(),
                        block.lineEnd(), block.blockType(), block.text()))
                .toList();
        // AI는 block을 만든 ingest 입력 Markdown의 SHA-256을 준다. 편집 문서의 current_content_hash는 같은 방식이다.
        // chat_export는 current_content_hash가 세션 기반이라, AI에 보낸 입력 Markdown의 해시와 비교한다.
        String sourceContentHash = context.sourceContentHash();
        String currentContentHash = "chat_export".equals(document.getOrigin())
                ? Optional.ofNullable(document.getPipelineInputMarkdown())
                        .map(markdown -> sha256(markdown.getBytes(StandardCharsets.UTF_8)))
                        .orElse(null)
                : document.getCurrentContentHash();
        Boolean stale = sourceContentHash == null ? null : !sourceContentHash.equals(currentContentHash);

        return new DocumentBlocksResponse(documentId, sourceContentHash, currentContentHash, stale, blocks);
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
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.codePoints().anyMatch(Character::isISOControl)) {
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
