package fruition.core.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.domain.DocumentProcessingState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.dto.DocumentBlocksResponse;
import fruition.core.document.dto.DocumentContentSaveResponse;
import fruition.core.document.dto.DocumentContentDiffResponse;
import fruition.core.document.dto.DocumentContentVersionListResponse;
import fruition.core.document.dto.DocumentDetailResponse;
import fruition.core.document.dto.DocumentDuplicateResponse;
import fruition.core.document.dto.DocumentListResponse;
import fruition.core.document.dto.DocumentLifecycleRequest;
import fruition.core.document.dto.DocumentLifecycleResponse;
import fruition.core.document.dto.DocumentTrashResponse;
import fruition.core.document.dto.DocumentUploadResponse;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.dto.DocumentRenameRequest;
import fruition.core.document.dto.DocumentRenameResponse;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.exception.DocumentUploadException;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import fruition.core.document.exception.InvalidMarkdownContentException;
import fruition.shared.idempotency.IdempotencyConflictException;
import fruition.shared.idempotency.IdempotencyInProgressException;
import fruition.shared.idempotency.InvalidIdempotencyKeyException;
import fruition.core.document.exception.MarkdownContentTooLargeException;
import fruition.core.document.repository.PostgresDocumentEditSaveResult;
import fruition.core.document.repository.PostgresDocumentEditStore;
import fruition.core.document.repository.IngestCommandOutbox;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.shared.idempotency.IdempotencyService;
import fruition.core.document.repository.DocumentRepository;
import fruition.shared.util.StorageProperties;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceBlocksTest {

    /** 재생성 delta로 넘길 문답 블록. provenance는 본문이 아니라 여기에만 있다. */
    private static final java.util.List<DocumentService.PipelineSourceBlock> DELTA_BLOCKS =
            java.util.List.of(new DocumentService.PipelineSourceBlock("session_1:pair_1", "Q : 질문\nA : 답변"));


    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Mock DocumentRepository documentRepository;
    @Mock fruition.core.document.repository.FolderRepository folderRepository;
    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock MinioClient minioClient;
    @Mock StorageProperties storageProps;
    @Mock IngestCommandOutbox ingestCommandOutbox;
    @Mock PipelineWikiStateRequester pipelineWikiStateRequester;
    @Mock fruition.core.document.repository.DocumentConvertQueueRepository convertQueueRepository;
    @Mock fruition.core.document.repository.ConverterClient converterClient;
    @Mock TransactionTemplate transactionTemplate;
    @Mock DocumentEditStateInitializer editStateInitializer;
    @Mock DocumentEditStateRepository editStateRepository;
    @Mock PostgresDocumentEditStore postgresDocumentEditStore;
    @Mock fruition.core.document.repository.DocumentContentVersionRepository contentVersionRepository;
    @Mock MarkdownDiffService markdownDiffService;
    @Mock fruition.core.document.service.DocumentEditLockService editLockService;
    @Mock IdempotencyService idempotencyService;
    @Mock DocumentAssetReferenceSynchronizer assetReferenceSynchronizer;
    @Mock DocumentAssetReferenceParser assetReferenceParser;
    @Mock fruition.core.document.repository.DocumentAssetRepository assetRepository;
    @Mock fruition.core.aihistory.service.OperationRecorder operationRecorder;
    @Mock fruition.core.aihistory.service.IngestOperationStarter ingestOperationStarter;
    @Mock fruition.core.aihistory.service.AgentApplyOperationStore applyOperationStore;
    @Mock fruition.core.authz.WorkspaceAiModelClient workspaceAiModelClient;

    PlatformTransactionManager transactionManager;
    DocumentService documentService;

    private final fruition.core.document.repository.AiCommandOutboxWriter taskWriter =
            org.mockito.Mockito.mock(fruition.core.document.repository.AiCommandOutboxWriter.class);

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(applyOperationStore.authorizeSave(anyString(), anyString(), anyString())).thenReturn(true);
        org.mockito.Mockito.lenient().when(taskWriter.active(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        org.mockito.Mockito.lenient().when(taskWriter.join(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        documentService = new DocumentService(documentRepository, folderRepository,
                workspaceAccessGuard, minioClient, storageProps,
                ingestCommandOutbox, pipelineWikiStateRequester,
                convertQueueRepository, converterClient, transactionTemplate,
                editStateInitializer, editStateRepository, new DocumentItemAssembler(editStateRepository),
                postgresDocumentEditStore,
                contentVersionRepository, markdownDiffService,
                editLockService, idempotencyService,
                assetReferenceSynchronizer,
                assetReferenceParser, assetRepository,
                new ObjectMapper().findAndRegisterModules(),
                applyOperationStore,
                operationRecorder,
                ingestOperationStarter,
                workspaceAiModelClient, taskWriter);
        lenient().when(pipelineWikiStateRequester.documentContext(anyString(), anyString()))
                .thenReturn(new PipelineWikiStateRequester.DocumentWikiContext(List.of(), List.of()));
        // 직접 생성·복제·변환 placeholder도 생성 시점에 원본을 object storage에 쓴다.
        lenient().when(storageProps.getBucket()).thenReturn("test-bucket");
        lenient().when(documentRepository.findByIdAndWorkspaceIdForUpdate(anyString(), anyString()))
                .thenAnswer(invocation -> documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                        invocation.getArgument(0), invocation.getArgument(1)));
        lenient().when(idempotencyService.replay(
                anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        lenient().doCallRealMethod().when(idempotencyService).validateKey(any());
        lenient().doCallRealMethod().when(idempotencyService).requestHash(any(String[].class));
        lenient().when(idempotencyService.currentExecutionId()).thenReturn(Optional.empty());
        // 기본 저장 결과: base revision + 1로 변경 성공. 필요한 테스트는 개별로 다시 stub한다.
        lenient().when(postgresDocumentEditStore.save(
                anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(),
                anyString(), any()))
                .thenAnswer(invocation -> {
                    String documentId = invocation.getArgument(1);
                    long baseRevision = invocation.getArgument(4);
                    DocumentEditState base = editStateRepository.findById(documentId)
                            .orElse(new DocumentEditState(documentId, "", "", baseRevision));
                    return new PostgresDocumentEditSaveResult(
                            baseRevision,
                            base.getMarkdown(),
                            base.getContentHash(),
                            baseRevision + 1,
                            invocation.getArgument(3),
                            Instant.now(),
                            invocation.getArgument(6),
                            true, false);
                });
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<org.springframework.transaction.support.TransactionCallback<Object>>getArgument(0)
                        .doInTransaction(null));
        transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionTemplate.getTransactionManager()).thenReturn(transactionManager);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    private void stubOwnedWorkspace() {
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
    }

    @Test
    @DisplayName("문서가 존재하면 block 목록을 block_id 오름차순으로 반환한다")
    void blocks_existingDocument_returnsBlocksInOrder() {
        stubOwnedWorkspace();
        Document document = new Document("doc_1f9a74af", WORKSPACE_ID, USER_ID, "original.md", "text/markdown", 100L,
                "sources/documents/doc_1f9a74af/original", "hash1");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1f9a74af", WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(pipelineWikiStateRequester.documentContext(WORKSPACE_ID, "doc_1f9a74af")).thenReturn(
                new PipelineWikiStateRequester.DocumentWikiContext(List.of(), List.of(
                        new PipelineWikiStateRequester.SourceBlock("B0005", "다섯 번째 block 본문"),
                        new PipelineWikiStateRequester.SourceBlock("B0006", "여섯 번째 block 본문"))));

        DocumentBlocksResponse response = documentService.blocks(WORKSPACE_ID, USER_ID, "doc_1f9a74af");

        assertThat(response.documentId()).isEqualTo("doc_1f9a74af");
        assertThat(response.blocks()).hasSize(2);
        assertThat(response.blocks().get(0).blockId()).isEqualTo("B0005");
        assertThat(response.blocks().get(0).text()).isEqualTo("다섯 번째 block 본문");
        assertThat(response.blocks().get(1).blockId()).isEqualTo("B0006");
    }

    @Test
    @DisplayName("block이 없으면 200과 빈 배열을 반환한다")
    void blocks_noBlocks_returnsEmptyList() {
        stubOwnedWorkspace();
        Document document = new Document("doc_empty", WORKSPACE_ID, USER_ID, "original.md", "text/markdown", 100L,
                "sources/documents/doc_empty/original", "hash2");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_empty", WORKSPACE_ID))
                .thenReturn(Optional.of(document));

        DocumentBlocksResponse response = documentService.blocks(WORKSPACE_ID, USER_ID, "doc_empty");

        assertThat(response.blocks()).isEmpty();
    }

    @Test
    @DisplayName("문서가 존재하지 않으면 DocumentNotFoundException을 던진다")
    void blocks_unknownDocument_throws() {
        stubOwnedWorkspace();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_unknown", WORKSPACE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.blocks(WORKSPACE_ID, USER_ID, "doc_unknown"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    @DisplayName("소유하지 않은 워크스페이스면 WorkspaceNotFoundException을 던진다")
    void blocks_notOwnedWorkspace_throws() {
        doThrow(new WorkspaceNotFoundException(WORKSPACE_ID))
                .when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);

        assertThatThrownBy(() -> documentService.blocks(WORKSPACE_ID, USER_ID, "doc_1f9a74af"))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    @DisplayName("상세 조회는 metadata current_version과 edit_revision을 분리한다")
    void findById_separatesMetadataVersionAndEditRevision() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_lazy",
                WORKSPACE_ID,
                USER_ID,
                "legacy.md",
                "text/markdown",
                10,
                "sources/documents/doc_lazy/original",
                "legacy-hash"
        );
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_lazy", WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(postgresDocumentEditStore.findState("doc_lazy"))
                .thenReturn(Optional.of(new DocumentEditState(
                        "doc_lazy", "# 본문", "edit-hash", 7)));

        DocumentDetailResponse response = documentService.findById(WORKSPACE_ID, USER_ID, "doc_lazy");

        verify(editStateInitializer).initializeIfNeeded(document);
        assertThat(response.markdown()).isEqualTo("# 본문");
        assertThat(response.currentVersion()).isEqualTo(1);
        assertThat(response.editRevision()).isEqualTo(7);
        assertThat(response.editable()).isTrue();
        assertThat(response.documentRole()).isEqualTo(DocumentRole.EDITABLE);
    }

    @Test
    @DisplayName("편집 가능 문서의 PostgreSQL 편집 상태가 없으면 상세 조회를 거절한다")
    void findById_editableWithoutPostgresState_throws() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_missing_edit_state", WORKSPACE_ID, USER_ID, "노트.md", "text/markdown", 10,
                "sources/documents/doc_missing_edit_state/original", "source-hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                document.getId(), WORKSPACE_ID)).thenReturn(Optional.of(document));
        when(postgresDocumentEditStore.findState(document.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.findById(WORKSPACE_ID, USER_ID, document.getId()))
                .isInstanceOf(InvalidMarkdownContentException.class);
        verify(editStateInitializer).initializeIfNeeded(document);
    }

    @Test
    @DisplayName("원본 문서는 편집 상태가 없어도 current_version을 edit_revision으로 유지한다")
    void findById_nonEditableWithoutPostgresState_preservesCurrentVersion() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_source_without_edit_state", WORKSPACE_ID, USER_ID, "자료.pdf", "application/pdf", 10,
                "sources/documents/doc_source_without_edit_state/original", "source-hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                document.getId(), WORKSPACE_ID)).thenReturn(Optional.of(document));
        when(postgresDocumentEditStore.findState(document.getId())).thenReturn(Optional.empty());

        DocumentDetailResponse response = documentService.findById(WORKSPACE_ID, USER_ID, document.getId());

        assertThat(response.editRevision()).isEqualTo(document.getCurrentVersion());
        assertThat(response.editable()).isFalse();
    }

    @Test
    @DisplayName("편집 가능 문서의 PostgreSQL 편집 상태가 없으면 초기화 후 버전 목록을 반환한다")
    void listContentVersions_withoutPostgresState_initializesBeforeReading() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_versions_missing_state", WORKSPACE_ID, USER_ID, "노트.md", "text/markdown", 10,
                "sources/documents/doc_versions_missing_state/original", "source-hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                document.getId(), WORKSPACE_ID)).thenReturn(Optional.of(document));
        AtomicReference<Optional<DocumentEditState>> state = new AtomicReference<>(Optional.empty());
        doAnswer(invocation -> {
            state.set(Optional.of(new DocumentEditState(
                    document.getId(), "# 노트", "edit-hash", 2)));
            return null;
        }).when(editStateInitializer).initializeIfNeeded(document);
        when(postgresDocumentEditStore.findState(document.getId())).thenAnswer(invocation -> state.get());
        when(contentVersionRepository.findSummaries(document.getId())).thenReturn(List.of());

        DocumentContentVersionListResponse response = documentService.listContentVersions(
                WORKSPACE_ID, USER_ID, document.getId());

        InOrder order = inOrder(editStateInitializer, postgresDocumentEditStore);
        order.verify(editStateInitializer).initializeIfNeeded(document);
        order.verify(postgresDocumentEditStore).findState(document.getId());
        assertThat(response.documentId()).isEqualTo(document.getId());
        assertThat(response.currentVersion()).isEqualTo(2);
        assertThat(response.versions()).isEmpty();
    }

    @Test
    @DisplayName("호환 목록은 페이지와 원본 자료 메타데이터를 구분해 반환한다")
    void findAll_mapsPageAndSourceMetadata() {
        stubOwnedWorkspace();
        Document page = new Document("doc_page", WORKSPACE_ID, USER_ID, "노트.md", "text/markdown", 10,
                "sources/documents/doc_page/original", "page-hash");
        Document source = new Document("doc_source", WORKSPACE_ID, USER_ID, "자료.pdf", "application/pdf", 20,
                "sources/documents/doc_source/original", "source-hash");
        when(documentRepository.findVisibleByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(page, source));
        when(editStateRepository.findAllById(List.of("doc_page", "doc_source")))
                .thenReturn(List.of(new DocumentEditState("doc_page", "# 노트", "edit-hash", 1)));

        DocumentListResponse response = documentService.findAll(WORKSPACE_ID, USER_ID, null);

        assertThat(response.documents()).extracting(DocumentListResponse.DocumentItem::area)
                .containsExactly("pages", "sources");
        assertThat(response.documents()).extracting(DocumentListResponse.DocumentItem::itemKind)
                .containsExactly("page", "source_file");
        assertThat(response.documents().get(0).editable()).isTrue();
        assertThat(response.documents().get(0).fileType()).isEqualTo("md");
        assertThat(response.documents().get(1).editable()).isFalse();
        assertThat(response.documents().get(1).fileType()).isEqualTo("pdf");
    }

    @Test
    @DisplayName("기존 Markdown 문서는 PostgreSQL 상태가 없어도 목록에서 편집 가능으로 표시한다")
    void findAll_legacyMarkdownWithoutPostgresState_remainsEditable() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_legacy_markdown", WORKSPACE_ID, USER_ID, "기존 노트.md", "text/markdown", 10,
                "sources/documents/doc_legacy_markdown/original", "source-hash");
        when(documentRepository.findVisibleByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(document));
        when(editStateRepository.findAllById(List.of(document.getId()))).thenReturn(List.of());

        DocumentListResponse response = documentService.findAll(WORKSPACE_ID, USER_ID, null);

        assertThat(response.documents()).singleElement()
                .extracting(DocumentListResponse.DocumentItem::editable)
                .isEqualTo(true);
        verify(editStateInitializer, never()).initializeIfNeeded(any(Document.class));
    }

    @Test
    @DisplayName("편집 상태가 있으면 source_uri가 blank여도 목록에서 편집 가능으로 표시한다")
    void findAll_withEditStateAndBlankSourceUri_remainsEditable() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_state_blank_source", WORKSPACE_ID, USER_ID, "상태가 있는 노트.md", "text/markdown", 10,
                "   ", "source-hash");
        when(documentRepository.findVisibleByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(document));
        when(editStateRepository.findAllById(List.of(document.getId()))).thenReturn(List.of(
                new DocumentEditState(document.getId(), "# 본문", "edit-hash", 1)));

        DocumentListResponse response = documentService.findAll(WORKSPACE_ID, USER_ID, null);

        assertThat(response.documents()).singleElement()
                .extracting(DocumentListResponse.DocumentItem::editable)
                .isEqualTo(true);
    }

    @Test
    @DisplayName("편집 상태가 없고 source_uri가 blank면 목록에서 편집 불가로 표시한다")
    void findAll_withoutEditStateAndBlankSourceUri_isNotEditable() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_no_state_blank_source", WORKSPACE_ID, USER_ID, "상태가 없는 노트.md", "text/markdown", 10,
                "   ", "source-hash");
        when(documentRepository.findVisibleByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(document));
        when(editStateRepository.findAllById(List.of(document.getId()))).thenReturn(List.of());

        DocumentListResponse response = documentService.findAll(WORKSPACE_ID, USER_ID, null);

        assertThat(response.documents()).singleElement()
                .extracting(DocumentListResponse.DocumentItem::editable)
                .isEqualTo(false);
    }

    @Test
    @DisplayName("업로드만 되고 ingest가 시작되지 않은 문서는 processing_state를 내려보내지 않는다")
    void findAll_uploadedWithoutPipelineRun_hasNoProcessingState() {
        stubOwnedWorkspace();
        Document document = sourceDocument("doc_uploaded");
        document.updateStatus(fruition.core.document.domain.DocumentStatus.uploaded, null, null, null);
        stubSingleVisibleDocument(document);

        DocumentListResponse response = documentService.findAll(WORKSPACE_ID, USER_ID, null);

        assertThat(response.documents()).singleElement()
                .extracting(DocumentListResponse.DocumentItem::processingState)
                .isNull();
    }

    @Test
    @DisplayName("ingest가 시작됐지만 pipeline run ID가 아직 없으면 processing_state는 starting이다")
    void findAll_processingWithoutPipelineRun_isStarting() {
        stubOwnedWorkspace();
        // 생성자 기본 status가 processing이다. ingest 요청은 나갔지만 run ID는 아직 없는 상태.
        stubSingleVisibleDocument(sourceDocument("doc_starting"));

        DocumentListResponse response = documentService.findAll(WORKSPACE_ID, USER_ID, null);

        assertThat(response.documents()).singleElement()
                .extracting(DocumentListResponse.DocumentItem::processingState)
                .isEqualTo(DocumentProcessingState.starting);
    }

    @Test
    @DisplayName("heartbeat가 최근이면 processing_state는 running이다")
    void findAll_recentHeartbeat_isRunning() {
        stubOwnedWorkspace();
        Document document = sourceDocument("doc_running");
        document.markPipelineStarted("run_running", Instant.now());
        stubSingleVisibleDocument(document);

        DocumentListResponse response = documentService.findAll(WORKSPACE_ID, USER_ID, null);

        assertThat(response.documents()).singleElement()
                .extracting(DocumentListResponse.DocumentItem::processingState)
                .isEqualTo(DocumentProcessingState.running);
    }

    @Test
    @DisplayName("heartbeat가 60초 넘게 끊기면 processing_state는 stalled다")
    void findAll_staleHeartbeat_isStalled() {
        stubOwnedWorkspace();
        Document document = sourceDocument("doc_stalled");
        document.markPipelineStarted("run_stalled", Instant.now().minusSeconds(120));
        stubSingleVisibleDocument(document);

        DocumentListResponse response = documentService.findAll(WORKSPACE_ID, USER_ID, null);

        assertThat(response.documents()).singleElement()
                .extracting(DocumentListResponse.DocumentItem::processingState)
                .isEqualTo(DocumentProcessingState.stalled);
    }

    @Test
    @DisplayName("처리에 실패한 문서는 processing_state가 failed다")
    void findAll_failedDocument_isFailed() {
        stubOwnedWorkspace();
        Document document = sourceDocument("doc_failed");
        document.markProcessingFailed("추출 실패", Instant.now());
        stubSingleVisibleDocument(document);

        DocumentListResponse response = documentService.findAll(WORKSPACE_ID, USER_ID, null);

        assertThat(response.documents()).singleElement()
                .extracting(DocumentListResponse.DocumentItem::processingState)
                .isEqualTo(DocumentProcessingState.failed);
    }

    private Document sourceDocument(String documentId) {
        return new Document(documentId, WORKSPACE_ID, USER_ID, "자료.pdf", "application/pdf", 20,
                "sources/documents/" + documentId + "/original", "source-hash");
    }

    private void stubSingleVisibleDocument(Document document) {
        when(documentRepository.findVisibleByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(document));
        when(editStateRepository.findAllById(List.of(document.getId()))).thenReturn(List.of());
    }

    @Test
    @DisplayName("검색어는 앞뒤 공백을 제거해 파일명 검색 repository에 전달한다")
    void findAll_withQuery_usesFilenameSearch() {
        stubOwnedWorkspace();
        when(documentRepository.searchVisibleByWorkspaceId(WORKSPACE_ID, "보고서")).thenReturn(List.of());

        DocumentListResponse response = documentService.findAll(WORKSPACE_ID, USER_ID, "  보고서  ");

        assertThat(response.documents()).isEmpty();
        verify(documentRepository).searchVisibleByWorkspaceId(WORKSPACE_ID, "보고서");
    }

    @Test
    @DisplayName("초기 노트는 원본을 object storage에 쓰고 직접 생성 Markdown과 편집 상태로 저장한다")
    void createInitialNote_savesDirectMarkdownWithSource() throws Exception {
        documentService.createInitialNote("ws_first", USER_ID);
        documentService.createInitialNote("ws_second", USER_ID);

        ArgumentCaptor<Document> documents = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<DocumentEditState> editStates = ArgumentCaptor.forClass(DocumentEditState.class);
        verify(documentRepository, times(2)).save(documents.capture());
        verify(editStateRepository, times(2)).save(editStates.capture());
        // 파이프라인은 source_uri로만 본문을 읽으므로 문서를 만들 때 원본도 같이 만든다
        verify(minioClient, times(2)).putObject(any(PutObjectArgs.class));
        assertThat(documents.getAllValues())
                .allSatisfy(document -> {
                    assertThat(document.getFilename()).isEqualTo("새 노트.md");
                    assertThat(document.getMimeType()).isEqualTo("text/markdown");
                    assertThat(document.getByteSize()).isPositive();
                    assertThat(document.getSourceUri())
                            .isEqualTo("sources/documents/" + document.getId() + "/original");
                    assertThat(document.getContentHash()).isNull();
                    assertThat(document.getCurrentVersion()).isEqualTo(1);
                    assertThat(document.getStatus()).isEqualTo(fruition.core.document.domain.DocumentStatus.completed);
                });
        assertThat(documents.getAllValues().get(0).getWorkspaceId()).isEqualTo("ws_first");
        assertThat(documents.getAllValues().get(1).getWorkspaceId()).isEqualTo("ws_second");
        assertThat(editStates.getAllValues())
                .extracting(DocumentEditState::getMarkdown)
                .containsOnly("# 새 노트\n");
    }

    @Test
    @DisplayName("빈 Markdown 직접 생성은 version 1 편집 문서와 멱등 기록을 저장한다")
    void createMarkdown_emptyBody_createsEditableDocument() {
        stubOwnedWorkspace();
        when(documentRepository.findMaxRootSortOrder(WORKSPACE_ID, DocumentRole.EDITABLE)).thenReturn(-1L);

        DocumentUploadResponse response = documentService.createMarkdown(
                WORKSPACE_ID,
                USER_ID,
                "create-key",
                new MarkdownDocumentCreateRequest(" 새 문서 ", "", null)
        );

        ArgumentCaptor<Document> document = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(document.capture());
        verify(editStateRepository).save(any(DocumentEditState.class));
        verify(idempotencyService).save(any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(document.getValue().getFilename()).isEqualTo("새 문서.md");
        assertThat(document.getValue().getSourceUri())
                .isEqualTo("sources/documents/" + document.getValue().getId() + "/original");
        assertThat(document.getValue().getSortOrder()).isZero();
        assertThat(response.editable()).isTrue();
        assertThat(response.currentVersion()).isEqualTo(1);
        assertThat(response.documentRole()).isEqualTo(DocumentRole.EDITABLE);
    }

    @Test
    @DisplayName("직접 생성은 멱등 키를 필수로 받고 UTF-8 5MB 초과 본문을 거절한다")
    void createMarkdown_validatesIdempotencyKeyAndBodySize() {
        stubOwnedWorkspace();

        assertThatThrownBy(() -> documentService.createMarkdown(
                WORKSPACE_ID,
                USER_ID,
                null,
                new MarkdownDocumentCreateRequest("문서", "", null)
        )).isInstanceOf(InvalidIdempotencyKeyException.class);

        assertThatThrownBy(() -> documentService.createMarkdown(
                WORKSPACE_ID,
                USER_ID,
                "create-key",
                new MarkdownDocumentCreateRequest("문서", "a".repeat(5 * 1024 * 1024 + 1), null)
        )).isInstanceOf(MarkdownContentTooLargeException.class);

        verify(documentRepository, never()).save(any(Document.class));
        verify(editStateRepository, never()).save(any(DocumentEditState.class));
    }

    @Test
    @DisplayName("동일한 멱등 키와 요청은 기존 문서를 반환하고 다시 저장하지 않는다")
    void createMarkdown_sameIdempotencyRequest_replaysExistingDocument() {
        stubOwnedWorkspace();
        MarkdownDocumentCreateRequest request = new MarkdownDocumentCreateRequest("문서", "# 본문", null);

        DocumentUploadResponse first =
                documentService.createMarkdown(WORKSPACE_ID, USER_ID, "same-key", request);
        verify(documentRepository).save(any(Document.class));

        when(idempotencyService.replay(
                eq(USER_ID), anyString(), eq("same-key"), anyString(),
                eq(DocumentUploadResponse.class))).thenReturn(Optional.of(first));
        DocumentUploadResponse replay =
                documentService.createMarkdown(WORKSPACE_ID, USER_ID, "same-key", request);

        assertThat(replay).isEqualTo(first);
        verify(documentRepository, times(1)).save(any(Document.class));
        verify(editStateRepository, times(1)).save(any(DocumentEditState.class));
    }

    @Test
    @DisplayName("같은 멱등 키에 다른 생성 요청은 충돌한다")
    void createMarkdown_sameIdempotencyKeyDifferentRequest_conflicts() {
        stubOwnedWorkspace();
        MarkdownDocumentCreateRequest firstRequest = new MarkdownDocumentCreateRequest("문서", "# 본문", null);
        documentService.createMarkdown(WORKSPACE_ID, USER_ID, "same-key", firstRequest);
        when(idempotencyService.replay(
                eq(USER_ID), anyString(), eq("same-key"), anyString(),
                eq(DocumentUploadResponse.class)))
                .thenThrow(new IdempotencyConflictException("충돌"));

        assertThatThrownBy(() -> documentService.createMarkdown(
                WORKSPACE_ID,
                USER_ID,
                "same-key",
                new MarkdownDocumentCreateRequest("다른 문서", "# 본문", null)
        )).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("Markdown 업로드는 편집 상태와 원본만 저장하고 파이프라인을 요청하지 않는다")
    void uploadMarkdown_createsEditStateImmediately() throws Exception {
        stubOwnedWorkspace();
        when(storageProps.getBucket()).thenReturn("test-bucket");
        when(documentRepository.findMaxRootSortOrder(WORKSPACE_ID, DocumentRole.EDITABLE)).thenReturn(-1L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "업로드.md", "text/markdown", "# 업로드".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        DocumentUploadResponse response =
                documentService.upload(WORKSPACE_ID, USER_ID, "upload-key", null, file);

        ArgumentCaptor<Document> storedDocument = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(storedDocument.capture());
        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(editStateRepository).save(any(DocumentEditState.class));
        verify(idempotencyService).save(any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(storedDocument.getValue().getStatus()).isEqualTo(
                fruition.core.document.domain.DocumentStatus.uploaded);
        assertThat(response.status()).isEqualTo(fruition.core.document.domain.DocumentStatus.uploaded);
        assertThat(response.editable()).isTrue();
        assertThat(response.documentRole()).isEqualTo(DocumentRole.EDITABLE);
        assertThat(response.currentVersion()).isEqualTo(1);
    }

    @Test
    void upload_inProgressExceptionIsNotWrapped() {
        stubOwnedWorkspace();
        MockMultipartFile file = new MockMultipartFile(
                "file", "업로드.md", "text/markdown",
                "# 업로드".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(idempotencyService.replay(
                eq(USER_ID), anyString(), eq("same-key"), anyString(),
                eq(DocumentUploadResponse.class)))
                .thenThrow(new IdempotencyInProgressException("처리 중"));

        assertThatThrownBy(() -> documentService.upload(
                WORKSPACE_ID, USER_ID, "same-key", null, file))
                .isInstanceOf(IdempotencyInProgressException.class);
    }

    @Test
    @DisplayName("비 Markdown 업로드는 원본만 저장하고 파이프라인을 요청하지 않는다")
    void uploadNonMarkdown_storesOriginalWithoutProcessing() throws Exception {
        stubOwnedWorkspace();
        when(storageProps.getBucket()).thenReturn("test-bucket");
        when(documentRepository.findMaxRootSortOrder(WORKSPACE_ID, DocumentRole.ORIGINAL)).thenReturn(-1L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "자료.pdf", "application/pdf", new byte[]{1, 2, 3});

        DocumentUploadResponse response =
                documentService.upload(WORKSPACE_ID, USER_ID, "upload-pdf-key", null, file);

        ArgumentCaptor<Document> storedDocument = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(storedDocument.capture());
        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(editStateRepository, never()).save(any(DocumentEditState.class));
        assertThat(storedDocument.getValue().getStatus()).isEqualTo(
                fruition.core.document.domain.DocumentStatus.uploaded);
        assertThat(storedDocument.getValue().getProcessedAt()).isNull();
        assertThat(response.editable()).isFalse();
        assertThat(response.documentRole()).isEqualTo(DocumentRole.ORIGINAL);
    }

    @Test
    @DisplayName("Markdown 본문 저장은 편집 상태와 현재 버전을 함께 갱신한다")
    void saveContent_changed_updatesContentAndVersion() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "original-hash");
        DocumentEditState editState = new DocumentEditState(
                document.getId(), "old", DocumentEditingRules.markdown("old").contentHash(), 1);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById(document.getId())).thenReturn(Optional.of(editState));

        DocumentContentSaveResponse response = documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "# 변경\n", 1L, "write_1", null);

        assertThat(response.changed()).isTrue();
        assertThat(response.currentVersion()).isEqualTo(2);
        // JPA 편집 상태는 더 이상 갱신하지 않는다 — canonical은 PostgreSQL이다.
        assertThat(editState.getMarkdown()).isEqualTo("old");
        assertThat(document.getContentHash()).isEqualTo("original-hash");
        verify(postgresDocumentEditStore).save(
                eq(WORKSPACE_ID), eq(document.getId()), eq("# 변경\n"), anyString(),
                eq(1L), eq("write_1"), eq(USER_ID), isNull());
    }

    @Test
    @DisplayName("이미지를 첨부하지 않는 저장도 본문 기준으로 asset 참조를 동기화한다")
    void saveContent_synchronizesAssetReferencesFromMarkdown() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "original-hash");
        UUID retainedAssetId = UUID.randomUUID();
        String retained = "![](/api/workspaces/" + WORKSPACE_ID + "/assets/" + retainedAssetId + "/content)\n";
        DocumentEditState editState = new DocumentEditState(
                document.getId(), "old", DocumentEditingRules.markdown("old").contentHash(), 1);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById(document.getId())).thenReturn(Optional.of(editState));
        var reference = new DocumentAssetReferenceParser.ManagedAssetReference(WORKSPACE_ID, retainedAssetId);
        when(assetReferenceParser.parse(retained)).thenReturn(Set.of(reference));

        documentService.saveContent(WORKSPACE_ID, USER_ID, document.getId(), retained, 1L, "write_sync", null);

        verify(assetReferenceSynchronizer).synchronize(
                document.getId(), WORKSPACE_ID, Set.of(reference));
    }

    @Test
    @DisplayName("source=agent 저장도 PostgreSQL version read model을 갱신한다")
    void saveContent_sourceAgent_projectsVersions() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "original-hash");
        DocumentEditState editState = new DocumentEditState(
                document.getId(), "old", DocumentEditingRules.markdown("old").contentHash(), 1);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById(document.getId())).thenReturn(Optional.of(editState));

        DocumentContentSaveResponse response = documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "# 변경\n", 1L, "write_1", "agent");

        assertThat(response.changed()).isTrue();
        verify(postgresDocumentEditStore).save(
                eq(WORKSPACE_ID), eq(document.getId()), eq("# 변경\n"), anyString(),
                eq(1L), eq("write_1"), eq(USER_ID), isNull());
        verify(contentVersionRepository).insertIfAbsent(
                eq(document.getId()), eq(1L), eq("old"), eq(editState.getContentHash()), eq(USER_ID), any());
        verify(contentVersionRepository).insertIfAbsent(
                eq(document.getId()), eq(2L), eq("# 변경\n"), anyString(), eq(USER_ID), any());
    }

    @Test
    @DisplayName("유효하지 않은 Agent 적용 표는 저장 전에 거절한다")
    void saveContent_invalidApplyOperationId_rejectsBeforeAnyWrite() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "original-hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(applyOperationStore.consume("op-invalid", USER_ID, document.getId(), "write-agent", 1L, "# 변경\n"))
                .thenReturn(false);

        assertThatThrownBy(() -> documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "# 변경\n", 1L,
                "write-agent", "agent", "op-invalid"))
                .isInstanceOf(fruition.core.agent.exception.InvalidAgentTurnRequestException.class);

        verify(applyOperationStore).consume("op-invalid", USER_ID, document.getId(), "write-agent", 1L, "# 변경\n");
        verify(editStateInitializer, never()).initializeIfNeeded(any(Document.class));
        verifyNoInteractions(postgresDocumentEditStore, contentVersionRepository,
                assetReferenceSynchronizer, operationRecorder);
    }

    @Test
    @DisplayName("pending 감사 예약 실패는 본문과 버전을 저장하지 않는다")
    void saveContent_pendingAuditFailure_rejectsBeforeEditStateWrite() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "original-hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(applyOperationStore.consume("op-pending", USER_ID, document.getId(), "write-pending", 1L, "# 변경\n"))
                .thenReturn(true);
        doThrow(new IllegalStateException("pending 감사 실패"))
                .when(operationRecorder).prepareDocumentEdit(
                        eq("op-pending"), eq(WORKSPACE_ID), eq(USER_ID), eq(document.getId()), any());

        assertThatThrownBy(() -> documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "# 변경\n", 1L,
                "write-pending", "agent", "op-pending"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("pending 감사 실패");

        verify(postgresDocumentEditStore, never()).save(
                anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(),
                anyString(), any());
        verify(contentVersionRepository, never()).insertIfAbsent(
                anyString(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Agent 적용 표 소비와 성공 감사 기록을 한 PostgreSQL transaction에서 처리한다")
    void saveContent_agentApplyConsumesTokenWithAuditAndVersionLink() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "original-hash");
        DocumentEditState editState = new DocumentEditState(
                document.getId(), "old", DocumentEditingRules.markdown("old").contentHash(), 1);
        doReturn(Optional.of(document)).when(documentRepository)
                .findByIdAndWorkspaceIdForUpdate(document.getId(), WORKSPACE_ID);
        when(editStateRepository.findById(document.getId())).thenReturn(Optional.of(editState));
        when(applyOperationStore.consume("op-agent", USER_ID, document.getId(), "write-agent", 1L, "# 변경\n"))
                .thenReturn(true);
        when(contentVersionRepository.linkOperation(document.getId(), 2L, "op-agent")).thenReturn(1);

        documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "# 변경\n", 1L,
                "write-agent", "agent", "op-agent");

        org.mockito.InOrder order = inOrder(applyOperationStore, postgresDocumentEditStore);
        order.verify(applyOperationStore).consume("op-agent", USER_ID, document.getId(), "write-agent", 1L, "# 변경\n");
        order.verify(postgresDocumentEditStore).save(
                eq(WORKSPACE_ID), eq(document.getId()), eq("# 변경\n"), anyString(),
                eq(1L), eq("write-agent"), eq(USER_ID), eq("op-agent"));
        verify(applyOperationStore).consume("op-agent", USER_ID, document.getId(), "write-agent", 1L, "# 변경\n");
        verify(operationRecorder).recordDocumentEdit(
                eq("op-agent"), eq(WORKSPACE_ID), eq(USER_ID), eq(document.getId()),
                eq(document.getDisplayName()),
                eq(1L), eq(2L), eq("old"), eq("# 변경\n"), any());
        verify(documentRepository).findByIdAndWorkspaceIdForUpdate(document.getId(), WORKSPACE_ID);
        verify(documentRepository, never()).findByIdAndWorkspaceIdAndDeletedAtIsNull(
                document.getId(), WORKSPACE_ID);
        verify(contentVersionRepository).linkOperation(document.getId(), 2L, "op-agent");
    }

    @Test
    @DisplayName("수동 receipt를 Agent 적용으로 재사용하면 성공 연결과 감사 기록을 남기지 않는다")
    void saveContent_manualReceiptCannotBeReplayedAsAgentApply() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "original-hash");
        DocumentEditState editState = new DocumentEditState(
                document.getId(), "old", DocumentEditingRules.markdown("old").contentHash(), 1);
        PostgresDocumentEditSaveResult manualResult = new PostgresDocumentEditSaveResult(
                1, "old", editState.getContentHash(), 2,
                DocumentEditingRules.markdown("# 변경\n").contentHash(), Instant.now(), USER_ID, true, false);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(postgresDocumentEditStore.save(
                eq(WORKSPACE_ID), eq(document.getId()), eq("# 변경\n"), anyString(),
                eq(1L), eq("write-reused"), eq(USER_ID), isNull()))
                .thenReturn(manualResult);
        when(applyOperationStore.consume("op-reused", USER_ID, document.getId(), "write-reused", 1L, "# 변경\n"))
                .thenReturn(true);
        when(postgresDocumentEditStore.save(
                eq(WORKSPACE_ID), eq(document.getId()), eq("# 변경\n"), anyString(),
                eq(1L), eq("write-reused"), eq(USER_ID), eq("op-reused")))
                .thenThrow(new IdempotencyConflictException("같은 revision_write_id를 다른 저장 요청에 사용할 수 없습니다."));

        documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "# 변경\n", 1L, "write-reused", null);

        assertThatThrownBy(() -> documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "# 변경\n", 1L,
                "write-reused", "agent", "op-reused"))
                .isInstanceOf(IdempotencyConflictException.class);

        verify(contentVersionRepository, never()).linkOperation(anyString(), anyLong(), anyString());
        verify(operationRecorder, never()).recordDocumentEdit(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("같은 Agent 저장 영수증 재생은 감사 변경을 중복 기록하지 않는다")
    void saveContent_exactAgentReplay_doesNotRecordSecondAudit() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "original-hash");
        DocumentEditState editState = new DocumentEditState(
                document.getId(), "old", DocumentEditingRules.markdown("old").contentHash(), 1);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById(document.getId())).thenReturn(Optional.of(editState));
        when(applyOperationStore.consume("op-replay", USER_ID, document.getId(), "write-replay", 1L, "# 변경\n"))
                .thenReturn(true);
        when(contentVersionRepository.linkOperation(document.getId(), 2L, "op-replay"))
                .thenReturn(1)
                .thenReturn(0);
        var linkedVersion = mock(fruition.core.document.domain.DocumentContentVersion.class);
        when(linkedVersion.getOperationId()).thenReturn("op-replay");
        when(contentVersionRepository.findById(any())).thenReturn(Optional.of(linkedVersion));

        documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "# 변경\n", 1L,
                "write-replay", "agent", "op-replay");
        documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "# 변경\n", 1L,
                "write-replay", "agent", "op-replay");

        verify(operationRecorder).recordDocumentEdit(
                eq("op-replay"), eq(WORKSPACE_ID), eq(USER_ID), eq(document.getId()),
                eq(document.getDisplayName()),
                eq(1L), eq(2L), eq("old"), eq("# 변경\n"), any());
        verify(contentVersionRepository, times(2)).linkOperation(document.getId(), 2L, "op-replay");
    }

    @Test
    @DisplayName("Agent 감사 실패 후 재시도는 연결과 감사를 다시 완료한다")
    void saveContent_auditFailureRetryLinksAndRecordsOnce() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "original-hash");
        DocumentEditState editState = new DocumentEditState(
                document.getId(), "old", DocumentEditingRules.markdown("old").contentHash(), 1);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById(document.getId())).thenReturn(Optional.of(editState));
        when(applyOperationStore.consume("op-retry", USER_ID, document.getId(), "write-retry", 1L, "# 변경\n"))
                .thenReturn(true);
        when(contentVersionRepository.linkOperation(document.getId(), 2L, "op-retry"))
                .thenReturn(1)
                .thenReturn(1);
        doThrow(new IllegalStateException("감사 실패")).doNothing()
                .when(operationRecorder).recordDocumentEdit(
                        eq("op-retry"), eq(WORKSPACE_ID), eq(USER_ID), eq(document.getId()),
                        eq(document.getDisplayName()),
                        eq(1L), eq(2L), eq("old"), eq("# 변경\n"), any());

        assertThatThrownBy(() -> documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "# 변경\n", 1L,
                "write-retry", "agent", "op-retry"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("감사 실패");
        documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "# 변경\n", 1L,
                "write-retry", "agent", "op-retry");

        verify(contentVersionRepository, times(2)).linkOperation(document.getId(), 2L, "op-retry");
        verify(operationRecorder, times(2)).recordDocumentEdit(
                eq("op-retry"), eq(WORKSPACE_ID), eq(USER_ID), eq(document.getId()),
                eq(document.getDisplayName()),
                eq(1L), eq(2L), eq("old"), eq("# 변경\n"), any());
    }

    @Test
    @DisplayName("Agent conflict도 적용 표 소비와 감사 기록을 한 PostgreSQL transaction에서 처리한다")
    void saveContent_agentConflictConsumesTokenWithAudit() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "hash");
        DocumentEditState editState = new DocumentEditState(document.getId(), "old", "old-hash", 1);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(postgresDocumentEditStore.save(
                eq(WORKSPACE_ID), eq(document.getId()), eq("new"), anyString(),
                eq(2L), eq("write-agent-conflict"), eq(USER_ID), eq("op-agent")))
                .thenThrow(new DocumentVersionConflictException("충돌"));
        when(applyOperationStore.consume("op-agent", USER_ID, document.getId(), "write-agent-conflict", 2L, "new"))
                .thenReturn(true);

        assertThatThrownBy(() -> documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "new", 2L,
                "write-agent-conflict", "agent", "op-agent"))
                .isInstanceOf(DocumentVersionConflictException.class);

        org.mockito.InOrder order = inOrder(applyOperationStore, postgresDocumentEditStore, operationRecorder);
        order.verify(applyOperationStore).consume(
                "op-agent", USER_ID, document.getId(), "write-agent-conflict", 2L, "new");
        order.verify(postgresDocumentEditStore).save(
                eq(WORKSPACE_ID), eq(document.getId()), eq("new"), anyString(),
                eq(2L), eq("write-agent-conflict"), eq(USER_ID), eq("op-agent"));
        verify(operationRecorder).recordConflict(
                eq("op-agent"), eq(WORKSPACE_ID), eq(USER_ID), eq(document.getId()), any());
        verify(applyOperationStore, times(2)).consume(
                "op-agent", USER_ID, document.getId(), "write-agent-conflict", 2L, "new");
    }

    @Test
    @DisplayName("수동 저장은 PostgreSQL version read model의 변경 전후 snapshot을 갱신한다")
    void saveContent_manualProjectsBeforeAndAfterSnapshots() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "original-hash");
        DocumentEditState editState = new DocumentEditState(
                document.getId(), "old", DocumentEditingRules.markdown("old").contentHash(), 1);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById(document.getId())).thenReturn(Optional.of(editState));

        documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "# 변경\n", 1L, "write_1", "   ");

        verify(contentVersionRepository).insertIfAbsent(
                eq(document.getId()), eq(1L), eq("old"), eq(editState.getContentHash()), eq(USER_ID), any());
        verify(contentVersionRepository).insertIfAbsent(
                eq(document.getId()), eq(2L), eq("# 변경\n"), anyString(), eq(USER_ID), any());
        verifyNoInteractions(applyOperationStore, operationRecorder);
    }

    @Test
    @DisplayName("version projection 실패 뒤 같은 revision_write_id 재시도로 누락 snapshot을 복구한다")
    void saveContent_projectionFailure_replayRepairsVersionReadModel() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "original-hash");
        DocumentEditState editState = new DocumentEditState(
                document.getId(), "old", DocumentEditingRules.markdown("old").contentHash(), 1);
        String resultHash = DocumentEditingRules.markdown("# 변경\n").contentHash();
        Instant updatedAt = Instant.parse("2026-08-07T00:00:00Z");
        PostgresDocumentEditSaveResult replayResult = new PostgresDocumentEditSaveResult(
                1, "old", editState.getContentHash(), 2, resultHash, updatedAt, USER_ID, true, false);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(postgresDocumentEditStore.save(
                eq(WORKSPACE_ID), eq(document.getId()), eq("# 변경\n"), eq(resultHash),
                eq(1L), eq("write_1"), eq(USER_ID), isNull()))
                .thenReturn(replayResult);
        when(contentVersionRepository.insertIfAbsent(
                document.getId(), 1L, "old", editState.getContentHash(), USER_ID, updatedAt))
                .thenReturn(1);
        when(contentVersionRepository.insertIfAbsent(
                document.getId(), 2L, "# 변경\n", resultHash, USER_ID, updatedAt))
                .thenThrow(new IllegalStateException("projection 실패"))
                .thenReturn(1);

        assertThatThrownBy(() -> documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "# 변경\n", 1L, "write_1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("projection 실패");

        DocumentContentSaveResponse recovered = documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "# 변경\n", 1L, "write_1", null);

        assertThat(recovered.currentVersion()).isEqualTo(2);
        verify(postgresDocumentEditStore, times(2)).save(
                eq(WORKSPACE_ID), eq(document.getId()), eq("# 변경\n"), eq(resultHash),
                eq(1L), eq("write_1"), eq(USER_ID), isNull());
        verify(contentVersionRepository, times(2)).insertIfAbsent(
                document.getId(), 1L, "old", editState.getContentHash(), USER_ID, updatedAt);
        verify(contentVersionRepository, times(2)).insertIfAbsent(
                document.getId(), 2L, "# 변경\n", resultHash, USER_ID, updatedAt);
    }

    @Test
    @DisplayName("일시적 unique 오류는 metadata를 포함한 전체 저장 transaction을 재시도한다")
    void saveContent_retriesWholeTransactionOnTransientUniqueFailure() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_retry", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_retry/original", "original-hash");
        DocumentEditState editState = new DocumentEditState(document.getId(), "old", "old-hash", 1);
        String resultHash = DocumentEditingRules.markdown("# 변경\n").contentHash();
        Instant updatedAt = Instant.parse("2026-08-14T00:00:00Z");
        PostgresDocumentEditSaveResult result = new PostgresDocumentEditSaveResult(
                1, "old", editState.getContentHash(), 2, resultHash, updatedAt, USER_ID, true, false);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(postgresDocumentEditStore.save(
                eq(WORKSPACE_ID), eq(document.getId()), eq("# 변경\n"), eq(resultHash),
                eq(1L), eq("write-retry-transient"), eq(USER_ID), isNull()))
                .thenReturn(result, result);
        when(contentVersionRepository.insertIfAbsent(
                document.getId(), 1L, "old", editState.getContentHash(), USER_ID, updatedAt))
                .thenThrow(new DuplicateKeyException("transient unique race"))
                .thenReturn(1);

        DocumentContentSaveResponse response = documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "# 변경\n", 1L,
                "write-retry-transient", null);

        assertThat(response.changed()).isTrue();
        verify(postgresDocumentEditStore, times(2)).save(
                eq(WORKSPACE_ID), eq(document.getId()), eq("# 변경\n"), eq(resultHash),
                eq(1L), eq("write-retry-transient"), eq(USER_ID), isNull());
        verify(contentVersionRepository).insertIfAbsent(
                document.getId(), 2L, "# 변경\n", resultHash, USER_ID, updatedAt);
        verify(documentRepository).updateCurrentContentHash(document.getId(), resultHash, updatedAt);
    }

    @Test
    @DisplayName("버전 복원은 대상 본문을 새 edit revision으로 적용한다")
    void restoreContentVersion_appliesTargetThroughEditStateStore() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "current-hash");
        DocumentEditState editState = new DocumentEditState(
                document.getId(), "current", DocumentEditingRules.markdown("current").contentHash(), 1);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(contentVersionRepository.findById(
                new fruition.core.document.domain.DocumentContentVersionId(document.getId(), 5L)))
                .thenReturn(Optional.of(new fruition.core.document.domain.DocumentContentVersion(
                        document.getId(), 5L, "# 예전\n", "old-hash", USER_ID, java.time.Instant.now())));

        DocumentContentSaveResponse response = documentService.restoreContentVersion(
                WORKSPACE_ID, USER_ID, document.getId(), 5L, 1L);

        assertThat(response.currentVersion()).isEqualTo(2);
        verify(postgresDocumentEditStore).save(
                eq(WORKSPACE_ID), eq(document.getId()), eq("# 예전\n"), anyString(),
                eq(1L), eq("restore:5:1"), eq(USER_ID), isNull());
    }

    @Test
    @DisplayName("두 콘텐츠 버전을 조회해 줄 단위 diff를 계산한다")
    void compareContentVersions_loadsSnapshotsAndCalculatesDiff() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "current-hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        fruition.core.document.domain.DocumentContentVersion before =
                new fruition.core.document.domain.DocumentContentVersion(
                        document.getId(), 1L, "이전", "old-hash", USER_ID, java.time.Instant.now());
        fruition.core.document.domain.DocumentContentVersion after =
                new fruition.core.document.domain.DocumentContentVersion(
                        document.getId(), 2L, "이후", "new-hash", USER_ID, java.time.Instant.now());
        when(contentVersionRepository.findById(
                new fruition.core.document.domain.DocumentContentVersionId(document.getId(), 1L)))
                .thenReturn(Optional.of(before));
        when(contentVersionRepository.findById(
                new fruition.core.document.domain.DocumentContentVersionId(document.getId(), 2L)))
                .thenReturn(Optional.of(after));
        DocumentContentDiffResponse expected =
                new DocumentContentDiffResponse(document.getId(), 1L, 2L, 1, 1, List.of());
        when(markdownDiffService.compare(document.getId(), 1L, "이전", 2L, "이후"))
                .thenReturn(expected);

        DocumentContentDiffResponse response = documentService.compareContentVersions(
                WORKSPACE_ID, USER_ID, document.getId(), 1L, 2L);

        assertThat(response).isEqualTo(expected);
    }

    @Test
    @DisplayName("재ingest는 편집본을 원본으로 승격하고 processing으로 되돌린다")
    void ingest_promotesEditStateAndReprocesses() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "old-hash");
        document.updateStatus(fruition.core.document.domain.DocumentStatus.completed, null, java.time.Instant.now(), null);
        DocumentEditState editState = new DocumentEditState(
                document.getId(), "# 편집본\n", DocumentEditingRules.markdown("# 편집본\n").contentHash(), 1);
        doReturn(Optional.of(document)).when(documentRepository)
                .findByIdAndWorkspaceIdForUpdate(document.getId(), WORKSPACE_ID);
        when(editStateRepository.findById(document.getId())).thenReturn(Optional.of(editState));
        when(storageProps.getBucket()).thenReturn("bucket");
        when(ingestOperationStarter.start(eq(WORKSPACE_ID), eq(USER_ID), eq(document.getId()),
                anyString(), any(Instant.class)))
                .thenReturn("op_ingest_1");

        fruition.core.document.dto.DocumentIngestResponse response =
                documentService.ingest(WORKSPACE_ID, USER_ID, document.getId());

        assertThat(response.id()).isEqualTo(document.getId());
        assertThat(response.runId()).isEqualTo(document.getPipelineRunId());
        assertThat(response.runId()).isNotBlank();
        assertThat(document.getStatus()).isEqualTo(fruition.core.document.domain.DocumentStatus.processing);
        assertThat(document.getContentHash()).isEqualTo(editState.getContentHash());
        verify(documentRepository).findByIdAndWorkspaceIdForUpdate(document.getId(), WORKSPACE_ID);
        verify(ingestCommandOutbox).enqueue(
                eq(response.runId()), eq(document.getId()), eq(USER_ID), eq(WORKSPACE_ID),
                any(), any(), any(), eq(false), eq("op_ingest_1"), anyLong(), any());
    }

    @Test
    @DisplayName("Wiki 페이지 제목에 슬래시가 있어도 이름 확정이 실패하지 않는다")
    void confirmChatExportName_sanitizesUnusableCharacters() {
        Document chatDoc = new Document(
                "chatdoc_slash", WORKSPACE_ID, USER_ID, "[채팅] 첫 질문.md", "text/markdown", 10L,
                "sources/documents/chatdoc_slash/original", "h", "chat_export");
        when(documentRepository.findActiveNormalizedFilenames(WORKSPACE_ID)).thenReturn(List.of());

        // 예외가 나면 reconciler 트랜잭션 전체가 롤백돼 채팅 후처리가 영구히 막힌다.
        documentService.confirmChatExportName(chatDoc, "CI/CD 파이프라인");

        assertThat(chatDoc.getFilename()).isEqualTo("[채팅] CI CD 파이프라인.md");
    }

    @Test
    @DisplayName("이름 확정을 두 번 해도 자기 이름과 충돌해 번호가 붙지 않는다")
    void confirmChatExportName_isIdempotent() {
        Document chatDoc = new Document(
                "chatdoc_name", WORKSPACE_ID, USER_ID, "[채팅] 첫 질문.md", "text/markdown", 10L,
                "sources/documents/chatdoc_name/original", "h", "chat_export");
        // 확정 후에는 문서 자신의 이름이 root 목록에 들어 있다.
        when(documentRepository.findActiveNormalizedFilenames(WORKSPACE_ID))
                .thenReturn(List.of("[채팅] 첫 질문.md"), List.of("[채팅] 검색 인덱싱.md"));

        documentService.confirmChatExportName(chatDoc, "검색 인덱싱");
        assertThat(chatDoc.getFilename()).isEqualTo("[채팅] 검색 인덱싱.md");

        documentService.confirmChatExportName(chatDoc, "검색 인덱싱");
        assertThat(chatDoc.getFilename()).isEqualTo("[채팅] 검색 인덱싱.md");
        verify(documentRepository, times(1)).save(chatDoc);
    }

    @Test
    @DisplayName("Wiki 페이지 제목이 폴백값이면 임시 이름을 유지한다")
    void confirmChatExportName_ignoresFallbackTitle() {
        Document chatDoc = new Document(
                "chatdoc_fb", WORKSPACE_ID, USER_ID, "[채팅] 첫 질문.md", "text/markdown", 10L,
                "sources/documents/chatdoc_fb/original", "h", "chat_export");

        documentService.confirmChatExportName(chatDoc, "Chat Export");
        documentService.confirmChatExportName(chatDoc, "  ");
        documentService.confirmChatExportName(chatDoc, null);

        assertThat(chatDoc.getFilename()).isEqualTo("[채팅] 첫 질문.md");
        verify(documentRepository, never()).save(chatDoc);
    }

    @Test
    @DisplayName("채팅 export 문서 이름에는 채팅에서 왔음을 알리는 접두사가 붙는다")
    void createChatExportDocument_prefixesName() {
        when(documentRepository.findByWorkspaceIdAndOriginAndContentHashAndSelectionModeAndDeletedAtIsNull(
                any(), any(), any(), any())).thenReturn(Optional.empty());
        when(documentRepository.findActiveNormalizedFilenames(WORKSPACE_ID)).thenReturn(List.of());
        when(documentRepository.reserveChatExport(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyLong(), any(), any(), anyString())).thenReturn(0);
        Document canonical = new Document(
                "chatdoc_x", WORKSPACE_ID, USER_ID, "[채팅] 검색 인덱싱.md", "text/markdown", 10L,
                "sources/documents/chatdoc_x/original", "h", "chat_export");
        when(documentRepository.findByWorkspaceIdAndOriginAndContentHashAndSelectionModeAndDeletedAtIsNull(
                eq(WORKSPACE_ID), eq("chat_export"), eq("hash-1"), any()))
                .thenReturn(Optional.empty(), Optional.of(canonical));

        documentService.createChatExportDocument(
                WORKSPACE_ID, USER_ID, "검색 인덱싱", "# Chat Export\n\nQ : 질문\nA : 답변\n", "hash-1",
                List.of(new DocumentService.PipelineSourceBlock("session_1:pair_1", "Q : 질문\nA : 답변")));

        ArgumentCaptor<String> filename = ArgumentCaptor.forClass(String.class);
        verify(documentRepository).reserveChatExport(
                anyString(), anyString(), anyString(), filename.capture(), anyString(), anyString(),
                anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyLong(), any(), any(), anyString());
        assertThat(filename.getValue()).isEqualTo("[채팅] 검색 인덱싱.md");
    }

    @Test
    @DisplayName("채팅 export 문서는 본문 저장을 거절한다(문답 provenance 보호)")
    void saveContent_chatExport_isRejected() {
        stubOwnedWorkspace();
        Document chatDoc = new Document(
                "chatdoc_ro", WORKSPACE_ID, USER_ID, "대화.md", "text/markdown", 4,
                "sources/documents/chatdoc_ro/original", "chat-hash", "chat_export");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(chatDoc.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(chatDoc));

        assertThatThrownBy(() -> documentService.saveContent(
                WORKSPACE_ID, USER_ID, chatDoc.getId(), "# 변경\n", 1L, "write_1", "user"))
                .isInstanceOf(InvalidMarkdownContentException.class)
                .hasMessageContaining("편집할 수 없습니다");
        // 잠금 조회조차 가지 않는다.
        verify(editLockService, never()).requireWritable(anyString(), anyString());
        verify(postgresDocumentEditStore, never()).save(
                anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(),
                anyString(), any());
    }

    @Test
    @DisplayName("완료된 채팅 export를 다시 ingest하면 출처 블록을 유지하고 후처리 상태를 초기화한다")
    void ingest_chatExport_preservesSourceBlocks() {
        stubOwnedWorkspace();
        Document chatDoc = new Document(
                "chatdoc_ro2", WORKSPACE_ID, USER_ID, "대화.md", "text/markdown", 4,
                "sources/documents/chatdoc_ro2/original", "chat-hash", "chat_export");
        chatDoc.updateStatus(fruition.core.document.domain.DocumentStatus.completed, null, Instant.now(), null);
        chatDoc.markReconciled(Instant.now());
        chatDoc.assignSelectionMode("full");
        chatDoc.assignPipelineInput("Q : 질문\nA : 답변", "[{\"block_id\":\"session_1:pair_1\",\"text\":\"문답\"}]");
        when(documentRepository.findByIdAndWorkspaceIdForUpdate(chatDoc.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(chatDoc));

        documentService.ingest(WORKSPACE_ID, USER_ID, chatDoc.getId());

        assertThat(chatDoc.getStatus()).isEqualTo(fruition.core.document.domain.DocumentStatus.processing);
        assertThat(chatDoc.getPipelineInputBlocks()).contains("session_1:pair_1");
        assertThat(chatDoc.getReconciledAt()).isNull();
        verify(ingestCommandOutbox).enqueue(
                anyString(), eq(chatDoc.getId()), eq(USER_ID), eq(WORKSPACE_ID), eq("full"),
                eq(chatDoc.getPipelineInputMarkdown()), eq(chatDoc.getPipelineInputBlocks()),
                eq(false), any(), anyLong(), any());
        verifyNoInteractions(editLockService, editStateInitializer, minioClient);

        assertThatThrownBy(() -> documentService.ingest(WORKSPACE_ID, USER_ID, chatDoc.getId()))
                .isInstanceOf(fruition.core.document.exception.DocumentAlreadyProcessingException.class);
    }

    @Test
    @DisplayName("채팅 편입은 원문과 출처 블록만 저장하고 ingest 작업을 만들지 않는다")
    void createChatExportDocument_savesWithoutIngest() throws Exception {
        when(documentRepository.findByWorkspaceIdAndOriginAndContentHashAndSelectionModeAndDeletedAtIsNull(
                any(), any(), any(), any())).thenReturn(Optional.empty());
        AtomicReference<Document> saved = new AtomicReference<>();
        when(documentRepository.reserveChatExport(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyLong(), any(), any(), anyString())).thenAnswer(invocation -> {
                    assertThat((String) invocation.getArgument(8)).isEqualTo("uploaded");
                    Document document = new Document(invocation.getArgument(0), WORKSPACE_ID, USER_ID,
                            invocation.getArgument(3), "text/markdown", 10L, invocation.getArgument(9),
                            "hash-1", "chat_export");
                    document.updateStatus(fruition.core.document.domain.DocumentStatus.uploaded, null, null, null);
                    saved.set(document);
                    return 1;
                });
        when(documentRepository.findById(anyString())).thenAnswer(invocation -> Optional.of(saved.get()));

        var result = documentService.createChatExportDocument(WORKSPACE_ID, USER_ID, "질문", "Q : 질문\nA : 답변",
                "hash-1", DELTA_BLOCKS);

        assertThat(result.skipped()).isFalse();
        assertThat(saved.get().getPipelineInputMarkdown()).isEqualTo("Q : 질문\nA : 답변");
        assertThat(saved.get().getPipelineInputBlocks()).contains("session_1:pair_1");
        assertThat(saved.get().getProcessingStartedAt()).isNull();
        verify(minioClient).putObject(any(PutObjectArgs.class));
        verifyNoInteractions(ingestCommandOutbox, ingestOperationStarter, taskWriter);
    }

    @Test
    @DisplayName("채팅 export 문서는 목록·상세에서 editable=false다")
    void chatExportDocument_isNotEditable() {
        Document chatDoc = new Document(
                "chatdoc_ro3", WORKSPACE_ID, USER_ID, "대화.md", "text/markdown", 4,
                "sources/documents/chatdoc_ro3/original", "chat-hash", "chat_export");
        Document uploaded = new Document(
                "doc_md", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_md/original", "hash");

        assertThat(DocumentItemAssembler.isEditable(chatDoc, true)).isFalse();
        assertThat(DocumentItemAssembler.isEditable(uploaded, true)).isTrue();
    }

    @Test
    @DisplayName("다른 사용자가 편집 잠금을 보유 중이면 저장을 차단한다")
    void saveContent_lockedByOther_throwsLocked() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "original-hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        org.mockito.Mockito.doThrow(new fruition.core.document.exception.DocumentLockedException("다른 사용자가 편집 중"))
                .when(editLockService).requireWritable(document.getId(), USER_ID);

        assertThatThrownBy(() -> documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "# 변경\n", 1L, "write_1", "agent"))
                .isInstanceOf(fruition.core.document.exception.DocumentLockedException.class);
        // 잠금 차단은 편집 상태 저장·스냅샷 이전에 일어난다
        verify(postgresDocumentEditStore, never()).save(
                anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(),
                anyString(), any());
        verify(contentVersionRepository, never()).insertIfAbsent(
                anyString(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("동일 Markdown 저장은 버전과 수정 시각을 변경하지 않는다")
    void saveContent_sameMarkdown_returnsNoOp() {
        stubOwnedWorkspace();
        String markdown = "# 동일\n";
        String hash = DocumentEditingRules.markdown(markdown).contentHash();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown",
                markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                "sources/documents/doc_edit/original", hash);
        DocumentEditState editState = new DocumentEditState(document.getId(), markdown, hash, 1);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(postgresDocumentEditStore.save(
                eq(WORKSPACE_ID), eq(document.getId()), eq(markdown), eq(hash),
                eq(1L), eq("write_1"), eq(USER_ID), isNull()))
                .thenReturn(new PostgresDocumentEditSaveResult(
                        1, markdown, hash, 1, hash, editState.getUpdatedAt(), USER_ID, false, false));

        DocumentContentSaveResponse response = documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), markdown, 1L, "write_1", null);

        assertThat(response.changed()).isFalse();
        assertThat(response.currentVersion()).isEqualTo(1);
        assertThat(response.updatedAt()).isEqualTo(editState.getUpdatedAt());
        verify(contentVersionRepository, never()).insertIfAbsent(
                anyString(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("오래된 본문 버전과 비소유자 저장은 거절한다")
    void saveContent_rejectsStaleVersionAndNonOwner() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        DocumentEditState editState = new DocumentEditState(document.getId(), "old", "old-hash", 1);
        when(postgresDocumentEditStore.save(
                eq(WORKSPACE_ID), eq(document.getId()), eq("new"), anyString(),
                eq(2L), eq("write_1"), eq(USER_ID), isNull()))
                .thenThrow(new DocumentVersionConflictException("충돌"));

        assertThatThrownBy(() -> documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "new", 2L, "write_1", null))
                .isInstanceOf(DocumentVersionConflictException.class);
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, "member_2");
        assertThatThrownBy(() -> documentService.saveContent(
                WORKSPACE_ID, "member_2", document.getId(), "new", 1L, "write_2", null))
                .isInstanceOf(DocumentWriteForbiddenException.class);
    }

    @Test
    @DisplayName("워크스페이스 멤버는 다른 소유자의 문서를 읽지만 변경할 수 없다")
    void workspaceMember_readsButCannotMutateOtherOwnersDocument() {
        String memberId = "member_2";
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, memberId);
        Document document = new Document(
                "doc_owned_by_other", WORKSPACE_ID, USER_ID, "공유 문서.md",
                "text/markdown", 10, null, null, "direct");
        DocumentEditState editState = new DocumentEditState(
                document.getId(), "# 공유 본문", "edit-hash", 1);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                document.getId(), WORKSPACE_ID)).thenReturn(Optional.of(document));
        when(documentRepository.findByIdAndWorkspaceIdForUpdate(
                document.getId(), WORKSPACE_ID)).thenReturn(Optional.of(document));
        when(postgresDocumentEditStore.findState(document.getId())).thenReturn(Optional.of(editState));

        DocumentDetailResponse detail =
                documentService.findById(WORKSPACE_ID, memberId, document.getId());

        assertThat(detail.markdown()).isEqualTo("# 공유 본문");
        assertThatThrownBy(() -> documentService.rename(
                WORKSPACE_ID, memberId, document.getId(),
                new DocumentRenameRequest("변경 시도", 1L)))
                .isInstanceOf(DocumentWriteForbiddenException.class);
        assertThatThrownBy(() -> documentService.delete(
                WORKSPACE_ID, memberId, document.getId(), "delete-key",
                new DocumentLifecycleRequest(1L)))
                .isInstanceOf(DocumentWriteForbiddenException.class);
        assertThatThrownBy(() -> documentService.restore(
                WORKSPACE_ID, memberId, document.getId(), "restore-key",
                new DocumentLifecycleRequest(1L)))
                .isInstanceOf(DocumentWriteForbiddenException.class);
        verify(documentRepository, never()).renameIfVersionMatches(
                anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), any());
        verify(documentRepository, never()).softDeleteIfVersionMatches(
                anyString(), anyString(), anyLong(), anyString(), any(), any());
        verify(documentRepository, never()).restoreIfVersionMatches(
                anyString(), anyString(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("이름 변경은 확장자를 유지하고 본문과 Wiki 제목을 변경하지 않는다")
    void rename_changesOnlyNotionStylePageTitle() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "기존.md", "text/markdown", 10,
                "sources/documents/doc_edit/original", "hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(documentRepository.renameIfVersionMatches(
                eq(document.getId()), eq(WORKSPACE_ID), eq(1L),
                eq("새 제목.md"), eq("새 제목"), eq("새 제목.md"), any()))
                .thenReturn(1);

        DocumentRenameResponse response = documentService.rename(
                WORKSPACE_ID,
                USER_ID,
                document.getId(),
                new DocumentRenameRequest(" 새 제목 ", 1L)
        );

        assertThat(response.changed()).isTrue();
        assertThat(response.filename()).isEqualTo("새 제목.md");
        assertThat(response.displayName()).isEqualTo("새 제목");
        assertThat(response.currentVersion()).isEqualTo(2);
        verifyNoInteractions(pipelineWikiStateRequester);
        verifyNoInteractions(editStateRepository);
    }

    @Test
    @DisplayName("동일 이름 변경은 no-op이고 오래된 버전은 거절한다")
    void rename_sameNameNoOpAndStaleVersionConflict() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "기존.md", "text/markdown", 10,
                "sources/documents/doc_edit/original", "hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));

        DocumentRenameResponse noOp = documentService.rename(
                WORKSPACE_ID, USER_ID, document.getId(), new DocumentRenameRequest("기존", 1L));

        assertThat(noOp.changed()).isFalse();
        assertThat(noOp.updatedAt()).isEqualTo(document.getUpdatedAt());
        assertThatThrownBy(() -> documentService.rename(
                WORKSPACE_ID, USER_ID, document.getId(), new DocumentRenameRequest("새 제목", 2L)))
                .isInstanceOf(DocumentVersionConflictException.class);
        verify(documentRepository, never()).renameIfVersionMatches(
                anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("최신 Markdown을 새 ID와 version 1로 같은 폴더의 마지막에 복제한다")
    void duplicate_copiesLatestMarkdownAtEndOfSameParent() {
        stubOwnedWorkspace();
        java.util.UUID folderId = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111");
        Document source = new Document(
                "doc_source", WORKSPACE_ID, USER_ID, "보고서.md", "text/markdown", 10,
                null, null, "direct");
        source.initializeDuplicate("doc_origin", folderId, "old-hash", 10, 2);
        when(folderRepository.findActiveForUpdate(folderId, WORKSPACE_ID))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(fruition.core.document.domain.Folder.class)));
        Document existingCopy = new Document(
                "doc_existing", WORKSPACE_ID, USER_ID, "보고서 복사본.md",
                "text/markdown", 10, null, null, "duplicate");
        existingCopy.initializeDuplicate("doc_source", folderId, "old-hash", 10, 3);
        DocumentEditState sourceEditState = new DocumentEditState(
                source.getId(), "# 최신 본문\n", DocumentEditingRules.markdown("# 최신 본문\n").contentHash(), 1);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(source.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(source));
        when(documentRepository.findSiblingPagesForUpdate(WORKSPACE_ID, folderId))
                .thenReturn(List.of(source, existingCopy));
        when(documentRepository.findActiveNormalizedFilenames(WORKSPACE_ID))
                .thenReturn(List.of(source.getNormalizedFilename(), existingCopy.getNormalizedFilename()));
        when(editStateRepository.findById(source.getId())).thenReturn(Optional.of(sourceEditState));

        DocumentDuplicateResponse response = documentService.duplicate(
                WORKSPACE_ID, USER_ID, source.getId(), "duplicate-key");

        assertThat(response.id()).startsWith("doc_").isNotEqualTo(source.getId());
        assertThat(response.filename()).isEqualTo("보고서 복사본 (2).md");
        assertThat(response.currentVersion()).isEqualTo(1);
        assertThat(response.folderId()).isEqualTo(folderId);
        assertThat(response.sourceDocumentId()).isEqualTo(source.getId());
        assertThat(response.sortOrder()).isEqualTo(4);

        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<DocumentEditState> editStateCaptor = ArgumentCaptor.forClass(DocumentEditState.class);
        verify(documentRepository).save(documentCaptor.capture());
        verify(editStateRepository).save(editStateCaptor.capture());
        assertThat(documentCaptor.getValue().getSourceUri())
                .isEqualTo("sources/documents/" + documentCaptor.getValue().getId() + "/original");
        assertThat(documentCaptor.getValue().getContentHash()).isNull();
        assertThat(editStateCaptor.getValue().getMarkdown()).isEqualTo("# 최신 본문\n");
        verify(assetReferenceSynchronizer).copyReferences(source.getId(), response.id());
        verify(idempotencyService).save(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("같은 복제 요청을 재시도하면 최초 문서만 반환한다")
    void duplicate_sameIdempotencyRequest_replaysFirstResult() {
        stubOwnedWorkspace();
        Document source = new Document(
                "doc_source", WORKSPACE_ID, USER_ID, "보고서.md", "text/markdown", 10,
                null, null, "direct");
        DocumentEditState sourceEditState = new DocumentEditState(
                source.getId(), "# 본문\n", DocumentEditingRules.markdown("# 본문\n").contentHash(), 1);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(source.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(source));
        when(documentRepository.findSiblingPagesForUpdate(WORKSPACE_ID, null))
                .thenReturn(List.of(source));
        when(editStateRepository.findById(source.getId())).thenReturn(Optional.of(sourceEditState));

        DocumentDuplicateResponse first = documentService.duplicate(
                WORKSPACE_ID, USER_ID, source.getId(), "same-key");
        when(idempotencyService.replay(
                eq(USER_ID), anyString(), eq("same-key"), anyString(),
                eq(DocumentDuplicateResponse.class))).thenReturn(Optional.of(first));

        DocumentDuplicateResponse replay = documentService.duplicate(
                WORKSPACE_ID, USER_ID, source.getId(), "same-key");

        assertThat(replay).isEqualTo(first);
        verify(documentRepository, times(1)).save(any(Document.class));
        verify(editStateRepository, times(1)).save(any(DocumentEditState.class));
        verify(idempotencyService, times(1)).save(
                any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("같은 멱등 키를 다른 문서 복제에 사용하면 충돌한다")
    void duplicate_sameIdempotencyKeyForDifferentDocument_conflicts() {
        stubOwnedWorkspace();
        Document firstSource = new Document(
                "doc_first", WORKSPACE_ID, USER_ID, "첫 문서.md", "text/markdown", 10,
                null, null, "direct");
        Document secondSource = new Document(
                "doc_second", WORKSPACE_ID, USER_ID, "둘째 문서.md", "text/markdown", 10,
                null, null, "direct");
        DocumentEditState firstState = new DocumentEditState(
                firstSource.getId(), "# 첫 문서", DocumentEditingRules.markdown("# 첫 문서").contentHash(), 1);
        DocumentEditState secondState = new DocumentEditState(
                secondSource.getId(), "# 둘째 문서", DocumentEditingRules.markdown("# 둘째 문서").contentHash(), 1);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(firstSource.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(firstSource));
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(secondSource.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(secondSource));
        when(documentRepository.findSiblingPagesForUpdate(WORKSPACE_ID, null))
                .thenReturn(List.of(firstSource, secondSource));
        when(editStateRepository.findById(firstSource.getId())).thenReturn(Optional.of(firstState));
        when(editStateRepository.findById(secondSource.getId())).thenReturn(Optional.of(secondState));

        documentService.duplicate(WORKSPACE_ID, USER_ID, firstSource.getId(), "reused-key");
        when(idempotencyService.replay(
                eq(USER_ID), anyString(), eq("reused-key"), anyString(),
                eq(DocumentDuplicateResponse.class)))
                .thenThrow(new IdempotencyConflictException("충돌"));

        assertThatThrownBy(() -> documentService.duplicate(
                WORKSPACE_ID, USER_ID, secondSource.getId(), "reused-key"))
                .isInstanceOf(IdempotencyConflictException.class);
        verify(documentRepository, times(1)).save(any(Document.class));
    }

    @Test
    @DisplayName("원본 자료와 다른 소유자의 문서는 복제하지 않는다")
    void duplicate_rejectsOriginalAndNonOwner() {
        stubOwnedWorkspace();
        Document original = new Document(
                "doc_pdf", WORKSPACE_ID, USER_ID, "자료.pdf", "application/pdf", 10,
                "sources/documents/doc_pdf/original", "hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(original.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(original));

        assertThatThrownBy(() -> documentService.duplicate(
                WORKSPACE_ID, USER_ID, original.getId(), "duplicate-key"))
                .isInstanceOf(DocumentWriteForbiddenException.class);

        Document otherOwner = new Document(
                "doc_other", WORKSPACE_ID, "user_other", "문서.md", "text/markdown", 10,
                null, null, "direct");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(otherOwner.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(otherOwner));

        assertThatThrownBy(() -> documentService.duplicate(
                WORKSPACE_ID, USER_ID, otherOwner.getId(), "duplicate-key-2"))
                .isInstanceOf(DocumentWriteForbiddenException.class);
        verify(documentRepository, never()).findSiblingPagesForUpdate(anyString(), any());
    }

    @Test
    @DisplayName("문서 삭제는 version을 증가시키고 원본·편집 상태를 보존한다")
    void delete_softDeletesWithoutRemovingDocumentData() throws Exception {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_delete", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 10,
                "sources/documents/doc_delete/original", "original-hash");
        when(documentRepository.findByIdAndWorkspaceIdForUpdate(
                document.getId(), WORKSPACE_ID)).thenReturn(Optional.of(document));
        when(documentRepository.softDeleteIfVersionMatches(
                eq(document.getId()), eq(WORKSPACE_ID), eq(1L), eq(USER_ID), any(), any()))
                .thenReturn(1);

        DocumentLifecycleResponse response = documentService.delete(
                WORKSPACE_ID,
                USER_ID,
                document.getId(),
                "delete-key",
                new DocumentLifecycleRequest(1L)
        );

        assertThat(response.deleted()).isTrue();
        assertThat(response.currentVersion()).isEqualTo(2);
        verify(documentRepository, never()).delete(any(Document.class));
        verify(ingestCommandOutbox, never()).enqueueDelete(anyString(), anyString());
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
        verify(idempotencyService).save(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("삭제 문서는 version이 일치하면 최상위 마지막 위치로 복구된다")
    void restore_deletedDocumentAtEndOfRoot() {
        stubOwnedWorkspace();
        Document deleted = mock(Document.class);
        when(deleted.getId()).thenReturn("doc_restore");
        when(deleted.getUserId()).thenReturn(USER_ID);
        when(deleted.getDocumentRole()).thenReturn(DocumentRole.EDITABLE);
        when(deleted.getDeletedAt()).thenReturn(java.time.Instant.now());
        Document root = new Document(
                "doc_root", WORKSPACE_ID, USER_ID, "기존.md", "text/markdown", 10,
                null, null, "direct");
        root.initializeDirectMarkdown("hash", 10, 4);
        when(documentRepository.findByIdAndWorkspaceIdForUpdate(
                deleted.getId(), WORKSPACE_ID)).thenReturn(Optional.of(deleted));
        when(documentRepository.findRootItemsForUpdate(WORKSPACE_ID, DocumentRole.EDITABLE))
                .thenReturn(List.of(root));
        when(documentRepository.restoreIfVersionMatches(
                eq(deleted.getId()), eq(WORKSPACE_ID), eq(2L), eq(5L), any()))
                .thenReturn(1);

        DocumentLifecycleResponse response = documentService.restore(
                WORKSPACE_ID,
                USER_ID,
                deleted.getId(),
                "restore-key",
                new DocumentLifecycleRequest(2L)
        );

        assertThat(response.deleted()).isFalse();
        assertThat(response.currentVersion()).isEqualTo(3);
        assertThat(response.sortOrder()).isEqualTo(5);
    }

    @Test
    @DisplayName("휴지통은 삭제 문서만 삭제 시각 역순으로 반환한다")
    void trash_returnsDeletedDocuments() {
        stubOwnedWorkspace();
        Document deleted = mock(Document.class);
        when(deleted.getId()).thenReturn("doc_deleted");
        when(deleted.getFilename()).thenReturn("삭제 문서.md");
        when(deleted.getDisplayName()).thenReturn("삭제 문서");
        when(deleted.getDocumentRole()).thenReturn(DocumentRole.EDITABLE);
        when(deleted.getCurrentVersion()).thenReturn(2L);
        when(deleted.getDeletedAt()).thenReturn(java.time.Instant.now());
        when(deleted.getDeletedBy()).thenReturn(USER_ID);
        when(documentRepository.findAllByWorkspaceIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
                WORKSPACE_ID)).thenReturn(List.of(deleted));

        DocumentTrashResponse response = documentService.trash(WORKSPACE_ID, USER_ID);

        assertThat(response.documents()).hasSize(1);
        assertThat(response.documents().get(0).id()).isEqualTo("doc_deleted");
        assertThat(response.documents().get(0).currentVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("MinIO 업로드 실패 시 문서와 멱등 기록을 저장하지 않는다")
    void upload_minioFailure_leavesNoDatabaseState() throws Exception {
        stubOwnedWorkspace();
        when(storageProps.getBucket()).thenReturn("test-bucket");
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(new RuntimeException("storage failure"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "자료.pdf", "application/pdf", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> documentService.upload(WORKSPACE_ID, USER_ID, "upload-key", null, file))
                .isInstanceOf(DocumentUploadException.class);

        verify(documentRepository, never()).save(any(Document.class));
        verify(editStateRepository, never()).save(any(DocumentEditState.class));
        verify(idempotencyService, never()).save(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("MinIO 저장 후 DB 실패 시 업로드 객체를 보상 삭제한다")
    void upload_databaseFailure_removesStoredObject() throws Exception {
        stubOwnedWorkspace();
        when(storageProps.getBucket()).thenReturn("test-bucket");
        when(documentRepository.save(any(Document.class))).thenThrow(new RuntimeException("database failure"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "자료.pdf", "application/pdf", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> documentService.upload(WORKSPACE_ID, USER_ID, "upload-key", null, file))
                .isInstanceOf(DocumentUploadException.class);

        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
        verify(idempotencyService, never()).save(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void upload_reclaimedExecutionUsesDifferentObjectKeySoOldCleanupCannotDeleteNewObject() throws Exception {
        stubOwnedWorkspace();
        UUID oldExecutionId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        UUID newExecutionId = UUID.fromString("88888888-8888-8888-8888-888888888888");
        when(idempotencyService.currentExecutionId())
                .thenReturn(Optional.of(oldExecutionId), Optional.of(newExecutionId));
        when(storageProps.getBucket()).thenReturn("test-bucket");
        when(documentRepository.save(any(Document.class)))
                .thenThrow(new RuntimeException("database failure"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        MockMultipartFile file = new MockMultipartFile(
                "file", "자료.pdf", "application/pdf", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> documentService.upload(
                WORKSPACE_ID, USER_ID, "upload-key", null, file))
                .isInstanceOf(DocumentUploadException.class);
        DocumentUploadResponse retried = documentService.upload(
                WORKSPACE_ID, USER_ID, "upload-key", null, file);

        ArgumentCaptor<PutObjectArgs> puts = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient, times(2)).putObject(puts.capture());
        assertThat(puts.getAllValues())
                .extracting(PutObjectArgs::object)
                .containsExactly(
                        "sources/documents/doc_77777777777777777777777777777777/original",
                        "sources/documents/doc_88888888888888888888888888888888/original");
        assertThat(retried.id()).isEqualTo("doc_88888888888888888888888888888888");
        ArgumentCaptor<RemoveObjectArgs> cleanup = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minioClient).removeObject(cleanup.capture());
        assertThat(cleanup.getValue().object())
                .isEqualTo("sources/documents/doc_77777777777777777777777777777777/original");
    }

    @Test
    @DisplayName("chat_export 문서도 일반 document ingest로 라우팅한다")
    void enqueueIngest_chatExport_routesGenericDocumentIngest() {
        Document chatDoc = new Document("chatdoc_1", WORKSPACE_ID, USER_ID, "c.md", "text/markdown", 10L,
                "sources/documents/chatdoc_1/original", "h_chat", "chat_export");
        chatDoc.assignSelectionMode("full");
        when(ingestOperationStarter.start(eq(WORKSPACE_ID), eq(USER_ID), eq("chatdoc_1"),
                anyString(), any(Instant.class))).thenReturn("op_ingest_1");
        documentService.enqueueIngest(chatDoc);

        ArgumentCaptor<Boolean> chatWiki = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> runId = ArgumentCaptor.forClass(String.class);
        verify(ingestCommandOutbox).enqueue(runId.capture(), eq("chatdoc_1"), eq(USER_ID), eq(WORKSPACE_ID),
                eq("full"), any(), any(), chatWiki.capture(), eq("op_ingest_1"), anyLong(), any());
        assertThat(chatWiki.getValue()).isFalse();
        assertThat(chatDoc.getPipelineRunId()).isEqualTo(runId.getValue());
    }

    @Test
    @DisplayName("일반 업로드 문서는 chatWiki=false로 요청한다")
    void enqueueIngest_upload_routesGeneric() {
        Document doc = new Document("doc_up", WORKSPACE_ID, USER_ID, "u.pdf", "application/pdf", 10L,
                "sources/documents/doc_up/original", "h_up"); // origin 기본값 "upload"
        when(ingestOperationStarter.start(eq(WORKSPACE_ID), eq(USER_ID), eq("doc_up"),
                anyString(), any(Instant.class))).thenReturn("op_ingest_1");
        documentService.enqueueIngest(doc);

        ArgumentCaptor<Boolean> chatWiki = ArgumentCaptor.forClass(Boolean.class);
        verify(ingestCommandOutbox).enqueue(anyString(), any(), any(), any(), any(), any(), any(),
                chatWiki.capture(), any(), anyLong(), any());
        assertThat(chatWiki.getValue()).isFalse();
    }

}
