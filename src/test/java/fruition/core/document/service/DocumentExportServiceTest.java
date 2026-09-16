package fruition.core.document.service;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.dto.DocumentExportResult;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.domain.DocumentAsset;
import fruition.core.document.domain.DocumentAssetReference;
import fruition.core.document.exception.DocumentAssetExportException;
import fruition.core.document.repository.DocumentAssetReferenceRepository;
import fruition.core.document.repository.DocumentAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentExportServiceTest {

    private static final String WORKSPACE_ID = "ws_aaa11111";
    private static final String USER_ID = "member_1";

    @Mock DocumentRepository documentRepository;
    @Mock DocumentEditStateRepository editStateRepository;
    @Mock DocumentEditStateInitializer editStateInitializer;
    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock DocumentAssetReferenceRepository referenceRepository;
    @Mock DocumentAssetRepository assetRepository;
    @Mock DocumentAssetObjectStorage objectStorage;
    @Mock TransactionTemplate transactionTemplate;

    DocumentExportService exportService;

    @BeforeEach
    void setUp() {
        exportService = new DocumentExportService(
                documentRepository, editStateRepository,
                editStateInitializer,
                workspaceAccessGuard,
                referenceRepository, assetRepository, objectStorage,
                transactionTemplate);
        doAnswer(invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null))
                .when(transactionTemplate).execute(any());
    }

    @Test
    void exportMarkdown_memberDownloadsLatestUtf8WithoutChangingState() throws Exception {
        Document document = new Document(
                "doc_export", WORKSPACE_ID, "owner_1", "회의 결과.md",
                "text/markdown", 10, null, null, "direct");
        document.initializeDirectMarkdown("hash", 10, 3);
        DocumentEditState editState = new DocumentEditState(
                document.getId(), "# 최신 회의 결과\n한글 본문", "hash", 1);
        long versionBefore = document.getCurrentVersion();
        var updatedAtBefore = document.getUpdatedAt();
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                document.getId(), WORKSPACE_ID)).thenReturn(Optional.of(document));
        when(editStateRepository.findById(document.getId())).thenReturn(Optional.of(editState));
        when(referenceRepository.findAllByIdDocumentId(document.getId())).thenReturn(List.of());

        DocumentExportResult result =
                exportService.exportMarkdown(WORKSPACE_ID, USER_ID, document.getId());

        assertThat(result.filename()).isEqualTo("회의 결과.md");
        assertThat(new String(readAll(result.content()), StandardCharsets.UTF_8))
                .isEqualTo("# 최신 회의 결과\n한글 본문");
        assertThat(document.getCurrentVersion()).isEqualTo(versionBefore);
        assertThat(document.getUpdatedAt()).isEqualTo(updatedAtBefore);
        var order = inOrder(workspaceAccessGuard, documentRepository, editStateInitializer, transactionTemplate);
        order.verify(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
        order.verify(documentRepository).findByIdAndWorkspaceIdAndDeletedAtIsNull(
                document.getId(), WORKSPACE_ID);
        order.verify(editStateInitializer).initializeIfNeeded(document);
        order.verify(transactionTemplate).execute(any());
        verify(editStateInitializer).initializeIfNeeded(document);
        verify(documentRepository, never()).save(document);
        verify(editStateRepository, never()).save(editState);
    }

    @Test
    void exportMarkdown_withManagedImagesCreatesCompleteZipAndKeepsExternalUrl() throws Exception {
        Document document = editableDocument("doc_zip", "이미지 문서");
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        String markdown = """
                ![첫째](/api/workspaces/%s/assets/%s/content)
                ![둘째](/api/workspaces/%s/assets/%s/content)
                ![외부](https://example.com/image.png)
                """.formatted(WORKSPACE_ID, firstId, WORKSPACE_ID, secondId);
        stubDocument(document, new DocumentEditState(document.getId(), markdown, "hash", 1));
        when(referenceRepository.findAllByIdDocumentId(document.getId())).thenReturn(List.of(
                new DocumentAssetReference(document.getId(), firstId, java.time.Instant.now()),
                new DocumentAssetReference(document.getId(), secondId, java.time.Instant.now())));
        DocumentAsset first = asset(firstId, "diagram.png", "key-1", 3);
        DocumentAsset second = asset(secondId, "diagram.png", "key-2", 2);
        when(assetRepository.findAllByIdInAndWorkspaceId(List.of(firstId, secondId), WORKSPACE_ID))
                .thenReturn(List.of(first, second));
        when(objectStorage.get("key-1")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(objectStorage.get("key-2")).thenReturn(new ByteArrayInputStream(new byte[]{4, 5}));

        DocumentExportResult result = exportService.exportMarkdown(WORKSPACE_ID, USER_ID, document.getId());
        byte[] zipBytes = readAll(result.content());
        Map<String, byte[]> entries = unzip(zipBytes);

        assertThat(result.filename()).isEqualTo("이미지 문서.zip");
        assertThat(result.contentType()).isEqualTo("application/zip");
        assertThat(result.contentLength()).isEqualTo(zipBytes.length);
        assertThat(entries.keySet()).containsExactlyInAnyOrder(
                "이미지 문서.md", "assets/diagram.png", "assets/diagram-2.png");
        String exportedMarkdown = new String(entries.get("이미지 문서.md"), StandardCharsets.UTF_8);
        assertThat(exportedMarkdown).contains("./assets/diagram.png", "./assets/diagram-2.png");
        assertThat(exportedMarkdown).contains("https://example.com/image.png");
        assertThat(entries.get("assets/diagram.png")).containsExactly(1, 2, 3);
    }

    @Test
    void exportMarkdown_missingAssetFailsBeforeReadingStorage() {
        Document document = editableDocument("doc_missing", "누락 문서");
        UUID assetId = UUID.randomUUID();
        stubDocument(document, new DocumentEditState(document.getId(), "본문", "hash", 1));
        when(referenceRepository.findAllByIdDocumentId(document.getId())).thenReturn(List.of(
                new DocumentAssetReference(document.getId(), assetId, java.time.Instant.now())));
        when(assetRepository.findAllByIdInAndWorkspaceId(List.of(assetId), WORKSPACE_ID))
                .thenReturn(List.of());

        assertThatThrownBy(() -> exportService.exportMarkdown(WORKSPACE_ID, USER_ID, document.getId()))
                .isInstanceOf(DocumentAssetExportException.class);
        verify(objectStorage, never()).get(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void exportMarkdown_moreThanHundredAssetsFailsBeforeAssetLookup() {
        Document document = editableDocument("doc_many", "대량 문서");
        stubDocument(document, new DocumentEditState(document.getId(), "본문", "hash", 1));
        List<DocumentAssetReference> references = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> new DocumentAssetReference(
                        document.getId(), UUID.randomUUID(), java.time.Instant.now()))
                .toList();
        when(referenceRepository.findAllByIdDocumentId(document.getId())).thenReturn(references);

        assertThatThrownBy(() -> exportService.exportMarkdown(WORKSPACE_ID, USER_ID, document.getId()))
                .isInstanceOf(DocumentAssetExportException.class)
                .hasMessageContaining("100개");
        verify(assetRepository, never()).findAllByIdInAndWorkspaceId(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void exportMarkdown_moreThanHundredMegabytesFailsBeforeReadingStorage() {
        Document document = editableDocument("doc_large", "대용량 문서");
        UUID assetId = UUID.randomUUID();
        stubDocument(document, new DocumentEditState(document.getId(), "본문", "hash", 1));
        when(referenceRepository.findAllByIdDocumentId(document.getId())).thenReturn(List.of(
                new DocumentAssetReference(document.getId(), assetId, java.time.Instant.now())));
        when(assetRepository.findAllByIdInAndWorkspaceId(List.of(assetId), WORKSPACE_ID))
                .thenReturn(List.of(asset(assetId, "large.png", "key", 100L * 1024 * 1024 + 1)));

        assertThatThrownBy(() -> exportService.exportMarkdown(WORKSPACE_ID, USER_ID, document.getId()))
                .isInstanceOf(DocumentAssetExportException.class)
                .hasMessageContaining("100MB");
        verify(objectStorage, never()).get(org.mockito.ArgumentMatchers.anyString());
    }

    private Document editableDocument(String id, String displayName) {
        Document document = new Document(id, WORKSPACE_ID, USER_ID, displayName + ".md",
                "text/markdown", 10, null, null, "direct");
        document.initializeDirectMarkdown("hash", 10, 1);
        return document;
    }

    private void stubDocument(Document document, DocumentEditState editState) {
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById(document.getId())).thenReturn(Optional.of(editState));
    }

    private DocumentAsset asset(UUID id, String filename, String storageKey, long size) {
        return new DocumentAsset(id, WORKSPACE_ID, USER_ID, filename, "image/png", size,
                1, 1, "a".repeat(64), storageKey, java.time.Instant.now());
    }

    /** stream을 끝까지 읽고 닫는다. ZIP stream은 닫힐 때 임시 파일도 함께 정리된다. */
    private byte[] readAll(InputStream content) throws Exception {
        try (content) {
            return content.readAllBytes();
        }
    }

    private Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    @Test
    void exportMarkdown_originalOrMissingEditState_returnsNotFound() {
        Document original = new Document(
                "doc_pdf", WORKSPACE_ID, USER_ID, "자료.pdf",
                "application/pdf", 10, "sources/doc_pdf/original", "hash");
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                original.getId(), WORKSPACE_ID)).thenReturn(Optional.of(original));

        assertThatThrownBy(() ->
                exportService.exportMarkdown(WORKSPACE_ID, USER_ID, original.getId()))
                .isInstanceOf(DocumentNotFoundException.class);
        verify(editStateInitializer, never()).initializeIfNeeded(original);

        Document editable = new Document(
                "doc_no_state", WORKSPACE_ID, USER_ID, "문서.md",
                "text/markdown", 0, null, null, "direct");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                editable.getId(), WORKSPACE_ID)).thenReturn(Optional.of(editable));
        when(editStateRepository.findById(editable.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                exportService.exportMarkdown(WORKSPACE_ID, USER_ID, editable.getId()))
                .isInstanceOf(DocumentNotFoundException.class);
    }
}
