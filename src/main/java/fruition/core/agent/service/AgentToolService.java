package fruition.core.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.aitask.service.AiTaskCancellationService;
import fruition.core.document.dto.DocumentLifecycleRequest;
import fruition.core.agent.dto.AgentToolExecuteRequest;
import fruition.core.agent.dto.AgentToolReadRequest;
import fruition.core.agent.repository.PipelineAgentToolAuthorizationClient;
import fruition.core.agent.repository.PipelineAgentArtifactClient;
import fruition.core.agent.repository.AgentRunCommandRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.dto.DocumentPositionRequest;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.dto.DocumentRenameRequest;
import fruition.core.document.dto.DocumentRenameResponse;
import fruition.core.document.dto.FolderCreateRequest;
import fruition.core.document.dto.FolderPositionRequest;
import fruition.core.document.dto.FolderRenameRequest;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.exception.HierarchyItemNotFoundException;
import fruition.core.document.exception.DocumentWriteForbiddenException;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentToolService {

    private static final Map<String, Set<String>> READ_TOOL_ARGUMENTS = Map.of(
            "list_root_items", Set.of(),
            "list_folder_children", Set.of("folder_id"),
            "get_document_metadata", Set.of("document_id"),
            "get_document_content", Set.of("document_id"),
            "search_hierarchy", Set.of("query"),
            "get_breadcrumb", Set.of("folder_id", "document_id"));
    private static final Map<String, Set<String>> EXECUTE_TOOL_ARGUMENTS = Map.of(
            "create_folder", Set.of("name", "parent_folder_id"),
            "delete_folder", Set.of("folder_id", "base_version", "require_empty"),
            "delete_document", Set.of("document_id", "base_version"),
            "rename_folder", Set.of("folder_id", "name", "base_version"),
            "move_folder", Set.of("folder_id", "parent_folder_id", "position", "base_version"),
            "move_document", Set.of("document_id", "folder_id", "position", "base_version"),
            "rename_document", Set.of("document_id", "display_name", "base_version"),
            "create_document", Set.of("display_name", "folder_id", "content_artifact_id", "content_hash"),
            "apply_document_edit", Set.of(
                    "document_id", "base_version", "target", "content_artifact_id", "content_hash"));

    private final PipelineAgentToolAuthorizationClient authorizationClient;
    private final PipelineAgentArtifactClient artifactClient;
    private final AgentRunCommandRepository runCommandRepository;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final FolderService folderService;
    private final DocumentService documentService;
    private final DocumentPlacementService documentPlacementService;
    private final DocumentRepository documentRepository;
    private final FolderRepository folderRepository;
    private final DocumentEditStateRepository editStateRepository;
    private final DocumentEditStateInitializer editStateInitializer;
    private final IdempotencyService idempotencyService;
    private final TransactionTemplate transactionTemplate;
    private final AiTaskCancellationService taskCancellationClient;

    public AgentToolService(
            PipelineAgentToolAuthorizationClient authorizationClient,
            PipelineAgentArtifactClient artifactClient,
            AgentRunCommandRepository runCommandRepository,
            WorkspaceAccessGuard workspaceAccessGuard,
            FolderService folderService,
            DocumentService documentService,
            DocumentPlacementService documentPlacementService,
            DocumentRepository documentRepository,
            FolderRepository folderRepository,
            DocumentEditStateRepository editStateRepository,
            DocumentEditStateInitializer editStateInitializer,
            IdempotencyService idempotencyService,
            TransactionTemplate transactionTemplate,
            AiTaskCancellationService taskCancellationClient) {
        this.authorizationClient = authorizationClient;
        this.artifactClient = artifactClient;
        this.runCommandRepository = runCommandRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.folderService = folderService;
        this.documentService = documentService;
        this.documentPlacementService = documentPlacementService;
        this.documentRepository = documentRepository;
        this.folderRepository = folderRepository;
        this.editStateRepository = editStateRepository;
        this.editStateInitializer = editStateInitializer;
        this.idempotencyService = idempotencyService;
        this.transactionTemplate = transactionTemplate;
        this.taskCancellationClient = taskCancellationClient;
    }

    public Object read(String toolName, AgentToolReadRequest request) {
        if ("list_agent_run_artifacts".equals(toolName)) {
            requireArguments(request.arguments(), Set.of());
            authorizationClient.authorizeRead(request);
            return Map.of("items", artifactClient.list(request));
        }
        requireToolArguments(READ_TOOL_ARGUMENTS, toolName, request.arguments());
        authorizationClient.authorizeRead(request);
        return switch (toolName) {
            case "list_root_items" -> folderService.children(request.workspaceId(), request.userId(), null);
            case "list_folder_children" -> folderService.children(
                    request.workspaceId(), request.userId(), uuid(request.arguments(), "folder_id"));
            case "get_document_metadata" -> documentMetadata(request);
            case "get_document_content" -> documentContent(request);
            case "search_hierarchy" -> folderService.search(
                    request.workspaceId(), request.userId(), text(request.arguments(), "query"));
            case "get_breadcrumb" -> breadcrumb(request);
            default -> throw badRequest("지원하지 않는 read Tool입니다.");
        };
    }

    public Object execute(String toolName, AgentToolExecuteRequest request) {
        if ("apply_document_edit".equals(toolName) || "delete_document".equals(toolName)) {
            return dispatchExecute(toolName, request);
        }
        return transactionTemplate.execute(status -> dispatchExecute(toolName, request));
    }

    private Object dispatchExecute(String toolName, AgentToolExecuteRequest request) {
        requireToolArguments(EXECUTE_TOOL_ARGUMENTS, toolName, request.arguments());
        authorizationClient.authorizeExecute(toolName, request);
        JsonNode arguments = request.arguments();
        return switch (toolName) {
            case "create_folder" -> folderService.create(
                    request.workspaceId(), request.userId(), request.idempotencyKey(),
                    new FolderCreateRequest(text(arguments, "name"), nullableUuid(arguments, "parent_folder_id")));
            case "delete_folder" -> {
                if (!arguments.path("require_empty").isBoolean() || !arguments.path("require_empty").booleanValue()) {
                    throw badRequest("취소 삭제는 require_empty=true가 필요합니다.");
                }
                yield folderService.deleteEmpty(request.workspaceId(), request.userId(), uuid(arguments, "folder_id"),
                        request.idempotencyKey(), positiveLong(arguments, "base_version"));
            }
            case "rename_folder" -> folderService.rename(
                    request.workspaceId(), request.userId(), uuid(arguments, "folder_id"), request.idempotencyKey(),
                    new FolderRenameRequest(text(arguments, "name"), positiveLong(arguments, "base_version")));
            case "move_folder" -> folderService.move(
                    request.workspaceId(), request.userId(), uuid(arguments, "folder_id"), request.idempotencyKey(),
                    new FolderPositionRequest(nullableUuid(arguments, "parent_folder_id"),
                            nullableInteger(arguments, "position"), positiveLong(arguments, "base_version")));
            case "move_document" -> documentPlacementService.move(
                    request.workspaceId(), request.userId(), text(arguments, "document_id"),
                    request.idempotencyKey(), new DocumentPositionRequest(
                            nullableUuid(arguments, "folder_id"), nullableInteger(arguments, "position"),
                            positiveLong(arguments, "base_version")));
            case "rename_document" -> renameDocument(request);
            case "create_document" -> createDocument(request);
            case "delete_document" -> deleteDocument(request);
            case "apply_document_edit" -> applyDocumentEdit(request);
            default -> throw badRequest("지원하지 않는 mutation Tool입니다.");
        };
    }

    private Object deleteDocument(AgentToolExecuteRequest request) {
        String documentId = text(request.arguments(), "document_id");
        workspaceAccessGuard.requireMember(request.workspaceId(), request.userId());
        Document document = documentRepository.findById(documentId)
                .filter(value -> request.workspaceId().equals(value.getWorkspaceId()))
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!request.userId().equals(document.getUserId()))
            throw new DocumentWriteForbiddenException("문서 소유자만 변경할 수 있습니다.");
        long baseVersion = positiveLong(request.arguments(), "base_version");
        if (document.getDeletedAt() == null && document.getCurrentVersion() != baseVersion)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "문서가 변경되어 취소 삭제할 수 없습니다.");
        // 파생 작업과 늦은 결과를 먼저 차단·복구한다. 삭제 이벤트가 복구할 데이터를 먼저 지우지 않게 한다.
        if (document.getDeletedAt() == null
                && !taskCancellationClient.cancelDocumentTasks(documentId, request.workspaceId(), request.userId())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "문서 파생 작업을 복구하고 있습니다.");
        }
        var deleted = documentService.delete(request.workspaceId(), request.userId(), documentId,
                request.idempotencyKey(), new DocumentLifecycleRequest(positiveLong(request.arguments(), "base_version")));
        return Map.of("id", deleted.id(), "current_version", deleted.currentVersion(), "cleanup_complete", true);
    }

    private Object createDocument(AgentToolExecuteRequest request) {
        JsonNode arguments = request.arguments();
        authorizeCreateResource(request, nullableUuid(arguments, "folder_id"));
        PipelineAgentArtifactClient.ResolvedArtifact artifact =
                artifactClient.resolve(request, "create_document", arguments);
        requireResolvedArtifact(artifact, arguments, "create_document");
        return documentService.createMarkdown(
                request.workspaceId(), request.userId(), request.idempotencyKey(),
                new MarkdownDocumentCreateRequest(
                        text(arguments, "display_name"), artifact.markdown(),
                        nullableUuid(arguments, "folder_id")));
    }

    private Object applyDocumentEdit(AgentToolExecuteRequest request) {
        JsonNode arguments = request.arguments();
        String documentId = text(arguments, "document_id");
        long baseVersion = positiveLong(arguments, "base_version");
        JsonNode target = arguments.get("target");
        if (!target.isObject() || target.size() != 3
                || !target.has("type") || !target.has("start_line") || !target.has("end_line")) {
            throw badRequest("target 값이 올바르지 않습니다.");
        }
        requireOwnedDocument(request.workspaceId(), request.userId(), documentId);
        PipelineAgentArtifactClient.ResolvedArtifact artifact =
                artifactClient.resolve(request, "apply_document_edit", arguments);
        requireResolvedArtifact(artifact, arguments, "apply_document_edit");
        if (!documentId.equals(artifact.documentId())
                || artifact.baseVersion() == null || artifact.baseVersion() != baseVersion
                || !target.equals(artifact.target())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "승인된 Agent 문서 편집 대상과 일치하지 않습니다.");
        }
        prepareToolApply(request, documentId, baseVersion, artifact.markdown());
        return documentService.saveContent(
                request.workspaceId(), request.userId(), documentId, artifact.markdown(),
                baseVersion, request.idempotencyKey(), "agent", request.operationId());
    }

    private void prepareToolApply(
            AgentToolExecuteRequest request,
            String documentId,
            long baseVersion,
            String markdown
    ) {
        Runnable prepare = () -> runCommandRepository.prepareToolApply(
                "agent-tool:" + request.runId() + ":" + request.operationId(),
                request.workspaceId(), request.userId(), documentId, baseVersion,
                request.operationId(), markdown);
        TransactionTemplate requiresNew = new TransactionTemplate(transactionTemplate.getTransactionManager());
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(status -> prepare.run());
    }

    private void authorizeCreateResource(AgentToolExecuteRequest request, UUID folderId) {
        workspaceAccessGuard.requireMember(request.workspaceId(), request.userId());
        if (folderId != null) {
            folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(folderId, request.workspaceId())
                    .orElseThrow(() -> new HierarchyItemNotFoundException("대상 폴더를 찾을 수 없습니다."));
        }
    }

    private Document requireOwnedDocument(String workspaceId, String userId, String documentId) {
        Document document = requireDocument(workspaceId, userId, documentId);
        if (!userId.equals(document.getUserId())) {
            throw new DocumentWriteForbiddenException("문서 소유자만 변경할 수 있습니다.");
        }
        return document;
    }

    private static void requireResolvedArtifact(
            PipelineAgentArtifactClient.ResolvedArtifact artifact,
            JsonNode arguments,
            String purpose) {
        if (artifact == null
                || !text(arguments, "content_artifact_id").equals(artifact.id())
                || !text(arguments, "content_hash").equals(artifact.contentHash())
                || !purpose.equals(artifact.purpose())
                || artifact.markdown() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Agent artifact가 승인된 operation과 일치하지 않습니다.");
        }
    }

    private Map<String, Object> documentMetadata(AgentToolReadRequest request) {
        Document document = requireDocument(request.workspaceId(), request.userId(),
                text(request.arguments(), "document_id"));
        return Map.of(
                "id", document.getId(),
                "display_name", document.getDisplayName(),
                "current_version", document.getCurrentVersion());
    }

    private Map<String, Object> documentContent(AgentToolReadRequest request) {
        String documentId = text(request.arguments(), "document_id");
        Document document = requireDocument(request.workspaceId(), request.userId(), documentId);
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new InvalidMarkdownContentException("편집 가능한 Markdown 문서만 본문을 조회할 수 있습니다.");
        }
        editStateInitializer.initializeIfNeeded(document);
        DocumentEditState state = editStateRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "문서의 PostgreSQL 편집 상태를 찾을 수 없습니다."));
        return Map.of(
                "id", state.getDocumentId(),
                "markdown", state.getMarkdown(),
                "content_hash", state.getContentHash(),
                "edit_revision", state.getRevision());
    }

    private Object breadcrumb(AgentToolReadRequest request) {
        JsonNode arguments = request.arguments();
        UUID folderId = nullableUuid(arguments, "folder_id");
        String documentId = nullableText(arguments, "document_id");
        if ((folderId == null) == (documentId == null)) {
            throw badRequest("folder_id 또는 document_id 중 하나만 지정해야 합니다.");
        }
        return folderService.breadcrumb(request.workspaceId(), request.userId(), folderId, documentId);
    }

    private Document requireDocument(String workspaceId, String userId, String documentId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        return documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    private DocumentRenameResponse renameDocument(AgentToolExecuteRequest request) {
        JsonNode arguments = request.arguments();
        String documentId = text(arguments, "document_id");
        String displayName = text(arguments, "display_name");
        long baseVersion = positiveLong(arguments, "base_version");
        idempotencyService.validateKey(request.idempotencyKey());
        String scope = "POST:/internal/agent/tools/execute/rename_document";
        String requestHash = idempotencyService.requestHash(
                request.workspaceId(), documentId, displayName, String.valueOf(baseVersion));
        Optional<DocumentRenameResponse> replay = idempotencyService.replay(
                request.userId(), scope, request.idempotencyKey(), requestHash, DocumentRenameResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        DocumentRenameResponse response = documentService.rename(
                request.workspaceId(), request.userId(), documentId,
                new DocumentRenameRequest(displayName, baseVersion));
        idempotencyService.save(
                request.userId(), scope, request.idempotencyKey(), requestHash, 200, documentId, response);
        return response;
    }

    private static void requireToolArguments(
            Map<String, Set<String>> contracts,
            String toolName,
            JsonNode arguments) {
        Set<String> fields = contracts.get(toolName);
        if (fields == null) {
            throw badRequest("허용되지 않은 Agent Tool입니다.");
        }
        requireArguments(arguments, fields);
    }

    private static void requireArguments(JsonNode arguments, Set<String> fields) {
        if (!arguments.isObject() || arguments.size() != fields.size()
                || !fields.stream().allMatch(arguments::has)) {
            throw badRequest("Agent Tool arguments가 계약과 일치하지 않습니다.");
        }
    }

    private static String text(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw badRequest(field + " 값이 올바르지 않습니다.");
        }
        return value.textValue();
    }

    private static String nullableText(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        return value.isNull() ? null : text(arguments, field);
    }

    private static UUID uuid(JsonNode arguments, String field) {
        try {
            return UUID.fromString(text(arguments, field));
        } catch (IllegalArgumentException e) {
            throw badRequest(field + " 값이 UUID가 아닙니다.");
        }
    }

    private static UUID nullableUuid(JsonNode arguments, String field) {
        return arguments.get(field).isNull() ? null : uuid(arguments, field);
    }

    private static long positiveLong(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (!isExactInteger(value) || !value.canConvertToLong() || value.longValue() < 1) {
            throw badRequest(field + " 값은 1 이상이어야 합니다.");
        }
        return value.longValue();
    }

    private static Integer nullableInteger(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value.isNull()) {
            return null;
        }
        if (!isExactInteger(value) || !value.canConvertToInt() || value.intValue() < 0) {
            throw badRequest(field + " 값은 0 이상이어야 합니다.");
        }
        return value.intValue();
    }

    private static boolean isExactInteger(JsonNode value) {
        return value != null && value.isNumber()
                && value.decimalValue().stripTrailingZeros().scale() <= 0;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
