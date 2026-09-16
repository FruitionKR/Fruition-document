package fruition.core.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.agent.dto.AgentToolExecuteRequest;
import fruition.core.agent.dto.AgentToolReadRequest;
import fruition.core.agent.repository.PipelineAgentToolAuthorizationClient;
import fruition.core.agent.repository.PipelineAgentArtifactClient;
import fruition.core.agent.repository.AgentRunCommandRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.dto.BreadcrumbResponse;
import fruition.core.document.dto.DocumentRenameResponse;
import fruition.core.document.dto.FolderPositionRequest;
import fruition.core.document.dto.FolderResponse;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.exception.InvalidMarkdownContentException;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import fruition.core.document.service.DocumentPlacementService;
import fruition.core.document.service.DocumentEditStateInitializer;
import fruition.core.document.service.DocumentService;
import fruition.core.document.service.FolderService;
import fruition.shared.idempotency.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentToolServiceTest {

    @Mock PipelineAgentToolAuthorizationClient authorizationClient;
    @Mock PipelineAgentArtifactClient artifactClient;
    @Mock AgentRunCommandRepository runCommandRepository;
    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock FolderService folderService;
    @Mock DocumentService documentService;
    @Mock DocumentPlacementService documentPlacementService;
    @Mock DocumentRepository documentRepository;
    @Mock FolderRepository folderRepository;
    @Mock DocumentEditStateRepository editStateRepository;
    @Mock DocumentEditStateInitializer editStateInitializer;
    @Mock IdempotencyService idempotencyService;
    @Mock TransactionTemplate transactionTemplate;
    @Mock fruition.core.aitask.service.AiTaskCancellationService taskCancellationClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentToolService service;

    @BeforeEach
    void setUp() {
        service = new AgentToolService(
                authorizationClient,
                artifactClient,
                runCommandRepository,
                workspaceAccessGuard,
                folderService,
                documentService,
                documentPlacementService,
                documentRepository,
                folderRepository,
                editStateRepository,
                editStateInitializer,
                idempotencyService,
                transactionTemplate, taskCancellationClient);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        org.mockito.Mockito.lenient().when(transactionTemplate.getTransactionManager()).thenReturn(transactionManager);
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        org.mockito.Mockito.lenient().when(transactionTemplate.execute(any(TransactionCallback.class)))
                .thenAnswer(invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
    }

    @Test
    void read_returnsPostgresMarkdownAndEditRevisionAfterAuthorization() throws Exception {
        Document document = org.mockito.Mockito.mock(Document.class);
        when(document.getDocumentRole()).thenReturn(DocumentRole.EDITABLE);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("document-1", "workspace-1"))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById("document-1")).thenReturn(Optional.of(
                new DocumentEditState("document-1", "# canonical", "sha256:abc", 7)));
        AgentToolReadRequest request = new AgentToolReadRequest(
                "run-1", "workspace-1", "user-1",
                objectMapper.readTree("{\"document_id\":\"document-1\"}"));

        Object result = service.read("get_document_content", request);

        Map<?, ?> content = (Map<?, ?>) result;
        assertThat(content.get("markdown")).isEqualTo("# canonical");
        assertThat(content.get("content_hash")).isEqualTo("sha256:abc");
        assertThat(content.get("edit_revision")).isEqualTo(7L);
        assertThat(content.containsKey("current_version")).isFalse();
        InOrder order = inOrder(authorizationClient, workspaceAccessGuard,
                editStateInitializer, editStateRepository);
        order.verify(authorizationClient).authorizeRead(request);
        order.verify(workspaceAccessGuard).requireMember("workspace-1", "user-1");
        order.verify(editStateInitializer).initializeIfNeeded(document);
        order.verify(editStateRepository).findById("document-1");
    }

    @Test
    void read_rejectsOriginalDocumentBeforeCanonicalStateAccess() throws Exception {
        Document document = org.mockito.Mockito.mock(Document.class);
        when(document.getDocumentRole()).thenReturn(DocumentRole.ORIGINAL);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("document-1", "workspace-1"))
                .thenReturn(Optional.of(document));
        AgentToolReadRequest request = new AgentToolReadRequest(
                "run-1", "workspace-1", "user-1",
                objectMapper.readTree("{\"document_id\":\"document-1\"}"));

        assertThatThrownBy(() -> service.read("get_document_content", request))
                .isInstanceOf(InvalidMarkdownContentException.class);

        verify(editStateInitializer, never()).initializeIfNeeded(any());
        verify(editStateRepository, never()).findById(anyString());
    }

    @Test
    void read_dispatchesSearchAndBreadcrumbAfterAiAuthorization() throws Exception {
        var searchArguments = objectMapper.readTree("{\"query\":\"보고서\"}");
        var breadcrumbArguments = objectMapper.readTree(
                "{\"folder_id\":null,\"document_id\":\"document-1\"}");
        var search = new fruition.core.document.dto.HierarchySearchResponse(List.of());
        var breadcrumb = new BreadcrumbResponse(List.of());
        when(folderService.search("workspace-1", "user-1", "보고서")).thenReturn(search);
        when(folderService.breadcrumb("workspace-1", "user-1", null, "document-1"))
                .thenReturn(breadcrumb);

        assertThat(service.read("search_hierarchy", readRequest(searchArguments))).isSameAs(search);
        assertThat(service.read("get_breadcrumb", readRequest(breadcrumbArguments))).isSameAs(breadcrumb);
        InOrder order = inOrder(authorizationClient, folderService);
        order.verify(authorizationClient).authorizeRead(any());
        order.verify(folderService).search("workspace-1", "user-1", "보고서");
        order.verify(authorizationClient).authorizeRead(any());
        order.verify(folderService).breadcrumb("workspace-1", "user-1", null, "document-1");
    }

    @Test
    void read_rejectsInvalidBreadcrumbArgumentsAfterAuthorization() throws Exception {
        for (String json : List.of(
                "{\"folder_id\":null,\"document_id\":null}",
                "{\"folder_id\":\"11111111-1111-1111-1111-111111111111\",\"document_id\":\"document-1\"}",
                "{\"folder_id\":\"not-a-uuid\",\"document_id\":null}")) {
            assertThatThrownBy(() -> service.read("get_breadcrumb", readRequest(objectMapper.readTree(json))))
                    .isInstanceOf(ResponseStatusException.class);
        }
        verify(authorizationClient, times(3)).authorizeRead(any());
        verifyNoInteractions(folderService);
    }

    @Test
    void read_rejectsBlankSearchQueryAfterAuthorization() throws Exception {
        for (String query : List.of("", "   ")) {
            assertThatThrownBy(() -> service.read(
                    "search_hierarchy", readRequest(objectMapper.readTree(
                            "{\"query\":\"" + query + "\"}"))))
                    .isInstanceOf(ResponseStatusException.class);
        }

        verify(authorizationClient, times(2)).authorizeRead(any());
        verifyNoInteractions(folderService);
    }

    @Test
    void read_keepsHierarchyQueriesBoundToRequestedWorkspace() throws Exception {
        var arguments = objectMapper.readTree("{\"query\":\"보고서\"}");
        var search = new fruition.core.document.dto.HierarchySearchResponse(List.of());
        when(folderService.search("workspace-2", "user-1", "보고서")).thenReturn(search);

        assertThat(service.read("search_hierarchy", new AgentToolReadRequest(
                "run-1", "workspace-2", "user-1", arguments))).isSameAs(search);

        verify(folderService).search("workspace-2", "user-1", "보고서");
        verify(folderService, never()).search("workspace-1", "user-1", "보고서");
    }

    @Test
    void read_keepsBreadcrumbQueriesBoundToRequestedWorkspace() throws Exception {
        var arguments = objectMapper.readTree(
                "{\"folder_id\":null,\"document_id\":\"document-1\"}");
        var breadcrumb = new BreadcrumbResponse(List.of());
        when(folderService.breadcrumb("workspace-2", "user-1", null, "document-1"))
                .thenReturn(breadcrumb);

        assertThat(service.read("get_breadcrumb", new AgentToolReadRequest(
                "run-1", "workspace-2", "user-1", arguments))).isSameAs(breadcrumb);

        verify(folderService).breadcrumb("workspace-2", "user-1", null, "document-1");
        verify(folderService, never()).breadcrumb("workspace-1", "user-1", null, "document-1");
    }

    @Test
    void read_loadsInternalArtifactsThroughScopedPipelineClient() throws Exception {
        AgentToolReadRequest request = readRequest(objectMapper.createObjectNode());
        when(artifactClient.list(request)).thenReturn(List.of());

        Object result = service.read("list_agent_run_artifacts", request);

        assertThat(result).isEqualTo(Map.of("items", List.of()));
        verify(authorizationClient).authorizeRead(request);
        verify(artifactClient).list(request);
    }

    @Test
    void execute_dispatchesOnlyStrictP0ContractAfterAiAuthorization() throws Exception {
        var arguments = objectMapper.readTree("{\"name\":\"새 폴더\",\"parent_folder_id\":null}");
        AgentToolExecuteRequest request = executeRequest(arguments);
        FolderResponse expected = new FolderResponse(
                UUID.randomUUID(), null, "새 폴더", 1, 1, null, null);
        when(folderService.create(eq("workspace-1"), eq("user-1"), eq("idem-1"), any()))
                .thenReturn(expected);

        Object result = service.execute("create_folder", request);

        assertThat(result).isSameAs(expected);
        InOrder order = inOrder(authorizationClient, folderService);
        order.verify(authorizationClient).authorizeExecute("create_folder", request);
        order.verify(folderService).create(eq("workspace-1"), eq("user-1"), eq("idem-1"), any());
    }

    @Test
    void execute_acceptsExactlyIntegralDecimalsAfterAiAuthorization() throws Exception {
        UUID folderId = UUID.randomUUID();
        var arguments = objectMapper.readTree("""
                {"folder_id":"%s","parent_folder_id":null,"position":1.0,"base_version":3.0}
                """.formatted(folderId));
        AgentToolExecuteRequest request = executeRequest(arguments);
        FolderResponse expected = new FolderResponse(folderId, null, "폴더", 1, 4, null, null);
        FolderPositionRequest expectedPosition = new FolderPositionRequest(null, 1, 3L);
        when(folderService.move("workspace-1", "user-1", folderId, "idem-1", expectedPosition))
                .thenReturn(expected);

        Object result = service.execute("move_folder", request);

        assertThat(result).isSameAs(expected);
        InOrder order = inOrder(authorizationClient, folderService);
        order.verify(authorizationClient).authorizeExecute("move_folder", request);
        order.verify(folderService).move("workspace-1", "user-1", folderId, "idem-1", expectedPosition);
    }

    @Test
    void execute_rejectsFractionalAndOutOfRangeNumbersAfterAiAuthorization() throws Exception {
        UUID folderId = UUID.randomUUID();
        for (String argumentsJson : List.of(
                "{\"folder_id\":\"%s\",\"parent_folder_id\":null,\"position\":1,\"base_version\":3.5}",
                "{\"folder_id\":\"%s\",\"parent_folder_id\":null,\"position\":2147483648,\"base_version\":3}",
                "{\"folder_id\":\"%s\",\"parent_folder_id\":null,\"position\":1,\"base_version\":9223372036854775808}"
        )) {
            AgentToolExecuteRequest request = executeRequest(
                    objectMapper.readTree(argumentsJson.formatted(folderId)));

            assertThatThrownBy(() -> service.execute("move_folder", request))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
            verify(authorizationClient).authorizeExecute("move_folder", request);
        }

        verifyNoInteractions(folderService);
    }

    @Test
    void execute_rejectsUnsupportedArtifactAndContentToolsWithoutAuthorization() {
        for (String toolName : new String[]{"unsupported_tool"}) {
            assertThatThrownBy(() -> service.execute(
                    toolName, executeRequest(objectMapper.createObjectNode())))
                    .isInstanceOf(ResponseStatusException.class);
        }

        verifyNoInteractions(authorizationClient, folderService, documentService, documentPlacementService);
    }

    @Test
    void execute_createsDocumentOnlyFromResolvedArtifactAfterApproval() throws Exception {
        var arguments = objectMapper.readTree(
                "{\"display_name\":\"새 문서\",\"folder_id\":null,"
                        + "\"content_artifact_id\":\"artifact-1\",\"content_hash\":\"sha256:abc\"}");
        AgentToolExecuteRequest request = executeRequest(arguments);
        var artifact = new PipelineAgentArtifactClient.ResolvedArtifact(
                "artifact-1", "sha256:abc", "create_document", null, null, null, "# 문서\n");
        var expected = org.mockito.Mockito.mock(fruition.core.document.dto.DocumentUploadResponse.class);
        when(artifactClient.resolve(request, "create_document", arguments)).thenReturn(artifact);
        when(documentService.createMarkdown(eq("workspace-1"), eq("user-1"), eq("idem-1"), any()))
                .thenReturn(expected);

        assertThat(service.execute("create_document", request)).isSameAs(expected);

        InOrder order = inOrder(authorizationClient, workspaceAccessGuard, artifactClient, runCommandRepository, documentService);
        order.verify(authorizationClient).authorizeExecute("create_document", request);
        order.verify(workspaceAccessGuard).requireMember("workspace-1", "user-1");
        order.verify(artifactClient).resolve(request, "create_document", arguments);
        order.verify(documentService).createMarkdown(eq("workspace-1"), eq("user-1"), eq("idem-1"), any());
    }

    @Test
    void execute_keepsCreateDocumentBoundToRequestedWorkspace() throws Exception {
        var arguments = objectMapper.readTree(
                "{\"display_name\":\"새 문서\",\"folder_id\":null,"
                        + "\"content_artifact_id\":\"artifact-1\",\"content_hash\":\"sha256:abc\"}");
        AgentToolExecuteRequest request = new AgentToolExecuteRequest(
                "run-1", "workspace-2", "user-1", "plan-1", 1, "a".repeat(64),
                "operation-1", "idem-1", arguments);
        var artifact = new PipelineAgentArtifactClient.ResolvedArtifact(
                "artifact-1", "sha256:abc", "create_document", null, null, null, "# 문서\n");
        when(artifactClient.resolve(request, "create_document", arguments)).thenReturn(artifact);

        service.execute("create_document", request);

        verify(documentService).createMarkdown(eq("workspace-2"), eq("user-1"), eq("idem-1"), any());
        verify(documentService, never()).createMarkdown(eq("workspace-1"), any(), any(), any());
    }

    @Test
    void execute_rejectsCreateDocumentWithoutArtifactReference() throws Exception {
        var arguments = objectMapper.readTree(
                "{\"display_name\":\"새 문서\",\"folder_id\":null,"
                        + "\"content_hash\":\"sha256:abc\"}");

        assertThatThrownBy(() -> service.execute("create_document", executeRequest(arguments)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));

        verifyNoInteractions(authorizationClient, artifactClient, documentService);
    }

    @Test
    void execute_appliesDocumentEditOnlyForApprovedArtifactTargetAndVersion() throws Exception {
        var arguments = objectMapper.readTree(
                "{\"document_id\":\"document-1\",\"base_version\":2,"
                        + "\"target\":{\"type\":\"whole_document\",\"start_line\":1,\"end_line\":3},"
                        + "\"content_artifact_id\":\"artifact-1\",\"content_hash\":\"sha256:abc\"}");
        AgentToolExecuteRequest request = executeRequest(arguments);
        Document document = org.mockito.Mockito.mock(Document.class);
        when(document.getUserId()).thenReturn("user-1");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("document-1", "workspace-1"))
                .thenReturn(Optional.of(document));
        var artifact = new PipelineAgentArtifactClient.ResolvedArtifact(
                "artifact-1", "sha256:abc", "apply_document_edit", "document-1", 2L,
                arguments.get("target"), "# 변경\n");
        var expected = org.mockito.Mockito.mock(fruition.core.document.dto.DocumentContentSaveResponse.class);
        when(artifactClient.resolve(request, "apply_document_edit", arguments)).thenReturn(artifact);
        when(documentService.saveContent(
                "workspace-1", "user-1", "document-1", "# 변경\n", 2L, "idem-1", "agent", "operation-1"))
                .thenReturn(expected);

        assertThat(service.execute("apply_document_edit", request)).isSameAs(expected);
        verify(transactionTemplate, never()).execute(any(TransactionCallback.class));

        InOrder order = inOrder(
                authorizationClient, workspaceAccessGuard, documentRepository,
                artifactClient, runCommandRepository, documentService);
        order.verify(authorizationClient).authorizeExecute("apply_document_edit", request);
        order.verify(workspaceAccessGuard).requireMember("workspace-1", "user-1");
        order.verify(documentRepository).findByIdAndWorkspaceIdAndDeletedAtIsNull("document-1", "workspace-1");
        order.verify(artifactClient).resolve(request, "apply_document_edit", arguments);
        order.verify(runCommandRepository).prepareToolApply(
                "agent-tool:run-1:operation-1", "workspace-1", "user-1", "document-1", 2L,
                "operation-1", "# 변경\n");
        order.verify(documentService).saveContent(
                "workspace-1", "user-1", "document-1", "# 변경\n", 2L, "idem-1", "agent", "operation-1");
    }

    @Test
    void execute_rejectsStaleOrCrossWorkspaceArtifactBindingBeforeWrite() throws Exception {
        var arguments = objectMapper.readTree(
                "{\"document_id\":\"document-1\",\"base_version\":2,"
                        + "\"target\":{\"type\":\"whole_document\",\"start_line\":1,\"end_line\":3},"
                        + "\"content_artifact_id\":\"artifact-1\",\"content_hash\":\"sha256:abc\"}");
        AgentToolExecuteRequest request = executeRequest(arguments);
        Document document = org.mockito.Mockito.mock(Document.class);
        when(document.getUserId()).thenReturn("user-1");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("document-1", "workspace-1"))
                .thenReturn(Optional.of(document));
        when(artifactClient.resolve(request, "apply_document_edit", arguments)).thenReturn(
                new PipelineAgentArtifactClient.ResolvedArtifact(
                        "artifact-1", "sha256:abc", "apply_document_edit", "document-1", 1L,
                        arguments.get("target"), "# 변경\n"));

        assertThatThrownBy(() -> service.execute("apply_document_edit", request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));

        verify(documentService, never()).saveContent(any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(runCommandRepository);
    }

    @Test
    void execute_rejectsCreateWhenWorkspaceMembershipFailsBeforeArtifactFetch() throws Exception {
        var arguments = objectMapper.readTree(
                "{\"display_name\":\"새 문서\",\"folder_id\":null,"
                        + "\"content_artifact_id\":\"artifact-1\",\"content_hash\":\"sha256:abc\"}");
        AgentToolExecuteRequest request = executeRequest(arguments);
        org.mockito.Mockito.doThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "workspace"))
                .when(workspaceAccessGuard).requireMember("workspace-1", "user-1");

        assertThatThrownBy(() -> service.execute("create_document", request))
                .isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(artifactClient, documentService);
    }

    @Test
    void execute_rejectsApplyForMissingDocumentBeforeArtifactFetch() throws Exception {
        var arguments = objectMapper.readTree(
                "{\"document_id\":\"document-1\",\"base_version\":2,"
                        + "\"target\":{\"type\":\"whole_document\",\"start_line\":1,\"end_line\":3},"
                        + "\"content_artifact_id\":\"artifact-1\",\"content_hash\":\"sha256:abc\"}");
        AgentToolExecuteRequest request = executeRequest(arguments);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("document-1", "workspace-1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute("apply_document_edit", request))
                .isInstanceOf(DocumentNotFoundException.class);

        verifyNoInteractions(artifactClient, runCommandRepository, documentService);
    }

    @Test
    void execute_rejectsApplyDocumentEditWithInvalidTargetBeforeArtifactResolution() throws Exception {
        var arguments = objectMapper.readTree(
                "{\"document_id\":\"document-1\",\"base_version\":2,"
                        + "\"target\":{\"type\":\"whole_document\",\"start_line\":1},"
                        + "\"content_artifact_id\":\"artifact-1\",\"content_hash\":\"sha256:abc\"}");

        assertThatThrownBy(() -> service.execute("apply_document_edit", executeRequest(arguments)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));

        verify(authorizationClient).authorizeExecute(eq("apply_document_edit"), any());
        verifyNoInteractions(artifactClient, runCommandRepository, documentService);
    }

    @Test
    void renameDocumentConsumesIdempotencyKeyAndReplaysStoredResponse() throws Exception {
        AgentToolExecuteRequest request = executeRequest(objectMapper.readTree(
                "{\"document_id\":\"document-1\",\"display_name\":\"새 이름\",\"base_version\":3}"));
        DocumentRenameResponse replay = new DocumentRenameResponse(
                "document-1", "새 이름.md", "새 이름", 4, Instant.now(), true);
        when(idempotencyService.requestHash("workspace-1", "document-1", "새 이름", "3"))
                .thenReturn("request-hash");
        when(idempotencyService.replay(
                eq("user-1"),
                eq("POST:/internal/agent/tools/execute/rename_document"),
                eq("idem-1"),
                eq("request-hash"),
                eq(DocumentRenameResponse.class)))
                .thenReturn(Optional.of(replay));

        Object result = service.execute("rename_document", request);

        assertThat(result).isSameAs(replay);
        verify(transactionTemplate).execute(any(TransactionCallback.class));
        verify(idempotencyService).validateKey("idem-1");
        verify(documentService, never()).rename(any(), any(), any(), any());
        verify(idempotencyService, never()).save(any(), any(), any(), any(), anyInt(), any(), any());
    }

    private AgentToolExecuteRequest executeRequest(com.fasterxml.jackson.databind.JsonNode arguments) {
        return new AgentToolExecuteRequest(
                "run-1", "workspace-1", "user-1", "plan-1", 1, "a".repeat(64),
                "operation-1", "idem-1", arguments);
    }

    private AgentToolReadRequest readRequest(com.fasterxml.jackson.databind.JsonNode arguments) {
        return new AgentToolReadRequest("run-1", "workspace-1", "user-1", arguments);
    }
}
