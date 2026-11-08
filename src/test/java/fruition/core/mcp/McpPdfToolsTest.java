package fruition.core.mcp;

import fruition.core.document.domain.Document;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.McpDocumentDataGateway;
import fruition.shared.util.StorageProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpPdfToolsTest {
    private static final String PDF_HASH = "sha256:" + "a".repeat(64);
    private final McpDocumentDataGateway documents = mock(McpDocumentDataGateway.class);
    private final McpPlanProposalService plans = mock(McpPlanProposalService.class);
    private final MinioClient storage = mock(MinioClient.class);
    private final McpPdfTools tools;
    private final McpIdentity identity = new McpIdentity("user", "workspace", "https://client.example/mcp",
            "00000000-0000-4000-8000-000000000001", 3, Set.of("read", "write_propose"), "MEMBER", Set.of());
    private final UUID folder = UUID.fromString("00000000-0000-4000-8000-0000000000f1");

    McpPdfToolsTest() {
        StorageProperties properties = new StorageProperties();
        properties.setBucket("bucket");
        tools = new McpPdfTools(documents, plans, storage, properties);
    }

    @Test
    void sourceIssuesShortScopedUrlOnlyForReadablePdf() throws Exception {
        Document pdf = pdf();
        var viewer = mock(DocumentAccessPolicy.Viewer.class);
        when(documents.viewer(identity)).thenReturn(viewer);
        when(documents.find("workspace", "doc_pdf")).thenReturn(Optional.of(pdf));
        when(storage.getPresignedObjectUrl(any())).thenReturn("https://storage/signed");

        when(viewer.canRead(pdf)).thenReturn(false);
        assertThatThrownBy(() -> tools.invoke(identity, "get_pdf_source", Map.of("document_id", "doc_pdf")))
                .isInstanceOf(McpToolException.class).hasMessage("RESOURCE_NOT_FOUND");
        verifyNoInteractions(storage);

        when(viewer.canRead(pdf)).thenReturn(true);
        when(viewer.canConvert(pdf)).thenReturn(true);
        var result = (McpPdfTools.SourceResponse) tools.invoke(identity, "get_pdf_source", Map.of("document_id", "doc_pdf"));

        assertThat(result.sourceUrl()).isEqualTo("https://storage/signed");
        assertThat(result.sourceContentHash()).isEqualTo(PDF_HASH);
        assertThat(result.canConvert()).isTrue();
        ArgumentCaptor<GetPresignedObjectUrlArgs> args = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(storage).getPresignedObjectUrl(args.capture());
        assertThat(args.getValue().object()).isEqualTo("originals/doc_pdf.pdf");
        assertThat(args.getValue().expiry()).isEqualTo(600);
    }

    @Test
    void localSourceEndpointSignsForClientReachableHostWithoutStorageCall() {
        StorageProperties properties = new StorageProperties();
        properties.setBucket("bucket");
        properties.setAccessKey("access");
        properties.setSecretKey("secret");
        var reachable = new McpPdfTools(documents, plans, storage, properties, "http://127.0.0.1:59060");
        Document pdf = pdf();
        var viewer = mock(DocumentAccessPolicy.Viewer.class);
        when(documents.viewer(identity)).thenReturn(viewer);
        when(documents.find("workspace", "doc_pdf")).thenReturn(Optional.of(pdf));
        when(viewer.canRead(pdf)).thenReturn(true);

        var result = (McpPdfTools.SourceResponse) reachable.invoke(identity, "get_pdf_source",
                Map.of("document_id", "doc_pdf"));

        assertThat(result.sourceUrl()).startsWith("http://127.0.0.1:59060/bucket/originals/doc_pdf.pdf?")
                .contains("X-Amz-Expires=600");
        verifyNoInteractions(storage);
    }

    @Test
    void proposalFreezesSourceIdentityIntoValidPlanAndDefaultsToPdfFolder() {
        Document pdf = pdf();
        when(documents.convertSource(identity, "doc_pdf", PDF_HASH))
                .thenReturn(new McpDocumentDataGateway.ConvertSource(pdf, true));
        when(plans.propose(any(), any())).thenReturn(
                new McpDocumentProposalService.Result("run-1", "awaiting_approval", "https://review/run-1"));

        var result = (McpPdfTools.ProposeResponse) tools.invoke(identity, "propose_pdf_conversion",
                input("# 보고서\n\n![그림](data:image/png;base64,iVBORw0KGgo=)\n"));

        assertThat(result.reviewUrl()).isEqualTo("https://review/run-1");
        assertThat(result.nextStep()).contains("start_ingest");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> plan = ArgumentCaptor.forClass(Map.class);
        verify(plans).propose(org.mockito.ArgumentMatchers.eq(identity), plan.capture());
        // 실제 propose_plan 검증기를 통과해야 한다.
        var validated = McpPlanProposalService.input(plan.getValue());
        Map<?, ?> arguments = (Map<?, ?>) validated.operations().getFirst().get("arguments");
        assertThat(arguments.get("source_document_id")).isEqualTo("doc_pdf");
        assertThat(arguments.get("source_content_hash")).isEqualTo(PDF_HASH);
        assertThat(arguments.get("folder_id")).isEqualTo(folder.toString());
        assertThat(arguments.get("display_name")).isEqualTo("보고서");
        assertThat(validated.targetFolderId()).isEqualTo(folder.toString());
    }

    @Test
    void proposalRejectsUnsupportedImagesAndChangedSourceWithoutSubmitting() {
        for (String markdown : List.of("![x](https://evil.example/a.png)",
                "![x](/api/workspaces/other/assets/1/content)", "![x](data:image/webp;base64,AAAA)")) {
            assertThatThrownBy(() -> tools.invoke(identity, "propose_pdf_conversion", input(markdown)))
                    .isInstanceOf(McpToolException.class).hasMessage("INVALID_INPUT");
        }
        Document pdf = pdf();
        when(documents.convertSource(identity, "doc_pdf", PDF_HASH))
                .thenReturn(new McpDocumentDataGateway.ConvertSource(pdf, false));
        assertThatThrownBy(() -> tools.invoke(identity, "propose_pdf_conversion", input("# 본문")))
                .isInstanceOf(McpToolException.class).hasMessage("SOURCE_CHANGED");
        when(documents.convertSource(identity, "doc_pdf", PDF_HASH))
                .thenReturn(new McpDocumentDataGateway.ConvertSource(null, false));
        assertThatThrownBy(() -> tools.invoke(identity, "propose_pdf_conversion", input("# 본문")))
                .isInstanceOf(McpToolException.class).hasMessage("RESOURCE_NOT_FOUND");
        verifyNoInteractions(plans);
    }

    @Test
    void planValidatorRejectsPartialSourceBinding() {
        Document pdf = pdf();
        when(documents.convertSource(identity, "doc_pdf", PDF_HASH))
                .thenReturn(new McpDocumentDataGateway.ConvertSource(pdf, true));
        when(plans.propose(any(), any())).thenReturn(
                new McpDocumentProposalService.Result("run-1", "awaiting_approval", "https://review/run-1"));
        tools.invoke(identity, "propose_pdf_conversion", input("# 본문"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> plan = ArgumentCaptor.forClass(Map.class);
        verify(plans).propose(any(), plan.capture());
        @SuppressWarnings("unchecked")
        var operation = (Map<String, Object>) ((List<?>) plan.getValue().get("operations")).getFirst();
        @SuppressWarnings("unchecked")
        var arguments = new LinkedHashMap<>((Map<String, Object>) operation.get("arguments"));
        arguments.remove("source_content_hash");
        var partial = new LinkedHashMap<>(operation);
        partial.put("arguments", arguments);
        var broken = new LinkedHashMap<>(plan.getValue());
        broken.put("operations", List.of(partial));
        assertThatThrownBy(() -> McpPlanProposalService.input(broken)).isInstanceOf(IllegalArgumentException.class);
    }

    private Map<String, Object> input(String markdown) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("idempotency_key", "convert-1");
        input.put("source_document_id", "doc_pdf");
        input.put("source_content_hash", PDF_HASH);
        input.put("target_folder_id", null);
        input.put("display_name", null);
        input.put("markdown", markdown);
        return input;
    }

    private Document pdf() {
        Document pdf = mock(Document.class);
        when(pdf.getId()).thenReturn("doc_pdf");
        when(pdf.getDisplayName()).thenReturn("보고서.pdf");
        when(pdf.getFolderId()).thenReturn(folder);
        when(pdf.getMimeType()).thenReturn("application/pdf");
        when(pdf.getNormalizedFilename()).thenReturn("보고서.pdf");
        when(pdf.getSourceUri()).thenReturn("s3://bucket/originals/doc_pdf.pdf");
        when(pdf.getContentHash()).thenReturn("a".repeat(64));
        when(pdf.getByteSize()).thenReturn(1024L);
        return pdf;
    }
}
