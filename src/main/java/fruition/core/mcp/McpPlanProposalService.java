package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpPlanProposalService {
    static final String TOOL_NAME = "propose_plan";
    static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;
    private static final Pattern DOCUMENT_ID = Pattern.compile("(?:doc_|chatdoc_)[A-Za-z0-9][A-Za-z0-9_-]{0,246}");
    private static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Set<String> INPUT_FIELDS = Set.of(
            "idempotency_key", "request_summary", "target_folder_id", "operations", "artifacts");
    private static final Set<String> OPERATION_FIELDS = Set.of(
            "id", "sequence", "tool_name", "target_type", "target_id", "base_version",
            "source_parent_id", "destination_parent_id", "arguments", "reason", "depends_on");
    private static final Set<String> ARTIFACT_FIELDS = Set.of(
            "id", "content_hash", "purpose", "document_id", "base_version", "target", "markdown");
    private static final Map<String, Set<String>> ARGUMENT_FIELDS = Map.of(
            "create_folder", Set.of("name", "parent_folder_id"),
            "rename_folder", Set.of("folder_id", "name", "base_version"),
            "move_folder", Set.of("folder_id", "parent_folder_id", "position", "base_version"),
            "move_document", Set.of("document_id", "folder_id", "position", "base_version"),
            "rename_document", Set.of("document_id", "display_name", "base_version"),
            "create_document", Set.of("display_name", "folder_id", "content_artifact_id", "content_hash"),
            "apply_document_edit", Set.of(
                    "document_id", "base_version", "target", "content_artifact_id", "content_hash"));
    /** PDF 변환본 생성. 원본 PDF와 그 원본 hash를 승인 인자에 고정해 승인·실행 때 다시 확인한다. */
    static final Set<String> CONVERTED_CREATE_DOCUMENT_FIELDS = Set.of(
            "display_name", "folder_id", "content_artifact_id", "content_hash",
            "source_document_id", "source_content_hash");

    private final McpPlanContextService contexts;
    private final McpDocumentProposalService proposals;
    private final ObjectMapper mapper;

    public McpPlanProposalService(McpPlanContextService contexts, McpDocumentProposalService proposals,
                                  ObjectMapper mapper) {
        this.contexts = contexts;
        this.proposals = proposals;
        this.mapper = mapper;
    }

    public McpDocumentProposalService.Result propose(McpIdentity identity, Map<String, Object> arguments) {
        try {
            if (arguments == null || mapper.writeValueAsBytes(arguments).length > MAX_REQUEST_BYTES) throw invalid();
            Input input = input(arguments);
            proposals.requireReviewOrigin();
            McpProposalActor actor = McpDocumentProposalService.actor(
                    identity, UUID.randomUUID().toString(), TOOL_NAME);
            ContextRequest context = context(input.operations());
            contexts.read(actor, input.targetFolderId(), context.resources(), context.editDocuments());
            Map<String, Object> proposal = linked(
                    "instruction", null,
                    "request_summary", input.requestSummary(),
                    "target_folder_id", input.targetFolderId(),
                    "operations", input.operations(),
                    "artifacts", input.artifacts());
            return proposals.submit(identity,
                    McpDocumentProposalService.request(actor, input.idempotencyKey(), proposal, mapper));
        } catch (McpToolException e) {
            throw e;
        } catch (McpAuthorizationException e) {
            throw new McpToolException(e.code());
        } catch (IllegalArgumentException e) {
            throw new McpToolException("INVALID_INPUT");
        } catch (Exception e) {
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
    }

    static Input input(Map<String, Object> value) {
        if (value == null || !value.keySet().equals(INPUT_FIELDS)) throw invalid();
        String idempotencyKey = text(value.get("idempotency_key"), 255, false);
        String summary = text(value.get("request_summary"), 1000, true).strip();
        String targetFolderId = folderId(value.get("target_folder_id"));
        List<Map<String, Object>> operations = maps(value.get("operations"), 1, 20);
        List<Map<String, Object>> artifacts = maps(value.get("artifacts"), 0, 20);

        Set<String> operationIds = new HashSet<>();
        List<String> earlier = new ArrayList<>();
        Map<String, Map<String, Object>> earlierOperations = new HashMap<>();
        Map<String, Map<String, Object>> artifactById = new HashMap<>();
        int markdownBytes = 0;
        for (Map<String, Object> artifact : artifacts) {
            validateArtifact(artifact);
            String id = (String) artifact.get("id");
            if (artifactById.putIfAbsent(id, artifact) != null) throw invalid();
            markdownBytes = Math.addExact(markdownBytes, utf8((String) artifact.get("markdown")).length);
            if (markdownBytes > MAX_REQUEST_BYTES) throw invalid();
        }
        Set<String> usedArtifacts = new HashSet<>();
        for (int index = 0; index < operations.size(); index++) {
            Map<String, Object> operation = operations.get(index);
            validateOperation(operation, index + 1, earlier);
            String id = (String) operation.get("id");
            if (!operationIds.add(id)) throw invalid();
            Map<String, Object> operationArguments = map(operation.get("arguments"));
            validateResultReferenceBindings(operation, operationArguments, earlierOperations);
            Object artifactId = operationArguments.get("content_artifact_id");
            if (artifactId != null) {
                Map<String, Object> artifact = artifactById.get(artifactId);
                if (artifact == null || !usedArtifacts.add((String) artifactId)
                        || !operation.get("tool_name").equals(artifact.get("purpose"))
                        || !operationArguments.get("content_hash").equals(artifact.get("content_hash"))) {
                    throw invalid();
                }
                validateArtifactBinding(operation, artifact);
            }
            earlier.add(id);
            earlierOperations.put(id, operation);
        }
        if (usedArtifacts.size() != artifacts.size()) throw invalid();
        return new Input(idempotencyKey, summary, targetFolderId, operations, artifacts);
    }

    private static void validateOperation(Map<String, Object> operation, int expectedSequence, List<String> earlier) {
        if (!operation.keySet().equals(OPERATION_FIELDS)) throw invalid();
        String id = text(operation.get("id"), 255, true);
        if (integer(operation.get("sequence"), 1, 20) != expectedSequence) throw invalid();
        String tool = text(operation.get("tool_name"), 64, false);
        Set<String> fields = ARGUMENT_FIELDS.get(tool);
        if (fields == null) throw invalid();
        String expectedType = Set.of("create_folder", "rename_folder", "move_folder").contains(tool)
                ? "folder" : "document";
        if (!expectedType.equals(operation.get("target_type"))) throw invalid();
        boolean create = Set.of("create_folder", "create_document").contains(tool);
        Object targetId = operation.get("target_id");
        Object baseVersion = operation.get("base_version");
        if (create) {
            if (targetId != null || baseVersion != null || operation.get("source_parent_id") != null) throw invalid();
        } else {
            if (!(targetId instanceof String target)) throw invalid();
            if ("folder".equals(expectedType)) requireFolderId(target); else requireDocumentId(target);
            positiveLong(baseVersion);
        }
        folderId(operation.get("source_parent_id"));
        folderId(operation.get("destination_parent_id"));
        text(operation.get("reason"), 1000, true);
        List<String> dependsOn = strings(operation.get("depends_on"), 19);
        Set<String> dependencySet = new HashSet<>(dependsOn);
        if (dependencySet.size() != dependsOn.size() || !earlier.containsAll(dependsOn)
                || !earlier.stream().filter(dependencySet::contains).toList().equals(dependsOn)) throw invalid();
        Map<String, Object> arguments = map(operation.get("arguments"));
        if (!arguments.keySet().equals(fields) && !("create_document".equals(tool)
                && arguments.keySet().equals(CONVERTED_CREATE_DOCUMENT_FIELDS))) throw invalid();
        validateArguments(tool, operation, arguments, Set.copyOf(earlier), dependsOn);
        if (id.isEmpty()) throw invalid();
    }

    private static void validateResultReferenceBindings(Map<String, Object> operation,
                                                        Map<String, Object> arguments,
                                                        Map<String, Map<String, Object>> earlier) {
        String tool = (String) operation.get("tool_name");
        String destinationKey = Map.of(
                "create_folder", "parent_folder_id", "create_document", "folder_id",
                "move_folder", "parent_folder_id", "move_document", "folder_id").get(tool);
        if (destinationKey != null && arguments.get(destinationKey) instanceof Map<?, ?> reference) {
            Map<String, Object> dependency = earlier.get(reference.get("$operation_result"));
            if (dependency == null || !"create_folder".equals(dependency.get("tool_name"))) throw invalid();
        }
        if (arguments.get("base_version") instanceof Map<?, ?> reference) {
            Map<String, Object> dependency = earlier.get(reference.get("$operation_result"));
            if (dependency == null || !java.util.Objects.equals(
                    operation.get("target_id"), dependency.get("target_id"))) throw invalid();
        }
    }

    private static void validateArguments(String tool, Map<String, Object> operation,
                                          Map<String, Object> arguments, Set<String> earlier,
                                          List<String> dependsOn) {
        boolean folderTool = Set.of("create_folder", "rename_folder", "move_folder").contains(tool);
        boolean create = Set.of("create_folder", "create_document").contains(tool);
        String idKey = folderTool ? "folder_id" : "document_id";
        if (!create && !operation.get("target_id").equals(arguments.get(idKey))) throw invalid();
        if (!create) {
            Object argumentVersion = arguments.get("base_version");
            if (!(samePositiveLong(argumentVersion, operation.get("base_version"))
                    || resultRef(argumentVersion, "current_version", earlier, dependsOn))) throw invalid();
        }
        String destinationKey = Map.of(
                "create_folder", "parent_folder_id", "create_document", "folder_id",
                "move_folder", "parent_folder_id", "move_document", "folder_id").get(tool);
        if (destinationKey != null) {
            Object destination = arguments.get(destinationKey);
            if (resultRef(destination, "id", earlier, dependsOn)) {
                if (operation.get("destination_parent_id") != null) throw invalid();
            } else if (!java.util.Objects.equals(folderId(destination), operation.get("destination_parent_id"))) {
                throw invalid();
            }
        }
        if (arguments.containsKey("name")) text(arguments.get("name"), 255, true);
        if (arguments.containsKey("display_name")) text(arguments.get("display_name"), 255, true);
        if (arguments.containsKey("position") && arguments.get("position") != null)
            integer(arguments.get("position"), 0, Long.MAX_VALUE);
        if ("apply_document_edit".equals(tool)) target(arguments.get("target"));
        if (arguments.containsKey("content_artifact_id")) text(arguments.get("content_artifact_id"), 255, true);
        if (arguments.containsKey("content_hash") && !HASH.matcher(string(arguments.get("content_hash"))).matches())
            throw invalid();
        if (arguments.containsKey("source_document_id")) {
            requireDocumentId(string(arguments.get("source_document_id")));
            if (!HASH.matcher(string(arguments.get("source_content_hash"))).matches()) throw invalid();
        }
    }

    private static void validateArtifact(Map<String, Object> artifact) {
        if (!artifact.keySet().equals(ARTIFACT_FIELDS)) throw invalid();
        text(artifact.get("id"), 255, true);
        if (!HASH.matcher(string(artifact.get("content_hash"))).matches()) throw invalid();
        String purpose = string(artifact.get("purpose"));
        String markdown = string(artifact.get("markdown"));
        if (!("sha256:" + sha256(utf8(markdown))).equals(artifact.get("content_hash"))) throw invalid();
        if ("create_document".equals(purpose)) {
            if (artifact.get("document_id") != null || artifact.get("base_version") != null
                    || artifact.get("target") != null) throw invalid();
        } else if ("apply_document_edit".equals(purpose)) {
            requireDocumentId(string(artifact.get("document_id")));
            positiveLong(artifact.get("base_version"));
            target(artifact.get("target"));
        } else throw invalid();
    }

    private static void validateArtifactBinding(Map<String, Object> operation, Map<String, Object> artifact) {
        Map<String, Object> arguments = map(operation.get("arguments"));
        if ("apply_document_edit".equals(operation.get("tool_name"))) {
            if (!operation.get("target_id").equals(artifact.get("document_id"))
                    || !samePositiveLong(operation.get("base_version"), artifact.get("base_version"))
                    || !arguments.get("target").equals(artifact.get("target"))) throw invalid();
        }
    }

    private static ContextRequest context(List<Map<String, Object>> operations) {
        Map<String, McpPlanContextService.Resource> resources = new LinkedHashMap<>();
        List<McpPlanContextService.EditDocument> edits = new ArrayList<>();
        for (Map<String, Object> operation : operations) {
            String tool = (String) operation.get("tool_name");
            String target = (String) operation.get("target_id");
            if (target != null) {
                String type = (String) operation.get("target_type");
                resources.put(type + ":" + target, new McpPlanContextService.Resource(
                        type, target, ((Number) operation.get("base_version")).longValue()));
            }
            Map<String, Object> arguments = map(operation.get("arguments"));
            String destinationKey = Map.of(
                    "create_folder", "parent_folder_id", "create_document", "folder_id",
                    "move_folder", "parent_folder_id", "move_document", "folder_id")
                    .get(tool);
            Object destination = destinationKey == null ? null : arguments.get(destinationKey);
            if (destination instanceof String folder) {
                resources.putIfAbsent("folder:" + folder,
                        new McpPlanContextService.Resource("folder", folder, null));
            }
            if ("apply_document_edit".equals(tool)) {
                edits.add(new McpPlanContextService.EditDocument(target,
                        ((Number) operation.get("base_version")).longValue()));
            }
        }
        return new ContextRequest(List.copyOf(resources.values()), edits);
    }

    private static boolean resultRef(Object value, String field, Set<String> earlier, List<String> dependsOn) {
        if (!(value instanceof Map<?, ?> raw) || raw.size() != 2
                || !raw.keySet().equals(Set.of("$operation_result", "field"))
                || !(raw.get("$operation_result") instanceof String operationId)
                || !field.equals(raw.get("field"))) return false;
        return earlier.contains(operationId) && dependsOn.contains(operationId);
    }

    private static Map<String, Object> target(Object value) {
        Map<String, Object> target = map(value);
        if (!target.keySet().equals(Set.of("type", "start_line", "end_line"))
                || !Set.of("selection", "current_section", "whole_document").contains(target.get("type")))
            throw invalid();
        long start = positiveLong(target.get("start_line"));
        if (positiveLong(target.get("end_line")) < start) throw invalid();
        return target;
    }

    private static List<Map<String, Object>> maps(Object value, int min, int max) {
        if (!(value instanceof List<?> list) || list.size() < min || list.size() > max) throw invalid();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) result.add(new LinkedHashMap<>(map(item)));
        return result;
    }

    private static List<String> strings(Object value, int max) {
        if (!(value instanceof List<?> list) || list.size() > max) throw invalid();
        List<String> result = new ArrayList<>();
        for (Object item : list) result.add(text(item, 255, true));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) throw invalid();
        for (Object key : raw.keySet()) if (!(key instanceof String)) throw invalid();
        return (Map<String, Object>) raw;
    }

    private static String text(Object value, int max, boolean trimForBound) {
        String text = string(value);
        String checked = trimForBound ? text.strip() : text;
        if (checked.isBlank() || checked.codePointCount(0, checked.length()) > max) throw invalid();
        return text;
    }

    private static String string(Object value) {
        if (!(value instanceof String text)) throw invalid();
        return text;
    }

    private static String folderId(Object value) {
        if (value == null) return null;
        String text = string(value);
        requireFolderId(text);
        return text;
    }

    private static void requireFolderId(String value) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) throw invalid();
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private static void requireDocumentId(String value) {
        if (!DOCUMENT_ID.matcher(value).matches()) throw invalid();
    }

    private static long positiveLong(Object value) { return integer(value, 1, Long.MAX_VALUE); }

    private static boolean samePositiveLong(Object left, Object right) {
        try { return positiveLong(left) == positiveLong(right); }
        catch (IllegalArgumentException e) { return false; }
    }

    private static long integer(Object value, long min, long max) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long))
            throw invalid();
        long result = ((Number) value).longValue();
        if (result < min || result > max) throw invalid();
        return result;
    }

    private static byte[] utf8(String value) {
        try {
            var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw invalid();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2)
            result.put((String) values[index], values[index + 1]);
        return result;
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException(); }

    record Input(String idempotencyKey, String requestSummary, String targetFolderId,
                 List<Map<String, Object>> operations, List<Map<String, Object>> artifacts) {}
    private record ContextRequest(List<McpPlanContextService.Resource> resources,
                                  List<McpPlanContextService.EditDocument> editDocuments) {}
}
