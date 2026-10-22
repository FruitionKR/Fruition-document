package fruition.core.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentConvertQueue;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.dto.DocumentUploadResponse;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.exception.InvalidDocumentConvertRequestException;
import fruition.core.document.repository.PostgresDocumentEditSaveResult;
import fruition.core.document.repository.PostgresDocumentEditStore;
import fruition.core.document.repository.ConvertCommandOutbox;
import fruition.core.document.repository.DocumentConvertBatchStore;
import fruition.core.document.repository.DocumentConvertQueueRepository;
import fruition.core.document.repository.DocumentContentVersionRepository;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import fruition.core.document.repository.IngestCommandOutbox;
import fruition.shared.idempotency.IdempotencyConflictException;
import fruition.shared.idempotency.IdempotencyService;
import fruition.shared.util.StorageProperties;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceAiModelClient;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import fruition.core.document.exception.DocumentAlreadyProcessingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceConvertTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";
    private static final String SOURCE_DOCUMENT_ID = "doc_source_pdf";

    @Mock DocumentRepository documentRepository;
    @Mock FolderRepository folderRepository;
    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock MinioClient minioClient;
    @Mock StorageProperties storageProps;
    @Mock IngestCommandOutbox ingestCommandOutbox;
    @Mock DocumentWikiRetirement documentWikiRetirement;
    @Mock PipelineWikiStateRequester pipelineWikiStateRequester;
    @Mock DocumentConvertQueueRepository convertQueueRepository;
    @Mock fruition.core.aitask.repository.PipelineTaskCancellationClient pipelineCancellation;
    @Mock TransactionTemplate transactionTemplate;
    @Mock DocumentEditStateInitializer editStateInitializer;
    @Mock DocumentEditStateRepository editStateRepository;
    @Mock PostgresDocumentEditStore postgresDocumentEditStore;
    @Mock DocumentContentVersionRepository contentVersionRepository;
    @Mock MarkdownDiffService markdownDiffService;
    @Mock DocumentEditLockService editLockService;
    @Mock IdempotencyService idempotencyService;
    @Mock DocumentAssetReferenceSynchronizer assetReferenceSynchronizer;
    @Mock DocumentAssetReferenceParser assetReferenceParser;
    @Mock fruition.core.document.repository.DocumentAssetRepository assetRepository;
    @Mock fruition.core.aihistory.service.OperationRecorder operationRecorder;
    @Mock fruition.core.aihistory.service.IngestOperationStarter ingestOperationStarter;
    @Mock fruition.core.aihistory.service.AgentApplyOperationStore applyOperationStore;
    @Mock WorkspaceAiModelClient workspaceAiModelClient;
    @Mock fruition.core.authz.WorkspaceSettingsClient workspaceSettingsClient;

    DocumentService documentService;
    DocumentAccessPolicy accessPolicy;

    private final fruition.core.document.repository.AiCommandOutboxWriter taskWriter =
            org.mockito.Mockito.mock(fruition.core.document.repository.AiCommandOutboxWriter.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final FakeBatchStore batchStore = new FakeBatchStore();

    @BeforeEach
    void setUp() {
        // DocumentAccessPolicy.viewer가 역할·권한을 한 번에 읽는다. 기본은 권한 항목 없는 MEMBER.
        org.mockito.Mockito.lenient().when(workspaceAccessGuard.access(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new fruition.core.authz.WorkspaceAccessGuard.Access("MEMBER", java.util.List.of()));
        accessPolicy = new DocumentAccessPolicy(workspaceAccessGuard, jdbcTemplate);
        org.mockito.Mockito.lenient().when(applyOperationStore.authorizeSave(anyString(), anyString(), anyString())).thenReturn(true);
        org.mockito.Mockito.lenient().when(taskWriter.active(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        org.mockito.Mockito.lenient().when(taskWriter.join(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        documentService = new DocumentService(documentRepository, folderRepository,
                workspaceAccessGuard, minioClient, storageProps,
                ingestCommandOutbox, pipelineWikiStateRequester,
                convertQueueRepository, batchStore,
                new ConvertCommandOutbox(taskWriter, mapper, "ai.convert.command"), pipelineCancellation, transactionTemplate,
                editStateInitializer, editStateRepository, new DocumentItemAssembler(editStateRepository, accessPolicy, contentVersionRepository),
                postgresDocumentEditStore,
                contentVersionRepository, markdownDiffService,
                editLockService, idempotencyService,
                assetReferenceSynchronizer,
                assetReferenceParser, assetRepository,
                new ObjectMapper().findAndRegisterModules(),
                applyOperationStore,
                operationRecorder,
                ingestOperationStarter,
                workspaceAiModelClient, workspaceSettingsClient, taskWriter, documentWikiRetirement, accessPolicy,
                java.time.Duration.ofDays(30), 10, 3);
        // 변환 placeholder도 생성 시점에 원본을 object storage에 쓴다.
        lenient().when(storageProps.getBucket()).thenReturn("fruition-storage");
        // 단위 테스트에서는 transactionTemplate이 콜백을 그대로 실행하게 한다.
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(null));
        PlatformTransactionManager transactionManager = Mockito.mock(PlatformTransactionManager.class);
        lenient().when(transactionTemplate.getTransactionManager()).thenReturn(transactionManager);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(Mockito.mock(TransactionStatus.class));
        lenient().when(idempotencyService.replay(
                anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        lenient().doCallRealMethod().when(idempotencyService).validateKey(any());
        lenient().doCallRealMethod().when(idempotencyService).requestHash(any(String[].class));
        lenient().when(idempotencyService.currentExecutionId()).thenReturn(Optional.empty());
        lenient().when(convertQueueRepository.save(any())).thenAnswer(inv -> {
            DocumentConvertQueue saved = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(saved, "id", 7L);
            return saved;
        });
        lenient().when(workspaceAiModelClient.get(WORKSPACE_ID))
                .thenReturn(new WorkspaceAiModelClient.AiModelSelection(
                        "gemini", "gemini-3.5-flash-lite"));
    }

    private Document sourcePdf() {
        return new Document(SOURCE_DOCUMENT_ID, WORKSPACE_ID, USER_ID, "보고서.pdf",
                "application/pdf", 1234L, "sources/documents/" + SOURCE_DOCUMENT_ID + "/original", "pdf-hash");
    }

    private void stubOwnedWorkspace() {
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** 워크스페이스 자동 편입 설정과 요청자의 wiki.ingest 권한을 stub한다. */
    private void enableAutoIngest(boolean permission) {
        when(workspaceSettingsClient.autoIngestOnUpload(WORKSPACE_ID)).thenReturn(true);
        when(workspaceAccessGuard.hasPermission(WORKSPACE_ID, USER_ID, "wiki.ingest")).thenReturn(permission);
    }

    @Test
    @DisplayName("변환 완료 시 설정이 꺼져 있으면(조회 실패 포함) 편입하지 않고 문서는 completed로 남는다")
    void batchConvert_settingOff_doesNotIngest() throws Exception {
        startScenario(PLACEHOLDER_MARKDOWN);
        seedDispatched(0);
        stubResult(0, "<!-- page 1 -->\n\n# 본문\n");

        documentService.applyConvertBatch(succeeded(0, 3, 3));

        verify(ingestCommandOutbox, never()).begin(anyString(), anyString(), anyString());
        assertThat(parent.getStatus()).isEqualTo(DocumentStatus.completed);
    }

    @Test
    @DisplayName("변환 완료 시 설정이 켜져 있어도 요청자에게 wiki.ingest가 없으면(변환 도중 회수 포함) 편입하지 않는다")
    void batchConvert_withoutPermission_doesNotIngest() throws Exception {
        enableAutoIngest(false);
        startScenario(PLACEHOLDER_MARKDOWN);
        seedDispatched(0);
        stubResult(0, "<!-- page 1 -->\n\n# 본문\n");

        documentService.applyConvertBatch(succeeded(0, 3, 3));

        verify(ingestCommandOutbox, never()).begin(anyString(), anyString(), anyString());
        assertThat(parent.getStatus()).isEqualTo(DocumentStatus.completed);
    }


    @Test
    @DisplayName("남이 올린 PDF 변환은 document.convert 권한(또는 OWNER)이 있어야 하고, 없으면 WorkspacePermissionRequiredException")
    void convertToMarkdown_otherUsersPdf_requiresConvertPermission() {
        stubOwnedWorkspace();
        Document othersPdf = new Document(SOURCE_DOCUMENT_ID, WORKSPACE_ID, "user_other", "보고서.pdf",
                "application/pdf", 1234L, "sources/documents/" + SOURCE_DOCUMENT_ID + "/original", "pdf-hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(SOURCE_DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(othersPdf));
        when(workspaceAccessGuard.hasPermission(WORKSPACE_ID, USER_ID, "document.convert")).thenReturn(false);

        assertThatThrownBy(() -> documentService.convertToMarkdown(
                WORKSPACE_ID, USER_ID, SOURCE_DOCUMENT_ID, "convert-key"))
                .isInstanceOf(fruition.core.authz.WorkspacePermissionRequiredException.class)
                .hasMessageContaining("document.convert");
        verify(documentRepository, never()).save(any());
    }

    @Test
    @DisplayName("남이 올린 PDF라도 document.convert 권한자(OWNER 포함)는 변환을 요청할 수 있다")
    void convertToMarkdown_otherUsersPdf_withPermission_allowed() {
        stubOwnedWorkspace();
        Document othersPdf = new Document(SOURCE_DOCUMENT_ID, WORKSPACE_ID, "user_other", "보고서.pdf",
                "application/pdf", 1234L, "sources/documents/" + SOURCE_DOCUMENT_ID + "/original", "pdf-hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(SOURCE_DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(othersPdf));
        when(workspaceAccessGuard.hasPermission(WORKSPACE_ID, USER_ID, "document.convert")).thenReturn(true);
        when(documentRepository.findMaxRootSortOrder(WORKSPACE_ID, DocumentRole.EDITABLE)).thenReturn(0L);

        DocumentUploadResponse response = documentService.convertToMarkdown(
                WORKSPACE_ID, USER_ID, SOURCE_DOCUMENT_ID, "convert-key");

        assertThat(response.status()).isEqualTo(DocumentStatus.processing);
        verify(convertQueueRepository).save(any());
    }

    @Test
    @DisplayName("목록의 can_convert는 변환 요청이 받아들여지는지와 항상 같다")
    void canConvert_matchesConvertEndpoint() {
        stubOwnedWorkspace();
        lenient().when(documentRepository.findMaxRootSortOrder(WORKSPACE_ID, DocumentRole.EDITABLE)).thenReturn(0L);
        String sourceKey = "sources/documents/" + SOURCE_DOCUMENT_ID + "/original";
        record Case(String name, Document document, boolean convertPermission, boolean expected) {}
        java.util.List<Case> cases = java.util.List.of(
                new Case("업로더 본인", new Document(SOURCE_DOCUMENT_ID, WORKSPACE_ID, USER_ID, "보고서.pdf",
                        "application/pdf", 1L, sourceKey, "h"), false, true),
                new Case("권한 없는 타인", new Document(SOURCE_DOCUMENT_ID, WORKSPACE_ID, "user_other", "보고서.pdf",
                        "application/pdf", 1L, sourceKey, "h"), false, false),
                new Case("document.convert 권한자(OWNER 포함)", new Document(SOURCE_DOCUMENT_ID, WORKSPACE_ID,
                        "user_other", "보고서.pdf", "application/pdf", 1L, sourceKey, "h"), true, true),
                new Case("Markdown 문서", new Document(SOURCE_DOCUMENT_ID, WORKSPACE_ID, USER_ID, "메모.md",
                        "text/markdown", 1L, sourceKey, "h"), true, false),
                new Case("원본 파일 없는 PDF", new Document(SOURCE_DOCUMENT_ID, WORKSPACE_ID, USER_ID, "보고서.pdf",
                        "application/pdf", 1L, null, "h"), true, false));

        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            // 업로더 본인처럼 변환 요청이 권한을 묻지 않는 경우도 있어 lenient로 둔다.
            lenient().when(workspaceAccessGuard.hasPermission(WORKSPACE_ID, USER_ID, "document.convert"))
                    .thenReturn(c.convertPermission());
            when(workspaceAccessGuard.access(WORKSPACE_ID, USER_ID)).thenReturn(new fruition.core.authz.WorkspaceAccessGuard.Access(
                    "MEMBER", c.convertPermission() ? java.util.List.of("document.convert") : java.util.List.of()));
            when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(SOURCE_DOCUMENT_ID, WORKSPACE_ID))
                    .thenReturn(Optional.of(c.document()));

            boolean listed = accessPolicy.viewer(WORKSPACE_ID, USER_ID).canConvert(c.document());
            boolean accepted;
            try {
                documentService.convertToMarkdown(WORKSPACE_ID, USER_ID, SOURCE_DOCUMENT_ID, "convert-key-" + i);
                accepted = true;
            } catch (InvalidDocumentConvertRequestException | fruition.core.authz.WorkspacePermissionRequiredException e) {
                accepted = false;
            }

            assertThat(listed).as(c.name()).isEqualTo(c.expected());
            assertThat(accepted).as(c.name()).isEqualTo(listed);
        }
    }

    @Test
    @DisplayName("PDF 원본 변환 요청은 processing 상태의 placeholder Markdown 문서를 만들고 변환 큐에 등록한다")
    void convertToMarkdown_pdfSource_createsPlaceholderAndEnqueues() {
        stubOwnedWorkspace();
        Document source = sourcePdf();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(SOURCE_DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(source));
        when(documentRepository.findMaxRootSortOrder(WORKSPACE_ID, DocumentRole.EDITABLE)).thenReturn(2L);

        DocumentUploadResponse response = documentService.convertToMarkdown(
                WORKSPACE_ID, USER_ID, SOURCE_DOCUMENT_ID, "convert-key");

        ArgumentCaptor<Document> savedDocument = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(savedDocument.capture());
        Document placeholder = savedDocument.getValue();
        assertThat(placeholder.getFilename()).isEqualTo("보고서.md");
        assertThat(placeholder.getDisplayName()).isEqualTo("보고서");
        assertThat(placeholder.getMimeType()).isEqualTo("text/markdown");
        assertThat(placeholder.getDocumentRole()).isEqualTo(DocumentRole.EDITABLE);
        assertThat(placeholder.getStatus()).isEqualTo(DocumentStatus.processing);
        assertThat(placeholder.getSourceDocumentId()).isEqualTo(SOURCE_DOCUMENT_ID);
        assertThat(placeholder.getSortOrder()).isEqualTo(3L);
        assertThat(placeholder.getOrigin()).isEqualTo("convert");

        ArgumentCaptor<DocumentEditState> savedEditState = ArgumentCaptor.forClass(DocumentEditState.class);
        verify(editStateRepository).save(savedEditState.capture());
        assertThat(savedEditState.getValue().getDocumentId()).isEqualTo(placeholder.getId());
        assertThat(savedEditState.getValue().getMarkdown()).contains("PDF 변환 중");

        ArgumentCaptor<DocumentConvertQueue> savedQueue = ArgumentCaptor.forClass(DocumentConvertQueue.class);
        verify(convertQueueRepository).save(savedQueue.capture());
        assertThat(savedQueue.getValue().getDocumentId()).isEqualTo(placeholder.getId());
        assertThat(savedQueue.getValue().getSourceDocumentId()).isEqualTo(SOURCE_DOCUMENT_ID);
        assertThat(savedQueue.getValue().getStatus()).isEqualTo("processing");

        verify(idempotencyService).save(
                eq(USER_ID), anyString(), eq("convert-key"), anyString(),
                eq(201), anyString(), any());
        assertThat(response.id()).isEqualTo(placeholder.getId());
        assertThat(response.status()).isEqualTo(DocumentStatus.processing);
        assertThat(response.editable()).isTrue();
        assertThat(response.documentRole()).isEqualTo(DocumentRole.EDITABLE);
    }

    @Test
    @DisplayName("PDF 원본이 아니면 InvalidDocumentConvertRequestException을 던진다")
    void convertToMarkdown_nonPdf_throws() {
        stubOwnedWorkspace();
        Document markdownDocument = new Document("doc_md", WORKSPACE_ID, USER_ID, "노트.md",
                "text/markdown", 10L, null, null);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_md", WORKSPACE_ID))
                .thenReturn(Optional.of(markdownDocument));

        assertThatThrownBy(() -> documentService.convertToMarkdown(
                WORKSPACE_ID, USER_ID, "doc_md", "convert-key"))
                .isInstanceOf(InvalidDocumentConvertRequestException.class);
        verify(documentRepository, never()).save(any());
    }

    @Test
    @DisplayName("문서가 없으면 DocumentNotFoundException을 던진다")
    void convertToMarkdown_unknownDocument_throws() {
        stubOwnedWorkspace();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_unknown", WORKSPACE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.convertToMarkdown(
                WORKSPACE_ID, USER_ID, "doc_unknown", "convert-key"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    @DisplayName("같은 Idempotency-Key 재요청은 저장된 응답을 재생하고 새 문서를 만들지 않는다")
    void convertToMarkdown_idempotentReplay_returnsStoredResponse() throws Exception {
        stubOwnedWorkspace();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(SOURCE_DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(sourcePdf()));
        Instant now = Instant.now();
        DocumentUploadResponse stored = new DocumentUploadResponse(
                "doc_replay", "보고서.md", "text/markdown", 10,
                DocumentStatus.processing, null, now, true, 1, DocumentRole.EDITABLE, null);
        when(idempotencyService.replay(
                eq(USER_ID), anyString(), eq("convert-key"), anyString(),
                eq(DocumentUploadResponse.class))).thenReturn(Optional.of(stored));

        DocumentUploadResponse response = documentService.convertToMarkdown(
                WORKSPACE_ID, USER_ID, SOURCE_DOCUMENT_ID, "convert-key");

        assertThat(response.id()).isEqualTo("doc_replay");
        verify(documentRepository, never()).save(any());
        verify(convertQueueRepository, never()).save(any());
    }

    @Test
    @DisplayName("같은 Idempotency-Key를 다른 요청에 쓰면 IdempotencyConflictException을 던진다")
    void convertToMarkdown_idempotencyKeyReuse_throwsConflict() {
        stubOwnedWorkspace();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(SOURCE_DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(sourcePdf()));
        when(idempotencyService.replay(
                eq(USER_ID), anyString(), eq("convert-key"), anyString(),
                eq(DocumentUploadResponse.class)))
                .thenThrow(new IdempotencyConflictException("충돌"));

        assertThatThrownBy(() -> documentService.convertToMarkdown(
                WORKSPACE_ID, USER_ID, SOURCE_DOCUMENT_ID, "convert-key"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    private static final String PLACEHOLDER_MARKDOWN = "PDF 변환 중...\n";

    /** 메모리 batch 저장소. 운영 저장소와 같은 규칙(이미 결과가 온 batch의 중복 거절)을 따른다. */
    static class FakeBatchStore extends DocumentConvertBatchStore {
        final java.util.TreeMap<Integer, Batch> rows = new java.util.TreeMap<>();

        FakeBatchStore() { super(null); }

        @Override public void insertDispatched(long queueId, int startPage, int pageCount, String command) {
            rows.put(startPage, new Batch(queueId, startPage, pageCount, command, DISPATCHED, null, null));
        }
        @Override public Optional<Batch> find(long queueId, int startPage) { return Optional.ofNullable(rows.get(startPage)); }
        @Override public boolean markReady(long queueId, int startPage, int pageEnd, String resultKey) {
            var row = rows.get(startPage);
            if (row == null || !DISPATCHED.equals(row.status())) return false;
            rows.put(startPage, new Batch(queueId, startPage, row.pageCount(), row.command(), READY, pageEnd, resultKey));
            return true;
        }
        @Override public void delete(long queueId, int startPage) { rows.remove(startPage); }
        @Override public int count(long queueId) { return rows.size(); }
        @Override public java.util.List<Batch> findDispatched(long queueId) {
            return rows.values().stream().filter(row -> DISPATCHED.equals(row.status())).toList();
        }
        @Override public java.util.List<Batch> findDispatchedByDocument(String documentId) { return findDispatched(0); }
    }

    private Document parent;
    private DocumentConvertQueue queue;
    private java.util.Map<String, DocumentEditState> states;
    private java.util.Map<String, Document> rows;

    /** 변환 중인 문서 하나의 상태를 메모리로 흉내 낸다. 편집 상태는 문서 ID별 map이다. */
    private void startScenario(String initialMarkdown) {
        parent = placeholderDocument();
        var source = sourcePdf();
        queue = new DocumentConvertQueue(parent.getId(), source.getId());
        org.springframework.test.util.ReflectionTestUtils.setField(queue, "id", 7L);
        batchStore.rows.clear();
        states = new java.util.HashMap<>();
        states.put(parent.getId(), new DocumentEditState(parent.getId(), initialMarkdown, "hash", 1));
        rows = new java.util.LinkedHashMap<>();
        rows.put(parent.getId(), parent);
        rows.put(source.getId(), source);
        lenient().when(convertQueueRepository.findByDocumentIdForUpdate(parent.getId())).thenReturn(Optional.of(queue));
        lenient().when(documentRepository.findByIdInActiveWorkspace(parent.getId())).thenReturn(Optional.of(parent));
        lenient().when(documentRepository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.getArgument(0))));
        // 시나리오를 다시 시작해도 이전 stub의 answer를 호출하지 않도록 doAnswer로 덮어쓴다.
        lenient().doAnswer(inv -> { Document doc = inv.getArgument(0); rows.put(doc.getId(), doc); return doc; })
                .when(documentRepository).save(any());
        lenient().when(documentRepository.findByIdAndWorkspaceIdForUpdate(anyString(), eq(WORKSPACE_ID)))
                .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.getArgument(0))));
        lenient().when(documentRepository.findConvertedParts(WORKSPACE_ID, parent.getId())).thenAnswer(inv -> rows.values().stream()
                .filter(doc -> "convert_part".equals(doc.getOrigin())).toList());
        lenient().when(editStateRepository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(states.get(inv.<String>getArgument(0))));
        lenient().doAnswer(inv -> {
            DocumentEditState state = inv.getArgument(0); states.put(state.getDocumentId(), state); return state; })
                .when(editStateRepository).save(any());
        lenient().doAnswer(inv -> {
                    long base = inv.<Long>getArgument(4);
                    states.put(inv.getArgument(1), new DocumentEditState(inv.getArgument(1), inv.getArgument(2),
                            inv.getArgument(3), base + 1));
                    return new fruition.core.document.repository.PostgresDocumentEditSaveResult(
                            base, "", "", base + 1, inv.getArgument(3), Instant.now(), inv.getArgument(6), true, false);
                }).when(postgresDocumentEditStore).save(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyString(), any());
    }

    /** 이미 발행해 결과를 기다리는 batch를 만든다(발행 때 outbox에 넣은 command와 같은 모양). */
    private void seedDispatched(int... startPages) {
        for (int startPage : startPages) {
            batchStore.insertDispatched(7, startPage, 10, "{\"run_id\":\"convert:doc_placeholder:" + startPage
                    + "\",\"parent_run_id\":\"convert:doc_placeholder\",\"kind\":\"convert_batch\",\"workspace_id\":\""
                    + WORKSPACE_ID + "\",\"user_id\":\"" + USER_ID + "\",\"document_id\":\"doc_placeholder\","
                    + "\"source_key\":\"sources/documents/doc_source_pdf/original\",\"start_page\":" + startPage
                    + ",\"page_count\":10,\"provider\":\"gemini\",\"model\":\"gemini-3.5-flash-lite\"}");
            queue.dispatchedUpTo(Math.max(queue.getNextStartPage(), startPage + 10));
        }
    }

    private static String resultKey(int startPage) {
        return "tmp/convert/" + WORKSPACE_ID + "/doc_placeholder/" + startPage + ".md";
    }

    /** converter worker가 storage에 쓴 batch 결과를 흉내 낸다. */
    private void stubResult(int startPage, String markdown) throws Exception {
        String key = resultKey(startPage);
        lenient().when(minioClient.getObject(org.mockito.ArgumentMatchers.argThat(
                (io.minio.GetObjectArgs args) -> args != null && key.equals(args.object()))))
                .thenAnswer(inv -> new GetObjectResponse(Headers.of(), "fruition-storage", "us-east-1", key,
                        new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8))));
    }

    private com.fasterxml.jackson.databind.JsonNode succeeded(int startPage, int pageEnd, int total) throws Exception {
        return mapper.readTree("{\"run_id\":\"convert:doc_placeholder:" + startPage + "\",\"kind\":\"convert_batch\","
                + "\"status\":\"succeeded\",\"payload\":{\"document_id\":\"doc_placeholder\",\"page_start\":" + (startPage + 1)
                + ",\"page_end\":" + pageEnd + ",\"total_pages\":" + total + ",\"result_key\":\"" + resultKey(startPage) + "\"}}");
    }

    private com.fasterxml.jackson.databind.JsonNode failedEvent(int startPage, String error) throws Exception {
        return mapper.readTree("{\"run_id\":\"convert:doc_placeholder:" + startPage + "\",\"kind\":\"convert_batch\","
                + "\"status\":\"failed\",\"error\":\"" + error + "\"}");
    }

    private java.util.List<String> dispatchedRunIds() {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> runId = ArgumentCaptor.forClass(String.class);
        verify(taskWriter, org.mockito.Mockito.atLeast(0)).enqueueJoined(runId.capture(), eq("ai.convert.command"), anyString(), payload.capture());
        return runId.getAllValues();
    }

    @Test
    @DisplayName("변환 요청은 첫 batch command 하나를 계약 필드 그대로 발행하고 Kafka key는 run_id이며 batch마다 예약하지 않는다")
    void requestPublishesFirstBatchCommand() throws Exception {
        stubOwnedWorkspace();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(SOURCE_DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(sourcePdf()));

        DocumentUploadResponse response = documentService.convertToMarkdown(
                WORKSPACE_ID, USER_ID, SOURCE_DOCUMENT_ID, "convert-key");

        String placeholderId = response.id();
        String runId = "convert:" + placeholderId + ":0";
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(taskWriter, times(1)).enqueueJoined(eq(runId), eq("ai.convert.command"), eq(runId), payload.capture());
        var command = mapper.readTree(payload.getValue());
        assertThat(mapper.convertValue(command, java.util.Map.class)).isEqualTo(java.util.Map.ofEntries(
                java.util.Map.entry("run_id", runId),
                java.util.Map.entry("parent_run_id", "convert:" + placeholderId),
                java.util.Map.entry("kind", "convert_batch"),
                java.util.Map.entry("workspace_id", WORKSPACE_ID),
                java.util.Map.entry("user_id", USER_ID),
                java.util.Map.entry("document_id", placeholderId),
                java.util.Map.entry("source_key", "sources/documents/" + SOURCE_DOCUMENT_ID + "/original"),
                java.util.Map.entry("start_page", 0),
                java.util.Map.entry("page_count", 10),
                java.util.Map.entry("provider", "gemini"),
                java.util.Map.entry("model", "gemini-3.5-flash-lite")));
        assertThat(batchStore.rows.get(0).command()).isEqualTo(payload.getValue());
        // 예약은 변환 run 하나만 한다. batch는 run 등록·예약을 만드는 enqueue/begin/reserve를 거치지 않는다.
        verify(taskWriter, times(1)).begin(eq("convert:" + placeholderId), eq(WORKSPACE_ID), eq(USER_ID), eq("convert"));
        verify(taskWriter, never()).reserve(anyString(), anyString(), anyString(), anyString());
        verify(taskWriter, never()).enqueue(anyString(), anyString(), anyString(), any());
        assertThat(queue0().getNextStartPage()).isEqualTo(10);
    }

    private DocumentConvertQueue queue0() {
        ArgumentCaptor<DocumentConvertQueue> saved = ArgumentCaptor.forClass(DocumentConvertQueue.class);
        verify(convertQueueRepository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("첫 결과로 전체 쪽수를 알면 batch를 최대 3개까지 더 발행하고 전체 쪽수 이상은 발행하지 않는다")
    void firstResultDispatchesUpToThreeMore() throws Exception {
        startScenario(PLACEHOLDER_MARKDOWN);
        seedDispatched(0);
        stubResult(0, "<!-- page 1 -->\n\n# p1\n");

        documentService.applyConvertBatch(succeeded(0, 10, 100));

        assertThat(queue.getCompletedPages()).isEqualTo(10);
        assertThat(queue.getTotalPages()).isEqualTo(100);
        assertThat(batchStore.rows.keySet()).containsExactly(10, 20, 30);
        assertThat(dispatchedRunIds()).containsExactly(
                "convert:doc_placeholder:10", "convert:doc_placeholder:20", "convert:doc_placeholder:30");
        assertThat(queue.getNextStartPage()).isEqualTo(40);
        // 첫 결과의 provider·model·source_key를 이어받고 batch마다 예약하지 않는다.
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(taskWriter, times(3)).enqueueJoined(anyString(), anyString(), anyString(), payload.capture());
        var second = mapper.readTree(payload.getAllValues().getFirst());
        assertThat(second.path("start_page").asInt()).isEqualTo(10);
        assertThat(second.path("source_key").asText()).isEqualTo("sources/documents/" + SOURCE_DOCUMENT_ID + "/original");
        assertThat(second.path("model").asText()).isEqualTo("gemini-3.5-flash-lite");
        verify(taskWriter, never()).begin(anyString(), anyString(), anyString(), anyString());
        verify(taskWriter, never()).reserve(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("전체 쪽수가 적으면 남은 쪽까지만 발행한다")
    void dispatchStopsAtTotalPages() throws Exception {
        startScenario(PLACEHOLDER_MARKDOWN);
        seedDispatched(0);
        stubResult(0, "<!-- page 1 -->\n\n# p1\n");

        documentService.applyConvertBatch(succeeded(0, 10, 25));

        assertThat(batchStore.rows.keySet()).containsExactly(10, 20);
        assertThat(queue.getNextStartPage()).isEqualTo(30);
    }

    @Test
    @DisplayName("순서가 뒤바뀐 결과는 앞 쪽이 채워질 때까지 병합하지 않고, 채워지면 쪽 순서대로 한 번에 병합한 뒤 이어서 발행한다")
    void outOfOrderResultsMergeOnlyWhenContiguous() throws Exception {
        startScenario("<!-- page 10 -->\n\n# first pages\n");
        queue.checkpoint(10, 100);
        seedDispatched(10, 20, 30);
        stubResult(10, "<!-- page 1 -->\n\n# second\n");
        stubResult(20, "<!-- page 1 -->\n\n# third\n");

        documentService.applyConvertBatch(succeeded(20, 30, 100));

        assertThat(queue.getCompletedPages()).isEqualTo(10);
        assertThat(states.get(parent.getId()).getMarkdown()).isEqualTo("<!-- page 10 -->\n\n# first pages\n");
        assertThat(batchStore.rows.keySet()).containsExactly(10, 20, 30);
        verify(taskWriter, never()).enqueueJoined(anyString(), anyString(), anyString(), anyString());
        verify(minioClient, never()).getObject(any());

        documentService.applyConvertBatch(succeeded(10, 20, 100));

        assertThat(queue.getCompletedPages()).isEqualTo(30);
        assertThat(states.get(parent.getId()).getMarkdown()).isEqualTo(
                "<!-- page 10 -->\n\n# first pages\n\n<!-- page 11 -->\n\n# second\n\n<!-- page 21 -->\n\n# third\n");
        var order = org.mockito.Mockito.inOrder(postgresDocumentEditStore);
        order.verify(postgresDocumentEditStore).save(eq(WORKSPACE_ID), eq(parent.getId()), anyString(), anyString(),
                eq(1L), eq("convert:7:20:0"), eq(USER_ID), any());
        order.verify(postgresDocumentEditStore).save(eq(WORKSPACE_ID), eq(parent.getId()), anyString(), anyString(),
                eq(2L), eq("convert:7:30:0"), eq(USER_ID), any());
        // 병합한 batch는 지우고 비어 가는 만큼 다음 batch를 발행한다.
        assertThat(batchStore.rows.keySet()).containsExactly(30, 40, 50);
        assertThat(dispatchedRunIds()).containsExactly("convert:doc_placeholder:40", "convert:doc_placeholder:50");
    }

    @Test
    @DisplayName("순서가 뒤바뀌어 도착해도 최종 문서는 순서대로 도착한 경우와 같고 파트 분할도 같다")
    void outOfOrderFinalDocumentEqualsSequential() throws Exception {
        int sixtyPercent = DocumentEditingRules.MAX_MARKDOWN_BYTES / 3 * 6 / 10;
        java.util.Map<Integer, String> batches = java.util.Map.of(
                10, "<!-- page 1 -->\n\n" + "가".repeat(sixtyPercent) + "\n",
                20, "<!-- page 1 -->\n\n" + "나".repeat(sixtyPercent) + "\n",
                30, "<!-- page 1 -->\n\n" + "다".repeat(sixtyPercent) + "\n");

        var sequential = runMerge(batches, new int[] {10, 20, 30});
        var reversed = runMerge(batches, new int[] {30, 20, 10});

        assertThat(sequential).hasSize(3);
        assertThat(reversed).isEqualTo(sequential);
    }

    /** 결과를 주어진 순서로 전달하고 문서(파트 포함)별 최종 본문을 문서 ID 순서로 돌려준다. */
    private java.util.List<String> runMerge(java.util.Map<Integer, String> batches, int[] arrival) throws Exception {
        startScenario("<!-- page 10 -->\n\n# first pages\n");
        queue.checkpoint(10, 100);
        seedDispatched(10, 20, 30);
        for (var batch : batches.entrySet()) stubResult(batch.getKey(), batch.getValue());
        for (int startPage : arrival) documentService.applyConvertBatch(succeeded(startPage, startPage + 10, 100));
        assertThat(queue.getCompletedPages()).isEqualTo(40);
        return states.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> entry.getKey().equals(parent.getId()) ? "parent:" + entry.getValue().getMarkdown()
                        : "part:" + entry.getValue().getMarkdown()).toList();
    }

    @Test
    @DisplayName("같은 batch 이벤트가 다시 와도 한 번만 반영한다")
    void duplicateEventIsIgnored() throws Exception {
        startScenario("<!-- page 10 -->\n\n# first pages\n");
        queue.checkpoint(10, 100);
        seedDispatched(10, 20, 30);
        stubResult(10, "<!-- page 1 -->\n\n# second\n");
        stubResult(20, "<!-- page 1 -->\n\n# third\n");

        // 순서 대기 중인 결과의 재전달
        documentService.applyConvertBatch(succeeded(20, 30, 100));
        documentService.applyConvertBatch(succeeded(20, 30, 100));
        documentService.applyConvertBatch(succeeded(10, 20, 100));
        // 이미 병합한 batch의 재전달
        documentService.applyConvertBatch(succeeded(10, 20, 100));
        documentService.applyConvertBatch(succeeded(20, 30, 100));

        assertThat(states.get(parent.getId()).getMarkdown()).isEqualTo(
                "<!-- page 10 -->\n\n# first pages\n\n<!-- page 11 -->\n\n# second\n\n<!-- page 21 -->\n\n# third\n");
        verify(postgresDocumentEditStore, times(2)).save(anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), any());
        assertThat(dispatchedRunIds()).containsExactly("convert:doc_placeholder:40", "convert:doc_placeholder:50");
    }

    @Test
    @DisplayName("실행이 더는 진행 중이 아니거나 placeholder가 사라졌으면 결과를 읽지도 반영하지도 않는다")
    void eventAfterCancelOrDeleteIsIgnored() throws Exception {
        startScenario(PLACEHOLDER_MARKDOWN);
        seedDispatched(0);
        stubResult(0, "<!-- page 1 -->\n\n# p1\n");

        when(taskWriter.join("convert:doc_placeholder")).thenReturn(false);
        documentService.applyConvertBatch(succeeded(0, 10, 100));
        assertThat(batchStore.rows.get(0).status()).isEqualTo(DocumentConvertBatchStore.DISPATCHED);

        when(documentRepository.findByIdInActiveWorkspace("doc_placeholder")).thenReturn(Optional.empty());
        documentService.applyConvertBatch(succeeded(0, 10, 100));
        documentService.applyConvertBatch(failedEvent(0, "internal_error"));

        verify(minioClient, never()).getObject(any());
        verify(taskWriter, never()).fail(anyString());
        verify(taskWriter, never()).enqueueJoined(anyString(), anyString(), anyString(), anyString());
        assertThat(parent.getStatus()).isEqualTo(DocumentStatus.processing);
        assertThat(queue.getCompletedPages()).isZero();
    }

    @Test
    @DisplayName("batch가 실패하면 오류 코드의 문구로 placeholder를 실패시키고 실행을 닫고 나머지 batch를 각자의 command로 취소한다")
    void failedEventFailsDocumentAndCancelsOtherBatches() throws Exception {
        startScenario(PLACEHOLDER_MARKDOWN);
        queue.checkpoint(10, 100);
        seedDispatched(10, 20, 30);

        documentService.applyConvertBatch(failedEvent(20, "input_rejected"));

        assertThat(parent.getStatus()).isEqualTo(DocumentStatus.failed);
        assertThat(parent.getErrorMessage()).contains("암호").doesNotContain("input_rejected");
        verify(taskWriter).failAndSettle("convert:doc_placeholder");
        verify(convertQueueRepository).delete(queue);
        ArgumentCaptor<String> cancelled = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> commands =
                ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(pipelineCancellation, times(2)).cancel(cancelled.capture(), eq(WORKSPACE_ID), eq(USER_ID), commands.capture());
        assertThat(cancelled.getAllValues()).containsExactly("convert:doc_placeholder:10", "convert:doc_placeholder:30");
        assertThat(commands.getAllValues().stream().map(command -> command.path("run_id").asText()))
                .containsExactly("convert:doc_placeholder:10", "convert:doc_placeholder:30");
        verify(taskWriter, never()).completeAndSettle(anyString());
    }

    @Test
    @DisplayName("알 수 없는 오류 코드나 예외 원문은 사용자 문구에 그대로 싣지 않는다")
    void failureMessageNeverEchoesRawError() throws Exception {
        startScenario(PLACEHOLDER_MARKDOWN);
        seedDispatched(0);

        documentService.applyConvertBatch(failedEvent(0, "Traceback secret_internal_detail"));

        assertThat(parent.getStatus()).isEqualTo(DocumentStatus.failed);
        assertThat(parent.getErrorMessage()).startsWith("PDF 변환에 실패했습니다").doesNotContain("secret_internal_detail");
    }

    @Test
    @DisplayName("결과 쪽 범위나 key가 발행한 batch와 맞지 않으면 병합하지 않고 변환을 실패시킨다")
    void mismatchedResultFailsConversion() throws Exception {
        startScenario(PLACEHOLDER_MARKDOWN);
        seedDispatched(0);
        stubResult(0, "<!-- page 1 -->\n\n# p1\n");

        // 10쪽을 요청했는데 5쪽만 오고 끝도 아니다.
        documentService.applyConvertBatch(succeeded(0, 5, 100));

        assertThat(parent.getStatus()).isEqualTo(DocumentStatus.failed);
        verify(minioClient, never()).getObject(any());
        verify(taskWriter).failAndSettle("convert:doc_placeholder");
    }

    @Test
    @DisplayName("마지막 결과를 병합하면 문서를 completed로 바꾸고 정산한 뒤에 자동 ingest를 시작하며 대기열 행을 지운다")
    void completionSettlesBeforeAutoIngest() throws Exception {
        enableAutoIngest(true);
        startScenario(PLACEHOLDER_MARKDOWN);
        seedDispatched(0);
        stubResult(0, "<!-- page 1 -->\n\n# 본문\n");

        documentService.applyConvertBatch(succeeded(0, 3, 3));

        assertThat(states.get(parent.getId()).getMarkdown()).isEqualTo("<!-- page 1 -->\n\n# 본문\n");
        verify(convertQueueRepository).delete(queue);
        var order = org.mockito.Mockito.inOrder(taskWriter, ingestCommandOutbox);
        order.verify(taskWriter).completeAndSettle("convert:doc_placeholder");
        order.verify(ingestCommandOutbox, times(1)).begin(anyString(), eq(WORKSPACE_ID), eq(USER_ID));
        verify(taskWriter, never()).enqueueJoined(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("변환 뒤 자동 ingest가 크레딧 부족으로 거절돼도 변환은 성공이고 문서에 INSUFFICIENT_CREDIT을 남긴다")
    void autoIngestRejectedForCreditKeepsConversionAndRecordsInsufficientCredit() throws Exception {
        enableAutoIngest(true);
        startScenario("<!-- page 10 -->\n\n# first pages\n");
        queue.checkpoint(10, 20);
        seedDispatched(10);
        stubResult(10, "<!-- page 1 -->\n\n# next pages\n");
        org.mockito.Mockito.doThrow(new fruition.core.usage.service.CreditService.InsufficientCreditException("부족"))
                .when(ingestCommandOutbox).begin(anyString(), eq(WORKSPACE_ID), eq(USER_ID));

        documentService.applyConvertBatch(succeeded(10, 20, 20));

        assertThat(queue.getCompletedPages()).isEqualTo(20);
        assertThat(parent.getStatus()).isEqualTo(DocumentStatus.failed);
        assertThat(parent.getErrorMessage()).contains("크레딧이 부족해 작업을 시작하지 않았습니다").doesNotContain("INSUFFICIENT_CREDIT", "PDF 변환에 실패했습니다");
        // 변환 실행은 자동 ingest를 시작하기 전에 닫고 예약을 풀었다.
        var order = org.mockito.Mockito.inOrder(taskWriter, ingestCommandOutbox);
        order.verify(taskWriter).completeAndSettle("convert:" + parent.getId());
        order.verify(ingestCommandOutbox).begin(anyString(), eq(WORKSPACE_ID), eq(USER_ID));
    }

    @Test
    @DisplayName("상한을 넘는 한 묶음은 앞 조각을 이어 붙인 뒤의 본문 기준으로 다음 조각의 파트 전환을 판단한다")
    void oversizedBatchUsesAppendedContentForNextPiece() throws Exception {
        int mib = 1024 * 1024;
        startScenario("<!-- page 10 -->\n\n" + "가".repeat(mib / 3));
        queue.checkpoint(10, 100);
        seedDispatched(10);
        // 11쪽(약 3.5MiB) + 12쪽(약 2MiB): 함께는 상한을 넘어 두 조각이 된다.
        stubResult(10, "<!-- page 1 -->\n\n" + "나".repeat(mib * 7 / 6) + "\n\n<!-- page 2 -->\n\n" + "다".repeat(mib * 2 / 3) + "\n");

        documentService.applyConvertBatch(succeeded(10, 20, 100));

        var parts = rows.values().stream().filter(doc -> "convert_part".equals(doc.getOrigin())).toList();
        assertThat(parts).hasSize(1);
        assertThat(states.get(parent.getId()).getMarkdown()).contains("<!-- page 11 -->").doesNotContain("<!-- page 12 -->");
        assertThat(states.get(parts.getFirst().getId()).getMarkdown()).startsWith("<!-- page 12 -->");
        verify(postgresDocumentEditStore, times(1)).save(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("PDF 변환 중인 문서는 저장을 거부하고, 변환이 실패한 뒤에는 저장을 허용한다")
    void convertingDocumentRejectsContentSave() {
        stubOwnedWorkspace();
        var parent = placeholderDocument();
        parent.markPipelineStarted("convert:" + parent.getId(), Instant.now());
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(parent.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> documentService.validateContentSavePreconditions(WORKSPACE_ID, USER_ID, parent.getId()))
                .isInstanceOf(DocumentAlreadyProcessingException.class)
                .hasMessageContaining("PDF 변환 중");

        parent.markProcessingFailed("PDF 변환에 실패했습니다", Instant.now());
        documentService.validateContentSavePreconditions(WORKSPACE_ID, USER_ID, parent.getId());
    }

    @Test
    @DisplayName("변환 중 삭제된 파트의 ID는 다시 쓰지 않고 다음 번호로 새 파트를 만든다")
    void deletedPartIdIsNotReused() throws Exception {
        String nearlyFull = "<!-- page 10 -->\n\n" + "가".repeat((DocumentEditingRules.MAX_MARKDOWN_BYTES - 100) / 3);
        startScenario(nearlyFull);
        queue.checkpoint(10, 100);
        seedDispatched(10);
        String deletedPartId = "doc_" + java.util.UUID.nameUUIDFromBytes((parent.getId() + ":part:1")
                .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        when(documentRepository.existsById(anyString())).thenAnswer(inv -> deletedPartId.equals(inv.getArgument(0)));
        stubResult(10, "<!-- page 1 -->\n\n" + "나".repeat(100) + "\n");

        documentService.applyConvertBatch(succeeded(10, 20, 100));

        var parts = rows.values().stream().filter(doc -> "convert_part".equals(doc.getOrigin())).toList();
        assertThat(parts).hasSize(1);
        assertThat(parts.getFirst().getId()).isNotEqualTo(deletedPartId);
        assertThat(parts.getFirst().getFilename()).contains("[파트 3,");
    }

    @Test
    @DisplayName("첫 페이지 묶음은 변환 중 placeholder를 대체한다")
    void firstBatchReplacesPlaceholder() throws Exception {
        enableAutoIngest(true);
        startScenario(PLACEHOLDER_MARKDOWN);
        seedDispatched(0);
        stubResult(0, "<!-- page 1 -->\n\n# 본문\n");

        documentService.applyConvertBatch(succeeded(0, 3, 3));

        assertThat(states.get(parent.getId()).getMarkdown()).isEqualTo("<!-- page 1 -->\n\n# 본문\n");
        verify(ingestCommandOutbox, times(1)).begin(anyString(), eq(WORKSPACE_ID), eq(USER_ID));
    }

    @Test
    @DisplayName("페이지 묶음 변환 결과의 원시 HTML과 위험한 링크를 걷어내고 페이지 주석은 남긴다")
    void batchConvertSanitizesMarkdown() throws Exception {
        startScenario(PLACEHOLDER_MARKDOWN);
        seedDispatched(0);
        stubResult(0, "<!-- page 1 -->\n\n<u>임시명세서</u> [보기](javascript:alert(1))\n");

        documentService.applyConvertBatch(succeeded(0, 3, 3));

        assertThat(states.get(parent.getId()).getMarkdown()).isEqualTo("<!-- page 1 -->\n\n임시명세서 보기\n");
    }

    @Test
    @DisplayName("크기 상한을 넘는 변환 이미지는 저장하지 않고 생략 문구로 바꾼다")
    void oversizedConvertedImageIsReplacedWithNotice() throws Exception {
        startScenario(PLACEHOLDER_MARKDOWN);
        seedDispatched(0);
        String png = java.util.Base64.getEncoder().encodeToString(pngHeader(20_000, 10));
        stubResult(0, "앞\n\n![figure](data:image/png;base64," + png + ")\n\n뒤\n");

        documentService.applyConvertBatch(succeeded(0, 1, 1));

        assertThat(states.get(parent.getId()).getMarkdown()).isEqualTo("앞\n\n(이미지가 너무 커서 생략됨)\n\n뒤\n");
        verify(assetRepository, never()).save(any());
    }

    /** 크기 판정은 머리 정보만 읽으므로 IHDR까지만 있는 PNG로 충분하다. */
    private static byte[] pngHeader(int width, int height) {
        var ihdr = java.nio.ByteBuffer.allocate(17).put("IHDR".getBytes(StandardCharsets.US_ASCII))
                .putInt(width).putInt(height).put(new byte[] {8, 2, 0, 0, 0}).array();
        var crc = new java.util.zip.CRC32();
        crc.update(ihdr);
        return java.nio.ByteBuffer.allocate(8 + 4 + 17 + 4)
                .put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})
                .putInt(13).put(ihdr).putInt((int) crc.getValue()).array();
    }

    @Test
    @DisplayName("이어 붙인 본문이 편집 상한을 넘으면 다음 파트 문서로 넘어가고 파트마다 AI 큐에 등록된다")
    void batchOverEditLimitStartsNextPart() throws Exception {
        enableAutoIngest(true);
        String nearlyFull = "<!-- page 10 -->\n\n" + "가".repeat((DocumentEditingRules.MAX_MARKDOWN_BYTES - 100) / 3);
        startScenario(nearlyFull);
        queue.checkpoint(10, 20);
        seedDispatched(10);
        stubResult(10, "<!-- page 1 -->\n\n" + "나".repeat(100) + "\n");

        documentService.applyConvertBatch(succeeded(10, 20, 20));

        var parts = rows.values().stream().filter(doc -> "convert_part".equals(doc.getOrigin())).toList();
        assertThat(parts).hasSize(1);
        assertThat(states.get(parent.getId()).getMarkdown()).isEqualTo(nearlyFull);
        assertThat(states.get(parts.getFirst().getId()).getMarkdown()).startsWith("<!-- page 11 -->");
        verify(ingestCommandOutbox, times(2)).begin(anyString(), eq(WORKSPACE_ID), eq(USER_ID));
    }

    @Test
    @DisplayName("변환 결과 object가 없으면 변환을 실패시키고 실행을 닫는다")
    void missingResultObjectFailsConversion() throws Exception {
        startScenario(PLACEHOLDER_MARKDOWN);
        seedDispatched(0);
        lenient().when(minioClient.getObject(any())).thenThrow(new io.minio.errors.ErrorResponseException(
                new io.minio.messages.ErrorResponse(), null, null));

        documentService.applyConvertBatch(succeeded(0, 10, 100));

        assertThat(parent.getStatus()).isEqualTo(DocumentStatus.failed);
        verify(taskWriter).failAndSettle("convert:doc_placeholder");
    }

    @Test
    @DisplayName("placeholder를 삭제하면 결과를 기다리는 모든 batch를 각자의 command로 취소하고 변환 run을 닫는다")
    void deletingPlaceholderCancelsAllInFlightBatches() throws Exception {
        stubOwnedWorkspace();
        startScenario(PLACEHOLDER_MARKDOWN);
        queue.checkpoint(10, 100);
        seedDispatched(10, 20, 30);
        when(convertQueueRepository.findByDocumentId(parent.getId())).thenReturn(Optional.of(queue));
        when(documentRepository.softDeleteIfVersionMatches(
                eq(parent.getId()), eq(WORKSPACE_ID), eq(1L), eq(USER_ID), any(), any())).thenReturn(1);

        documentService.delete(WORKSPACE_ID, USER_ID, parent.getId(), "delete-key",
                new fruition.core.document.dto.DocumentLifecycleRequest(1L));

        ArgumentCaptor<String> cancelled = ArgumentCaptor.forClass(String.class);
        verify(pipelineCancellation, times(3)).cancel(cancelled.capture(), eq(WORKSPACE_ID), eq(USER_ID), any());
        assertThat(cancelled.getAllValues()).containsExactly(
                "convert:doc_placeholder:10", "convert:doc_placeholder:20", "convert:doc_placeholder:30");
        verify(taskWriter).fail("convert:doc_placeholder");
        verify(convertQueueRepository).delete(queue);
    }

    @Test
    @DisplayName("ingest 실패 코드는 카탈로그 문구로, 알 수 없는 원문은 기본 문구로 저장한다")
    void applyPipelineResult_failureStoresCatalogMessage() {
        Document known = placeholderDocument();
        known.markPipelineStarted("run-1", Instant.now());
        when(documentRepository.findByIdInActiveWorkspace("doc_placeholder")).thenReturn(Optional.of(known));

        documentService.applyPipelineResult("doc_placeholder", "run-1", "failed", "provider_rate_limited");

        assertThat(known.getStatus()).isEqualTo(DocumentStatus.failed);
        assertThat(known.getErrorMessage()).contains("요청이 많아").doesNotContain("provider_rate_limited");

        Document raw = placeholderDocument();
        raw.markPipelineStarted("run-2", Instant.now());
        when(documentRepository.findByIdInActiveWorkspace("doc_placeholder")).thenReturn(Optional.of(raw));

        documentService.applyPipelineResult("doc_placeholder", "run-2", "failed", "ValueError: secret detail");

        assertThat(raw.getErrorMessage()).isEqualTo("위키 편입에 실패했습니다.");
    }

    private Document placeholderDocument() {
        Document placeholder = new Document("doc_placeholder", WORKSPACE_ID, USER_ID, "보고서.md",
                "text/markdown", 15L, null, null, "convert");
        placeholder.initializeConvertPlaceholder(SOURCE_DOCUMENT_ID, null, "placeholder-hash", 15L, 0L);
        return placeholder;
    }

    private DocumentMetadata metadata(Document document) {
        return new DocumentMetadata(
                document.getProcessedAt(), document.getUpdatedAt(), document.getErrorMessage(),
                document.getByteSize(), document.getContentHash(), document.getCurrentContentHash(),
                document.getStatus(), document.getCurrentVersion());
    }

    private record DocumentMetadata(
            Instant processedAt,
            Instant updatedAt,
            String error,
            long byteSize,
            String contentHash,
            String currentContentHash,
            DocumentStatus status,
            long currentVersion
    ) {}
}
