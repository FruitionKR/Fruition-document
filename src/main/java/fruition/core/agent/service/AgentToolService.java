package fruition.core.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import fruition.core.document.service.DocumentAccessPolicy;
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

import fruition.shared.logging.CommittedLog;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

@Service
public class AgentToolService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentToolService.class);

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
                    "document_id", "base_version", "target", "content_artifact_id", "content_hash"),
            McpAgentApprovalService.SKILL_TOOL, Set.of("skill_id", "expected_version", "scope_type", "name",
                    "description", "instructions_markdown", "capabilities", "allowed_tools", "targets",
                    "result_mode", "requires_source"));

    /** MCP 승인 계획 전용 PDF 변환본 생성 인자. 내부 Agent 실행 경로는 받지 않는다. */
    private static final Set<String> CONVERTED_CREATE_DOCUMENT_ARGUMENTS = Set.of(
            "display_name", "folder_id", "content_result_key", "content_hash",
            "source_document_id", "source_content_hash");

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
    private final DocumentAccessPolicy documentAccessPolicy;
    private McpAgentApprovalService mcpApprovals;

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
            DocumentAccessPolicy documentAccessPolicy) {
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
        this.documentAccessPolicy = documentAccessPolicy;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMcpApprovals(McpAgentApprovalService mcpApprovals) {
        this.mcpApprovals = mcpApprovals;
    }

    private fruition.core.skill.service.SkillService skills;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSkills(fruition.core.skill.service.SkillService skills) {
        this.skills = skills;
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
        try {
            Object result;
            if (request.mcpApprovalPresent() && Set.of("create_folder", "rename_folder", "move_folder",
                    "move_document", "rename_document", "create_document",
                    McpAgentApprovalService.SKILL_TOOL).contains(toolName)) {
                result = executeMcpMutation(toolName, request);
            } else {
                result = "apply_document_edit".equals(toolName) || "delete_document".equals(toolName)
                        ? dispatchExecute(toolName, request)
                        : transactionTemplate.execute(status -> dispatchExecute(toolName, request));
            }
            // 재시도한 호출도 같은 응답을 받는다. 처음 실행인지는 같은 requestId의 멱등 재생 로그로 구분한다.
            // 변경 도구만 이 경로로 온다. 어느 Agent 실행이 바꿨는지 문서·폴더 로그와 이어 준다.
            log.info("[Agent 도구 실행 응답] operation=agent_tool stage=responded runId={} operationId={} toolName={} workspaceId={} userId={}",
                    request.runId(), request.operationId(), CommittedLog.code(toolName), request.workspaceId(), request.userId());
            return result;
        } catch (RuntimeException e) {
            log.warn("[Agent 도구 실행 실패] operation=agent_tool stage=execute runId={} operationId={} toolName={} workspaceId={} userId={} errorType={} 원인=도구 실행이 끝나지 못해 해당 트랜잭션을 롤백했습니다. 같은 requestId의 공통 예외 로그로 거부와 장애를 구분합니다.",
                    request.runId(), request.operationId(), CommittedLog.code(toolName), request.workspaceId(), request.userId(), e.getClass().getSimpleName());
            throw e;
        }
    }

    private Object executeMcpMutation(String toolName, AgentToolExecuteRequest request) {
        boolean converted = "create_document".equals(toolName) && request.arguments().has("source_document_id");
        if (converted) {
            requireArguments(request.arguments(), CONVERTED_CREATE_DOCUMENT_ARGUMENTS);
            // MCP PDF 변환 조립 결과는 이 워크스페이스의 변환 결과 위치에서만 읽는다.
            String key = text(request.arguments(), "content_result_key");
            if (!key.startsWith("tmp/mcp-convert/" + request.workspaceId() + "/") || key.contains(".."))
                throw badRequest("PDF 변환 결과 위치가 올바르지 않습니다.");
        } else requireToolArguments(EXECUTE_TOOL_ARGUMENTS, toolName, request.arguments());
        authorizationClient.authorizeExecute(toolName, request);
        if (mcpApprovals == null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "MCP execution authorization is unavailable.");
        if (McpAgentApprovalService.SKILL_TOOL.equals(toolName)) return executeMcpSkillDefinition(request);
        JsonNode arguments = request.arguments();
        PipelineAgentArtifactClient.ResolvedArtifact artifact = null;
        if ("create_document".equals(toolName) && !converted) {
            artifact = artifactClient.resolve(request, toolName, arguments);
            requireResolvedArtifact(artifact, arguments, toolName);
        }
        McpAgentApprovalService.Execution execution = mcpApprovals.authorizeExecution(request, toolName);
        if (execution.replay() != null) return execution.replay();
        PipelineAgentArtifactClient.ResolvedArtifact resolvedArtifact = artifact;
        try {
            McpAgentApprovalService.Receipt receipt = transactionTemplate.execute(status -> switch (toolName) {
                case "create_folder" -> {
                    var created = folderService.createAgentInCurrentTransaction(request.workspaceId(),
                            text(arguments, "name"), nullableUuid(arguments, "parent_folder_id"));
                    yield mcpApprovals.recordReceipt(execution, request.operationId(), toolName, "FOLDER",
                            created.id().toString(), null, created.currentVersion(), true, created.updatedAt());
                }
                case "rename_folder" -> {
                    long before = positiveLong(arguments, "base_version");
                    var renamed = folderService.renameAgentInCurrentTransaction(request.workspaceId(),
                            uuid(arguments, "folder_id"), text(arguments, "name"), before);
                    yield mcpApprovals.recordReceipt(execution, request.operationId(), toolName, "FOLDER",
                            renamed.id().toString(), before, renamed.currentVersion(),
                            renamed.currentVersion() > before, renamed.updatedAt());
                }
                case "move_folder" -> {
                    long before = positiveLong(arguments, "base_version");
                    var moved = folderService.moveAgentInCurrentTransaction(request.workspaceId(),
                            uuid(arguments, "folder_id"), new FolderPositionRequest(
                                    nullableUuid(arguments, "parent_folder_id"),
                                    nullableInteger(arguments, "position"), before));
                    yield mcpApprovals.recordReceipt(execution, request.operationId(), toolName, "FOLDER",
                            moved.id().toString(), before, moved.currentVersion(),
                            moved.currentVersion() > before, moved.updatedAt());
                }
                case "create_document" -> {
                    var created = converted
                            ? documentService.createConvertedMarkdownInCurrentTransaction(
                                    request.workspaceId(), request.userId(), text(arguments, "source_document_id"),
                                    text(arguments, "source_content_hash"), text(arguments, "display_name"),
                                    nullableUuid(arguments, "folder_id"), text(arguments, "content_result_key"),
                                    text(arguments, "content_hash"))
                            : documentService.createAgentMarkdownInCurrentTransaction(
                                    request.workspaceId(), request.userId(), new MarkdownDocumentCreateRequest(
                                            text(arguments, "display_name"), resolvedArtifact.markdown(),
                                            nullableUuid(arguments, "folder_id")));
                    yield mcpApprovals.recordReceipt(execution, request.operationId(), toolName, "DOCUMENT",
                            created.id(), null, created.currentVersion(), true, created.uploadedAt());
                }
                case "rename_document" -> {
                    long before = positiveLong(arguments, "base_version");
                    var renamed = documentService.renameAgentInCurrentTransaction(
                            request.workspaceId(), request.userId(), text(arguments, "document_id"),
                            new DocumentRenameRequest(text(arguments, "display_name"), before));
                    yield mcpApprovals.recordReceipt(execution, request.operationId(), toolName, "DOCUMENT",
                            renamed.id(), before, renamed.currentVersion(), renamed.changed(), renamed.updatedAt());
                }
                case "move_document" -> {
                    long before = positiveLong(arguments, "base_version");
                    var moved = documentPlacementService.moveAgentInCurrentTransaction(
                            request.workspaceId(), text(arguments, "document_id"),
                            new DocumentPositionRequest(nullableUuid(arguments, "folder_id"),
                                    nullableInteger(arguments, "position"), before));
                    yield mcpApprovals.recordReceipt(execution, request.operationId(), toolName, "DOCUMENT",
                            moved.id(), before, moved.currentVersion(), moved.currentVersion() > before, Instant.now());
                }
                default -> throw badRequest("지원하지 않는 MCP mutation Tool입니다.");
            });
            mcpApprovals.executionFinished(execution);
            return receipt;
        } catch (RuntimeException failure) {
            mcpApprovals.executionFailed(execution, failure);
            throw failure;
        }
    }

    /**
     * 승인된 Skill 정의 operation. 현재 승인·grant·policy·skill.author·expected_version을 authorizeExecution에서 확인한 뒤
     * ai 수락 API(같은 run·operation이면 저장 증거 재생)로 저장하고, 그 증거로 Document 영수증을 남긴다.
     * ai 저장 뒤 영수증 전에 끊기면 같은 operation 재실행 또는 영수증 조회가 ai 증거로 영수증을 복구한다.
     */
    private Object executeMcpSkillDefinition(AgentToolExecuteRequest request) {
        if (skills == null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Skill service is unavailable.");
        McpAgentApprovalService.Execution execution =
                mcpApprovals.authorizeExecution(request, McpAgentApprovalService.SKILL_TOOL);
        if (execution.replay() != null) return execution.replay();
        try {
            JsonNode arguments = request.arguments();
            var definition = ((com.fasterxml.jackson.databind.node.ObjectNode) arguments.deepCopy());
            definition.remove("allowed_tools"); // ai가 capabilities로 다시 계산한다.
            JsonNode evidence = skills.acceptMcpDefinition(request.runId(), request.operationId(),
                    request.workspaceId(), request.userId(), definition);
            boolean update = arguments.path("skill_id").isTextual();
            Long before = update ? arguments.path("expected_version").longValue() : null;
            long after = evidence.path("version").asLong(-1);
            if (!"applied".equals(evidence.path("status").asText()) || !evidence.path("skill_id").isTextual()
                    || evidence.path("rolled_back").asBoolean(true) || after < 1
                    || evidence.path("created").asBoolean(update) == update
                    || (update && (!arguments.path("skill_id").asText().equals(evidence.path("skill_id").asText())
                    || after != before + 1))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Skill acceptance evidence does not match.");
            }
            McpAgentApprovalService.Receipt receipt = mcpApprovals.recordSkillReceipt(execution, request.operationId(),
                    evidence.path("skill_id").asText(), before, after);
            mcpApprovals.executionFinished(execution);
            return receipt;
        } catch (RuntimeException failure) {
            mcpApprovals.executionFailed(execution, failure);
            throw failure;
        }
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
        documentAccessPolicy.requireDelete(document, request.userId());
        long baseVersion = positiveLong(request.arguments(), "base_version");
        if (document.getDeletedAt() == null && document.getCurrentVersion() != baseVersion)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "문서가 변경되어 취소 삭제할 수 없습니다.");
        // 일반 삭제와 같다. 파생 데이터 정리는 document_deleted 명령으로 비동기 처리한다(ai ADR 0032).
        var deleted = documentService.delete(request.workspaceId(), request.userId(), documentId,
                request.idempotencyKey(), new DocumentLifecycleRequest(positiveLong(request.arguments(), "base_version")));
        return Map.of("id", deleted.id(), "current_version", deleted.currentVersion());
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
                        nullableUuid(arguments, "folder_id")),
                Document.AGENT_ORIGIN);
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
        if (!request.mcpApprovalPresent()) {
            requireEditableDocument(request.workspaceId(), request.userId(), documentId);
        }
        PipelineAgentArtifactClient.ResolvedArtifact artifact =
                artifactClient.resolve(request, "apply_document_edit", arguments);
        requireResolvedArtifact(artifact, arguments, "apply_document_edit");
        if (!documentId.equals(artifact.documentId())
                || artifact.baseVersion() == null || artifact.baseVersion() != baseVersion
                || !target.equals(artifact.target())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "승인된 Agent 문서 편집 대상과 일치하지 않습니다.");
        }
        if (!request.mcpApprovalPresent()) {
            prepareToolApply(request, documentId, baseVersion, artifact.markdown());
            return documentService.saveContentMergeOnly(
                    request.workspaceId(), request.userId(), documentId, artifact.markdown(),
                    baseVersion, request.idempotencyKey(), request.operationId());
        }
        if (mcpApprovals == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "MCP execution authorization is unavailable.");
        }
        McpAgentApprovalService.Execution execution = mcpApprovals.authorizeExecution(request, "apply_document_edit");
        if (execution.replay() != null) return execution.replay();
        try {
            prepareToolApply(request, documentId, baseVersion, artifact.markdown());
            McpAgentApprovalService.Receipt receipt = transactionTemplate.execute(status -> {
                var saved = documentService.saveAgentContentInCurrentTransaction(
                        request.workspaceId(), request.userId(), documentId, artifact.markdown(),
                        baseVersion, request.idempotencyKey(), request.operationId());
                return mcpApprovals.recordReceipt(execution, request.operationId(), documentId, saved);
            });
            mcpApprovals.executionFinished(execution);
            return receipt;
        } catch (RuntimeException failure) {
            mcpApprovals.executionFailed(execution, failure);
            throw failure;
        }
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

    private Document requireEditableDocument(String workspaceId, String userId, String documentId) {
        Document document = requireDocument(workspaceId, userId, documentId);
        documentAccessPolicy.requireEdit(document, userId);
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
