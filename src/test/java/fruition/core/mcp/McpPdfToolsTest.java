package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fruition.core.authz.WorkspaceSettingsClient;
import fruition.core.document.domain.Document;
import fruition.core.document.repository.ConvertCommandOutbox;
import fruition.core.document.service.McpDocumentDataGateway;
import fruition.shared.util.StorageProperties;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpPdfToolsTest {
    private static final String PDF_HASH = "sha256:" + "a".repeat(64);
    private static final String CONVERSION = "0f6f3d1e-2b7a-4c8e-9d5f-1a2b3c4d5e6f";
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpDocumentDataGateway documents = mock(McpDocumentDataGateway.class);
    private final McpPlanProposalService plans = mock(McpPlanProposalService.class);
    private final McpAccessClient access = mock(McpAccessClient.class);
    private final McpWikiDraftClient ai = mock(McpWikiDraftClient.class);
    private final ConvertCommandOutbox outbox = mock(ConvertCommandOutbox.class);
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
        tools = new McpPdfTools(documents, plans, access, ai, outbox, transaction, settings, storage, storage,
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
        verifyNoInteractions(ai, outbox);

        when(documents.convertSource(identity, "doc_pdf", PDF_HASH))
                .thenReturn(new McpDocumentDataGateway.ConvertSource(pdf, true));
        when(settings.describePdfFigures("workspace")).thenReturn(true);
        when(ai.post(eq("/internal/mcp/pdf-conversions"), any())).thenReturn(status(true));

        @SuppressWarnings("unchecked")
        var view = (Map<String, Object>) tools.invoke(identity, "start_pdf_conversion", start());
        tools.invoke(identity, "start_pdf_conversion", start());

        assertThat(view.get("state")).isEqualTo("PREPARING");
        verify(outbox, times(2)).enqueueExternalPrepare(CONVERSION, "workspace", "user",
                "doc_pdf", "sources/documents/doc_pdf/original", 10, true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(ai, times(2)).post(eq("/internal/mcp/pdf-conversions"), body.capture());
        // 같은 요청을 다시 보내면 invocation_id가 달라도 같은 request_hash라 AI가 같은 세션을 돌려준다.
        assertThat(body.getAllValues().get(0).get("request_hash")).isEqualTo(body.getAllValues().get(1).get("request_hash"));
        assertThat(body.getAllValues().get(0)).doesNotContainKeys("provider", "model");
    }

    @Test
    void windowSignsOnlyThisConversionsBundleImages() throws Exception {
        when(storage.getPresignedObjectUrl(any())).thenReturn("https://storage/signed");
        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/windows/read"), any()))
                .thenReturn(window("tmp/convert/mcp/workspace/" + CONVERSION + "/0/external/images/00000.png"));

        @SuppressWarnings("unchecked")
        var view = (Map<String, Object>) tools.invoke(identity, "get_conversion_window",
                Map.of("conversion_id", CONVERSION, "window_ordinal", 1));
        assertThat(view.get("images")).isEqualTo(List.of(Map.of("url", "https://storage/signed", "media_type", "image/png")));
        assertThat(view.get("instructions")).isEqualTo("prompt");
        verifyNoInteractions(access);

        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/windows/read"), any()))
                .thenReturn(window("tmp/convert/mcp/other/" + CONVERSION + "/0/x.png"));
        assertThatThrownBy(() -> tools.invoke(identity, "get_conversion_window",
                Map.of("conversion_id", CONVERSION, "window_ordinal", 1)))
                .isInstanceOf(McpToolException.class).hasMessage("DEPENDENCY_UNAVAILABLE");
    }

    @Test
    void finalizeProposesOnlyTheServerAssembledMarkdownLinkedToTheUnchangedPdf() throws Exception {
        String markdown = "<!-- page 1 -->\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n";
        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/finalize"), any()))
                .thenReturn(finalized("sha256:" + sha256(markdown)));
        when(storage.getObject(any(GetObjectArgs.class))).thenAnswer(invocation -> object(markdown));
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
        assertThat(arguments.get("source_document_id")).isEqualTo("doc_pdf");
        assertThat(arguments.get("source_content_hash")).isEqualTo(PDF_HASH);
        assertThat(arguments.get("folder_id")).isEqualTo(folder.toString());
        assertThat(arguments.get("display_name")).isEqualTo("보고서");
        assertThat(validated.artifacts().getFirst().get("markdown")).isEqualTo(markdown);
    }

    @Test
    void finalizeRefusesTamperedResultOrChangedSourceWithoutProposing() throws Exception {
        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/finalize"), any()))
                .thenReturn(finalized("sha256:" + "0".repeat(64)));
        when(storage.getObject(any(GetObjectArgs.class))).thenAnswer(invocation -> object("# 다른 본문\n"));
        Document pdf = pdf();
        when(documents.convertSource(identity, "doc_pdf", PDF_HASH))
                .thenReturn(new McpDocumentDataGateway.ConvertSource(pdf, true));
        assertThatThrownBy(() -> tools.invoke(identity, "finalize_pdf_conversion", finalizeArgs()))
                .isInstanceOf(McpToolException.class).hasMessage("DEPENDENCY_UNAVAILABLE");

        when(documents.convertSource(identity, "doc_pdf", PDF_HASH))
                .thenReturn(new McpDocumentDataGateway.ConvertSource(pdf, false));
        assertThatThrownBy(() -> tools.invoke(identity, "finalize_pdf_conversion", finalizeArgs()))
                .isInstanceOf(McpToolException.class).hasMessage("SOURCE_CHANGED");
        verify(plans, never()).propose(any(), any());
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
        args.put("target_folder_id", null);
        args.put("display_name", null);
        return args;
    }

    private ObjectNode status(boolean needsDispatch) {
        ObjectNode node = mapper.createObjectNode().put("schema_version", "mcp.pdf-conversion.v1")
                .put("conversion_id", CONVERSION).put("state", "PREPARING").put("source_document_id", "doc_pdf")
                .put("source_content_hash", PDF_HASH).put("prepared_pages", 0).put("needs_dispatch", needsDispatch);
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

    private ObjectNode finalized(String contentHash) {
        ObjectNode node = mapper.createObjectNode().put("conversion_id", CONVERSION).put("source_document_id", "doc_pdf")
                .put("source_content_hash", PDF_HASH).put("content_hash", contentHash)
                .put("result_key", "tmp/convert/mcp/workspace/" + CONVERSION + "/final.md");
        node.putObject("restoration_summary").put("broken_pages", 0).put("repaired_pages", 0)
                .putArray("fallback_pages");
        return node;
    }

    private static GetObjectResponse object(String markdown) {
        return new GetObjectResponse(Headers.of(), "bucket", null, "final.md",
                new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8)));
    }

    private Document pdf() {
        Document pdf = mock(Document.class);
        when(pdf.getId()).thenReturn("doc_pdf");
        when(pdf.getDisplayName()).thenReturn("보고서.pdf");
        when(pdf.getFolderId()).thenReturn(folder);
        when(pdf.getSourceUri()).thenReturn("s3://bucket/sources/documents/doc_pdf/original");
        return pdf;
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
