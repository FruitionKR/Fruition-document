package fruition.core.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpDocumentProposalService {
    static final String TOOL_NAME = "propose_document_edit";
    private static final int MAX_TEXT_LENGTH = 1000;
    private static final int MAX_ID_LENGTH = 255;
    private static final int MAX_MARKDOWN_BYTES = 4 * 1024 * 1024;
    private static final Pattern DOCUMENT_ID = Pattern.compile("(?:doc_|chatdoc_)[A-Za-z0-9][A-Za-z0-9_-]{0,246}");
    private static final Set<String> INPUT_FIELDS = Set.of(
            "idempotency_key", "document_id", "base_version", "target", "instruction", "replacement_markdown");
    private static final Set<String> TARGET_FIELDS = Set.of("type", "start_line", "end_line");
    private static final Set<String> TARGET_TYPES = Set.of("selection", "current_section", "whole_document");
    private final McpDocumentEditContextService contexts;
    private final McpAgentProposalClient client;
    private final ObjectMapper mapper;
    private final URI reviewOrigin;

    public McpDocumentProposalService(McpDocumentEditContextService contexts, McpAgentProposalClient client,
                                      ObjectMapper mapper,
                                      @Value("${app.mcp.review-origin:}") String reviewOrigin) {
        this.contexts = contexts;
        this.client = client;
        this.mapper = mapper;
        this.reviewOrigin = reviewOrigin.isBlank() ? null : parseReviewOrigin(reviewOrigin);
    }

    public Result propose(McpIdentity identity, Map<String, Object> arguments) {
        try {
            Input input = input(arguments);
            if (reviewOrigin == null) throw new McpToolException("DEPENDENCY_UNAVAILABLE");
            McpProposalActor actor = actor(identity, UUID.randomUUID().toString(), TOOL_NAME);
            McpDocumentEditContextService.Response context = contexts.read(actor, input.documentId, input.baseVersion);
            validateContext(identity, input, context);
            Map<String, Object> request = request(actor, input, context.parentFolderId(), mapper);
            return submit(identity, request);
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

    static Input input(Map<String, Object> arguments) {
        if (arguments == null || !arguments.keySet().equals(INPUT_FIELDS)) throw new IllegalArgumentException();
        String idempotencyKey = bounded(arguments.get("idempotency_key"), MAX_ID_LENGTH, false);
        String documentId = bounded(arguments.get("document_id"), MAX_ID_LENGTH, false);
        if (!DOCUMENT_ID.matcher(documentId).matches()) throw new IllegalArgumentException();
        long baseVersion = positiveLong(arguments.get("base_version"));
        Object rawTarget = arguments.get("target");
        if (!(rawTarget instanceof Map<?, ?> target) || !target.keySet().equals(TARGET_FIELDS)) {
            throw new IllegalArgumentException();
        }
        String type = bounded(target.get("type"), 32, false);
        if (!TARGET_TYPES.contains(type)) throw new IllegalArgumentException();
        long startLine = positiveLong(target.get("start_line"));
        long endLine = positiveLong(target.get("end_line"));
        if (endLine < startLine) throw new IllegalArgumentException();
        String instruction = bounded(arguments.get("instruction"), MAX_TEXT_LENGTH, true);
        String replacement = string(arguments.get("replacement_markdown"));
        if (utf8(replacement).length > MAX_MARKDOWN_BYTES) throw new IllegalArgumentException();
        return new Input(idempotencyKey, documentId, baseVersion,
                new Target(type, startLine, endLine), instruction, replacement);
    }

    static Map<String, Object> request(McpProposalActor actor, Input input, String parentFolderId,
                                       ObjectMapper mapper) {
        String summary = input.instruction.strip();
        String contentHash = "sha256:" + sha256(utf8(input.replacementMarkdown));
        Map<String, Object> target = linked(
                "type", input.target.type, "start_line", input.target.startLine, "end_line", input.target.endLine);
        Map<String, Object> arguments = linked(
                "document_id", input.documentId, "base_version", input.baseVersion, "target", target,
                "content_artifact_id", "artifact-1", "content_hash", contentHash);
        Map<String, Object> operation = linked(
                "id", "op-1", "sequence", 1, "tool_name", "apply_document_edit", "target_type", "document",
                "target_id", input.documentId, "base_version", input.baseVersion,
                "source_parent_id", parentFolderId, "destination_parent_id", parentFolderId,
                "arguments", arguments, "reason", summary, "depends_on", List.of());
        Map<String, Object> artifact = linked(
                "id", "artifact-1", "content_hash", contentHash, "purpose", "apply_document_edit",
                "document_id", input.documentId, "base_version", input.baseVersion, "target", target,
                "markdown", input.replacementMarkdown);
        Map<String, Object> proposal = linked(
                "instruction", input.instruction, "request_summary", summary, "target_folder_id", null,
                "operations", List.of(operation), "artifacts", List.of(artifact));
        return request(actor, input.idempotencyKey, proposal, mapper);
    }

    static Map<String, Object> request(McpProposalActor actor, String idempotencyKey,
                                       Map<String, Object> proposal, ObjectMapper mapper) {
        Map<String, Object> actorMap = mapper.convertValue(actor, new TypeReference<>() {});
        Map<String, Object> hashActor = new LinkedHashMap<>(actorMap);
        hashActor.remove("invocation_id");
        Map<String, Object> canonical = linked(
                "schema_version", "mcp.agent-proposal.v1", "actor", hashActor, "proposal", proposal);
        String requestHash;
        try {
            byte[] bytes = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(canonical);
            requestHash = "sha256:" + sha256(bytes);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return linked(
                "schema_version", "mcp.agent-proposal.v1", "actor", actorMap,
                "idempotency_key", idempotencyKey, "request_hash", requestHash, "proposal", proposal);
    }

    static McpProposalActor actor(McpIdentity identity, String invocationId, String toolName) {
        if (identity == null || blank(identity.userId()) || blank(identity.workspaceId())
                || blank(identity.clientId()) || blank(identity.grantId()) || identity.policyGeneration() < 1) {
            throw new IllegalArgumentException();
        }
        return new McpProposalActor(invocationId, identity.grantId(), identity.userId(), identity.workspaceId(),
                identity.clientId(), identity.policyGeneration(), "MCP_CLIENT", "2025-11-25", toolName);
    }

    void requireReviewOrigin() {
        if (reviewOrigin == null) throw new McpToolException("DEPENDENCY_UNAVAILABLE");
    }

    Result submit(McpIdentity identity, Map<String, Object> request) {
        requireReviewOrigin();
        McpAgentProposalClient.Response response = client.create(request);
        String reviewUrl = UriComponentsBuilder.fromUri(reviewOrigin)
                .pathSegment("mcp", "workspaces", identity.workspaceId(), "runs", response.runId())
                .build().encode().toUriString();
        return new Result(response.runId(), response.status(), reviewUrl);
    }

    private static void validateContext(McpIdentity identity, Input input,
                                        McpDocumentEditContextService.Response context) {
        if (!input.documentId.equals(context.documentId()) || !identity.workspaceId().equals(context.workspaceId())
                || input.baseVersion != context.currentVersion() || context.currentMarkdown() == null) {
            throw new McpToolException("SOURCE_CHANGED");
        }
        long lineCount = context.currentMarkdown().split("\\r\\n|\\r|\\n", -1).length;
        if (input.target.endLine > lineCount
                || ("whole_document".equals(input.target.type)
                && (input.target.startLine != 1 || input.target.endLine != lineCount))) {
            throw new McpToolException("SOURCE_CHANGED");
        }
    }

    private static long positiveLong(Object value) {
        try {
            long result = value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                    ? ((Number) value).longValue()
                    : value instanceof BigInteger integer ? integer.longValueExact()
                    : value instanceof BigDecimal decimal ? decimal.longValueExact()
                    : -1;
            if (result < 1) throw new IllegalArgumentException();
            return result;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String bounded(Object value, int maxCodePoints, boolean trimForBound) {
        String string = string(value);
        String checked = trimForBound ? string.strip() : string;
        if (checked.isBlank() || checked.codePointCount(0, checked.length()) > maxCodePoints) {
            throw new IllegalArgumentException();
        }
        return string;
    }

    private static String string(Object value) {
        if (!(value instanceof String string)) throw new IllegalArgumentException();
        return string;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static URI parseReviewOrigin(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (!uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                throw new IllegalArgumentException();
            }
            return URI.create("https://" + uri.getAuthority());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("MCP review origin must be a canonical HTTPS origin");
        }
    }

    private static byte[] utf8(String value) {
        try {
            var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    record Input(String idempotencyKey, String documentId, long baseVersion, Target target,
                 String instruction, String replacementMarkdown) {}
    record Target(String type, long startLine, long endLine) {}
    public record Result(@com.fasterxml.jackson.annotation.JsonProperty("run_id") String runId, String status,
                         @com.fasterxml.jackson.annotation.JsonProperty("review_url") String reviewUrl) {}
}
