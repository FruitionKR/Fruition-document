package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fruition.core.authz.WorkspaceSettingsClient;
import fruition.core.document.domain.Document;
import fruition.core.document.service.McpDocumentDataGateway;
import fruition.shared.util.StorageProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpPdfToolsTest {
    private static final String PDF_HASH = "sha256:" + "a".repeat(64);
    private static final String CONVERSION = "0f6f3d1e-2b7a-4c8e-9d5f-1a2b3c4d5e6f";
    private static final String RESULT_KEY = "tmp/convert/mcp/workspace/" + CONVERSION + "/final.json";
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpDocumentDataGateway documents = mock(McpDocumentDataGateway.class);
    private final McpPlanProposalService plans = mock(McpPlanProposalService.class);
    private final McpAccessClient access = mock(McpAccessClient.class);
    private final McpWikiDraftClient ai = mock(McpWikiDraftClient.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final WorkspaceSettingsClient settings = mock(WorkspaceSettingsClient.class);
    private final MinioClient storage = mock(MinioClient.class);
    private final UUID folder = UUID.fromString("00000000-0000-4000-8000-0000000000f1");
    private final McpIdentity identity = new McpIdentity("user", "workspace", "https://client.example/mcp",
            "00000000-0000-4000-8000-000000000001", 3, Set.of("read", "write_propose"), "MEMBER", Set.of());
    private final McpPdfTools tools;

    McpPdfToolsTest() {
        StorageProperties properties = new StorageProperties();
        properties.setBucket("bucket");
        tools = new McpPdfTools(documents, plans, access, ai, transaction, settings, storage,
                properties, mapper, 10);
        when(access.authorizeProposal(any())).thenReturn(identity);
        doAnswer(invocation -> {
            invocation.<Consumer<Object>>getArgument(0).accept(null);
            return null;
        }).when(transaction).executeWithoutResult(any());
    }

    @Test
    void startChecksSourceBeforeAiAndDispatchesExistingPreprocessing() {
        Document pdf = pdf();
        when(documents.convertSource(identity, "doc_pdf", PDF_HASH))
                .thenReturn(new McpDocumentDataGateway.ConvertSource(null, false));
        assertThatThrownBy(() -> tools.invoke(identity, "start_pdf_conversion", start()))
                .isInstanceOf(McpToolException.class).hasMessage("RESOURCE_NOT_FOUND");
        when(documents.convertSource(identity, "doc_pdf", PDF_HASH))
                .thenReturn(new McpDocumentDataGateway.ConvertSource(pdf, false));
        assertThatThrownBy(() -> tools.invoke(identity, "start_pdf_conversion", start()))
                .isInstanceOf(McpToolException.class).hasMessage("SOURCE_CHANGED");
        verifyNoInteractions(ai, transaction);

        when(documents.convertSource(identity, "doc_pdf", PDF_HASH))
                .thenReturn(new McpDocumentDataGateway.ConvertSource(pdf, true));
        when(settings.describePdfFigures("workspace")).thenReturn(true);
        when(ai.post(eq("/internal/mcp/pdf-conversions"), any())).thenReturn(status(true));

        @SuppressWarnings("unchecked")
        var view = (Map<String, Object>) tools.invoke(identity, "start_pdf_conversion", start());
        tools.invoke(identity, "start_pdf_conversion", start());

        assertThat(view.get("state")).isEqualTo("PREPARING");
        verify(documents, times(2)).enqueueExternalPrepare(CONVERSION, "workspace", "user",
                "doc_pdf", "sources/documents/doc_pdf/original", 0, 10, true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(ai, times(2)).post(eq("/internal/mcp/pdf-conversions"), body.capture());
        // 같은 요청을 다시 보내면 invocation_id가 달라도 같은 request_hash라 AI가 같은 세션을 돌려준다.
        assertThat(body.getAllValues().get(0).get("request_hash")).isEqualTo(body.getAllValues().get(1).get("request_hash"));
        assertThat(body.getAllValues().get(0)).doesNotContainKeys("provider", "model");
    }

    @Test
    void windowRejectsUnavailableOrRestrictedSourceBeforeReadingOrSigning() throws Exception {
        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/status"), any()))
                .thenReturn(status(false));
        when(documents.convertSource(identity, "doc_pdf", PDF_HASH))
                .thenReturn(new McpDocumentDataGateway.ConvertSource(null, false));
        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/windows/read"), any()))
                .thenReturn(window("tmp/convert/mcp/workspace/" + CONVERSION + "/0/external/images/00000.png"));
        when(storage.getPresignedObjectUrl(any())).thenReturn("https://storage/signed");

        assertThatThrownBy(() -> tools.invoke(identity, "get_conversion_window",
                Map.of("conversion_id", CONVERSION, "window_ordinal", 1)))
                .isInstanceOf(McpToolException.class).hasMessage("RESOURCE_NOT_FOUND");
        verify(documents).convertSource(identity, "doc_pdf", PDF_HASH);
        verify(ai, never()).post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/windows/read"), any());
        verifyNoInteractions(storage, access);
    }

    @Test
    void windowRejectsChangedSourceBeforeReadingOrSigning() {
        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/status"), any()))
                .thenReturn(status(false));
        Document pdf = pdf();
        when(documents.convertSource(identity, "doc_pdf", PDF_HASH))
                .thenReturn(new McpDocumentDataGateway.ConvertSource(pdf, false));

        assertThatThrownBy(() -> tools.invoke(identity, "get_conversion_window",
                Map.of("conversion_id", CONVERSION, "window_ordinal", 1)))
                .isInstanceOf(McpToolException.class).hasMessage("SOURCE_CHANGED");
        verify(documents).convertSource(identity, "doc_pdf", PDF_HASH);
        verify(ai, never()).post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/windows/read"), any());
        verifyNoInteractions(storage, access);
    }

    @Test
    void windowRejectsUnownedConversionBeforeCheckingSource() {
        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/status"), any()))
                .thenThrow(new McpToolException("RESOURCE_NOT_FOUND"));

        assertThatThrownBy(() -> tools.invoke(identity, "get_conversion_window",
                Map.of("conversion_id", CONVERSION, "window_ordinal", 1)))
                .isInstanceOf(McpToolException.class).hasMessage("RESOURCE_NOT_FOUND");
        verify(ai, never()).post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/windows/read"), any());
        verifyNoInteractions(documents, storage, access);
    }

    @Test
    void windowSignsOnlyThisConversionsBundleImages() throws Exception {
        McpIdentity reader = new McpIdentity(identity.userId(), identity.workspaceId(), identity.clientId(),
                identity.grantId(), identity.policyGeneration(), Set.of("read"), "MEMBER", Set.of());
        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/status"), any()))
                .thenReturn(status(false));
        Document pdf = pdf();
        when(documents.convertSource(reader, "doc_pdf", PDF_HASH))
                .thenReturn(new McpDocumentDataGateway.ConvertSource(pdf, true));
        when(storage.getPresignedObjectUrl(any())).thenReturn("https://storage/signed");
        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/windows/read"), any()))
                .thenReturn(window("tmp/convert/mcp/workspace/" + CONVERSION + "/0/external/images/00000.png"));

        @SuppressWarnings("unchecked")
        var view = (Map<String, Object>) tools.invoke(reader, "get_conversion_window",
                Map.of("conversion_id", CONVERSION, "window_ordinal", 1));
        assertThat(view.get("images")).isEqualTo(List.of(Map.of("url", "https://storage/signed", "media_type", "image/png")));
        assertThat(view.get("instructions")).isEqualTo("prompt");
        verifyNoInteractions(access);
        var order = inOrder(ai, documents, storage);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> statusBody = ArgumentCaptor.forClass(Map.class);
        order.verify(ai).post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/status"), statusBody.capture());
        order.verify(documents).convertSource(reader, "doc_pdf", PDF_HASH);
        order.verify(ai).post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/windows/read"), eq(Map.of(
                "schema_version", "mcp.pdf-conversion.v1", "actor", statusBody.getValue().get("actor"), "window_ordinal", 1)));
        order.verify(storage).getPresignedObjectUrl(any());

        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/windows/read"), any()))
                .thenReturn(window("tmp/convert/mcp/other/" + CONVERSION + "/0/x.png"));
        assertThatThrownBy(() -> tools.invoke(reader, "get_conversion_window",
                Map.of("conversion_id", CONVERSION, "window_ordinal", 1)))
                .isInstanceOf(McpToolException.class).hasMessage("DEPENDENCY_UNAVAILABLE");
        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/windows/read"), any()))
                .thenReturn(window("tmp/convert/mcp/workspace/00000000-0000-4000-8000-000000000002/0/x.png"));
        assertThatThrownBy(() -> tools.invoke(reader, "get_conversion_window",
                Map.of("conversion_id", CONVERSION, "window_ordinal", 1)))
                .isInstanceOf(McpToolException.class).hasMessage("DEPENDENCY_UNAVAILABLE");
        verify(storage, times(1)).getPresignedObjectUrl(any());
    }

    @Test
    void finalizeProposesOnlyTheStoredResultKeyInThePdfFolder() throws Exception {
        String contentHash = "sha256:" + "c".repeat(64);
        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/finalize"), any()))
                .thenReturn(finalized(contentHash, RESULT_KEY));
        Document pdf = pdf();
        when(documents.convertSource(identity, "doc_pdf", PDF_HASH))
                .thenReturn(new McpDocumentDataGateway.ConvertSource(pdf, true));
        when(plans.propose(any(), any())).thenReturn(
                new McpDocumentProposalService.Result("run-1", "awaiting_approval", "https://review/run-1"));

        @SuppressWarnings("unchecked")
        var view = (Map<String, Object>) tools.invoke(identity, "finalize_pdf_conversion", finalizeArgs());

        assertThat(view.get("review_url")).isEqualTo("https://review/run-1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> plan = ArgumentCaptor.forClass(Map.class);
        verify(plans).propose(eq(identity), plan.capture());
        var validated = McpPlanProposalService.input(plan.getValue());
        Map<?, ?> arguments = (Map<?, ?>) validated.operations().getFirst().get("arguments");
        // 조립 결과는 계획에 싣지 않는다. 승인 뒤 document가 key에서 읽어 hash를 확인하고 저장한다.
        assertThat(arguments.get("content_result_key")).isEqualTo(RESULT_KEY);
        assertThat(arguments.get("content_hash")).isEqualTo(contentHash);
        assertThat(arguments.get("source_document_id")).isEqualTo("doc_pdf");
        assertThat(arguments.get("source_content_hash")).isEqualTo(PDF_HASH);
        // 웹 변환처럼 원본 PDF와 같은 폴더에 만든다.
        assertThat(arguments.get("folder_id")).isEqualTo(folder.toString());
        assertThat(arguments.get("display_name")).isEqualTo("보고서");
        assertThat(validated.artifacts()).isEmpty();
        verify(storage, never()).getObject(any(GetObjectArgs.class));
    }

    @Test
    void finalizeNoLongerAcceptsAClientChosenFolder() {
        Map<String, Object> args = finalizeArgs();
        args.put("target_folder_id", UUID.randomUUID().toString());
        assertThatThrownBy(() -> tools.invoke(identity, "finalize_pdf_conversion", args))
                .isInstanceOf(McpToolException.class).hasMessage("INVALID_INPUT");
        var schema = tools.tools().stream().filter(tool -> tool.name().equals("finalize_pdf_conversion"))
                .findFirst().orElseThrow().inputSchema();
        assertThat(((Map<?, ?>) schema.get("properties")).keySet().stream().map(Object::toString).toList())
                .containsExactly("idempotency_key", "conversion_id", "display_name");
        verify(ai, never()).post(anyString(), any());
    }

    @Test
    void finalizeRefusesForeignResultKeyOrChangedSourceWithoutProposing() {
        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/finalize"), any()))
                .thenReturn(finalized(PDF_HASH, "tmp/convert/mcp/other/" + CONVERSION + "/final.json"));
        Document pdf = pdf();
        when(documents.convertSource(identity, "doc_pdf", PDF_HASH))
                .thenReturn(new McpDocumentDataGateway.ConvertSource(pdf, true));
        assertThatThrownBy(() -> tools.invoke(identity, "finalize_pdf_conversion", finalizeArgs()))
                .isInstanceOf(McpToolException.class).hasMessage("DEPENDENCY_UNAVAILABLE");

        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/finalize"), any()))
                .thenReturn(finalized(PDF_HASH, RESULT_KEY));
        when(documents.convertSource(identity, "doc_pdf", PDF_HASH))
                .thenReturn(new McpDocumentDataGateway.ConvertSource(pdf, false));
        assertThatThrownBy(() -> tools.invoke(identity, "finalize_pdf_conversion", finalizeArgs()))
                .isInstanceOf(McpToolException.class).hasMessage("SOURCE_CHANGED");
        verify(plans, never()).propose(any(), any());
    }

    @Test
    void submitReportsFollowUpWindowsFromTheSharedRetryRule() {
        String windowId = UUID.randomUUID().toString();
        ObjectNode response = mapper.createObjectNode().put("schema_version", "mcp.pdf-conversion.v1")
                .put("conversion_id", CONVERSION).put("window_id", windowId).put("attempt", 1).put("max_attempts", 1)
                .put("accepted", false).put("submitted_window_count", 1).put("window_count", 3);
        response.putArray("rejected_block_ids").add("table-a");
        response.putArray("followup_block_ids").add("table-a").add("table-b");
        response.putArray("new_window_ordinals").add(2).add(3);
        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/windows/" + windowId + "/submissions"),
                any())).thenReturn(response);

        @SuppressWarnings("unchecked")
        var view = (Map<String, Object>) tools.invoke(identity, "submit_conversion_window", Map.of(
                "idempotency_key", "submit-1", "conversion_id", CONVERSION, "window_id", windowId,
                "window_hash", PDF_HASH, "result", Map.of("results", List.of())));

        assertThat(view.get("new_window_ordinals")).isEqualTo(List.of(2, 3));
        assertThat(view).doesNotContainKey("followup_block_ids");
    }

    @Test
    void writeToolsRequireTheSameLiveGrant() {
        when(access.authorizeProposal(any())).thenReturn(new McpIdentity("user", "workspace", "https://client.example/mcp",
                "00000000-0000-4000-8000-000000000001", 4, Set.of("write_propose"), "MEMBER", Set.of()));
        assertThatThrownBy(() -> tools.invoke(identity, "submit_conversion_window", Map.of()))
                .isInstanceOf(McpToolException.class).hasMessage("PERMISSION_REVOKED");
        verify(ai, never()).post(anyString(), any());
    }

    private Map<String, Object> start() {
        return Map.of("idempotency_key", "convert-1", "document_id", "doc_pdf", "source_content_hash", PDF_HASH);
    }

    private Map<String, Object> finalizeArgs() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("idempotency_key", "finalize-1");
        args.put("conversion_id", CONVERSION);
        args.put("display_name", null);
        return args;
    }

    private ObjectNode status(boolean needsDispatch) {
        ObjectNode node = mapper.createObjectNode().put("schema_version", "mcp.pdf-conversion.v1")
                .put("conversion_id", CONVERSION).put("state", "PREPARING").put("source_document_id", "doc_pdf")
                .put("source_content_hash", PDF_HASH).put("prepared_pages", 0);
        if (needsDispatch) node.put("dispatch_start_page", 0); else node.putNull("dispatch_start_page");
        node.putNull("total_pages");
        node.putNull("window_count");
        node.putNull("error_code");
        return node;
    }

    private ObjectNode window(String imageKey) {
        ObjectNode node = mapper.createObjectNode().put("schema_version", "mcp.pdf-conversion.v1")
                .put("conversion_id", CONVERSION).put("window_id", UUID.randomUUID().toString())
                .put("window_ordinal", 1).put("window_count", 1).put("window_hash", PDF_HASH).put("kind", "repair")
                .put("lane", "table").put("page", 1).put("instructions", "prompt").put("submitted_attempts", 0)
                .put("max_attempts", 2).put("data_boundary", "UNTRUSTED_WORKSPACE_CONTENT");
        node.putObject("payload");
        node.putObject("result_schema");
        node.putArray("image_keys").add(imageKey);
        return node;
    }

    private ObjectNode finalized(String contentHash, String resultKey) {
        ObjectNode node = mapper.createObjectNode().put("conversion_id", CONVERSION).put("source_document_id", "doc_pdf")
                .put("source_content_hash", PDF_HASH).put("content_hash", contentHash).put("result_key", resultKey);
        node.putObject("restoration_summary").put("broken_pages", 0).put("repaired_pages", 0)
                .putArray("fallback_pages");
        return node;
    }

    private Document pdf() {
        Document pdf = mock(Document.class);
        when(pdf.getId()).thenReturn("doc_pdf");
        when(pdf.getDisplayName()).thenReturn("보고서.pdf");
        when(pdf.getFolderId()).thenReturn(folder);
        when(pdf.getSourceUri()).thenReturn("s3://bucket/sources/documents/doc_pdf/original");
        return pdf;
    }
}
