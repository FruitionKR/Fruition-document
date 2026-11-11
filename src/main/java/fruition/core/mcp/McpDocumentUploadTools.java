package fruition.core.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import fruition.core.document.exception.InvalidDocumentFilenameException;
import fruition.core.document.exception.InvalidMarkdownContentException;
import fruition.core.document.exception.MarkdownContentTooLargeException;
import fruition.core.document.exception.UnsupportedDocumentFileException;
import fruition.core.document.service.DocumentDirectUploadService;
import fruition.core.document.service.McpDocumentDataGateway;
import fruition.core.upload.UploadLimitException;
import fruition.shared.idempotency.IdempotencyConflictException;
import fruition.shared.idempotency.IdempotencyInProgressException;
import fruition.shared.idempotency.InvalidIdempotencyKeyException;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 웹 직접 업로드 API({@link DocumentDirectUploadService})를 MCP 신원으로 그대로 실행하는 얇은 도구. 웹과 같은 형식·크기·용량·
 * 권한 검사를 거쳐 complete에서 바로 문서가 된다(업로드는 AI 결과가 아니므로 웹 승인이 없다). 서버 자동 편입만 하지 않는다:
 * 편입·변환은 사용자 AI가 start_ingest·start_pdf_conversion으로 한다(ADR 0048).
 */
@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpDocumentUploadTools {
    static final String START = "start_document_upload";
    static final String PARTS = "get_upload_part_urls";
    static final String COMPLETE = "complete_document_upload";
    static final Set<String> NAMES = Set.of(START, PARTS, COMPLETE);
    /**
     * 승인은 웹이 아니라 MCP 클라이언트의 도구 실행 확인이 맡는다(ADR 0048). 쓰기로 표시해 클라이언트가 확인을 묻게 한다.
     * start는 부를 때마다 새 multipart·ticket을 만들고, part URL 재발급과 같은 key의 complete는 같은 결과다.
     */
    private static final McpSchema.ToolAnnotations WRITE = write(false);
    private static final McpSchema.ToolAnnotations IDEMPOTENT_WRITE = write(true);
    private static final String CONFIRM = " This saves the user's file into the workspace without a separate approval"
            + " step, so confirm with the user before uploading.";
    private static final String NO_HTTP = " If you cannot send HTTP requests yourself, do not use these tools and do not"
            + " paste file contents into other tools; ask the user to upload the file in the Fruition web app instead.";
    private static final String PUT_PARTS = "PUT the raw bytes of each part to its URL with no auth header (Content-Type is"
            + " not needed). Part n is the byte range starting at (n-1)*part_size, part_size bytes long (the last part is"
            + " shorter); a single-part file is the whole file, e.g. curl -X PUT -T ./file '<url>'. After every part is"
            + " uploaded, call complete_document_upload with the same ticket.";

    private final DocumentDirectUploadService uploads;
    private final McpDocumentDataGateway documents;
    private final ObjectMapper mapper;

    public McpDocumentUploadTools(DocumentDirectUploadService uploads, McpDocumentDataGateway documents,
                                  ObjectMapper mapper) {
        this.uploads = uploads; this.documents = documents; this.mapper = mapper;
    }

    public List<McpSchema.Tool> tools() {
        Map<String, Object> string = Map.of("type", "string");
        Map<String, Object> positive = Map.of("type", "integer", "minimum", 1);
        Map<String, Object> folder = Map.of("anyOf", List.of(Map.of("type", "string", "format", "uuid"), Map.of("type", "null")));
        Map<String, Object> startInput = new LinkedHashMap<>();
        startInput.put("filename", string);
        startInput.put("size", positive);
        startInput.put("folder_id", folder);
        Map<String, Object> startOutput = new LinkedHashMap<>();
        startOutput.put("ticket", string);
        startOutput.put("part_size", positive);
        startOutput.put("part_count", positive);
        startOutput.put("next_step", string);
        Map<String, Object> partsInput = new LinkedHashMap<>();
        partsInput.put("ticket", string);
        partsInput.put("first_part", positive);
        partsInput.put("count", Map.of("type", "integer", "minimum", 1, "maximum", 32));
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("part_number", positive);
        part.put("url", string);
        Map<String, Object> completeInput = new LinkedHashMap<>();
        completeInput.put("idempotency_key", string);
        completeInput.put("ticket", string);
        return List.of(
                McpSchema.Tool.builder(START, strict(startInput))
                        .description("Upload a local PDF, Markdown (.md/.markdown) or .txt file into the workspace through"
                                + " the same direct-upload API as Fruition web. Send the exact file name and byte size;"
                                + " folder_id null means the top level. PDF up to 100MB, Markdown/txt up to 5MB (UTF-8)."
                                + " Then call get_upload_part_urls and upload the parts. " + PUT_PARTS
                                + " The document is created on completion, like a web upload." + CONFIRM + NO_HTTP)
                        .outputSchema(strict(startOutput)).annotations(WRITE).build(),
                McpSchema.Tool.builder(PARTS, strict(partsInput))
                        .description("Get presigned PUT URLs (valid 15 minutes) for parts first_part.."
                                + "first_part+count-1 of a started upload, at most 32 per call. " + PUT_PARTS + NO_HTTP)
                        .outputSchema(strict(Map.of("parts", Map.of("type", "array", "items", strict(part)))))
                        .annotations(IDEMPOTENT_WRITE).build(),
                McpSchema.Tool.builder(COMPLETE, strict(completeInput))
                        .description("Finish an upload after every part was PUT. Fruition checks size, type and content"
                                + " the same way as web upload and creates the document immediately (no approval)."
                                + " Reuse the same idempotency_key when retrying. Fruition does not add the file to the"
                                + " wiki automatically: call start_ingest with the returned id for Markdown, or"
                                + " start_pdf_conversion with id and content_hash for a PDF." + CONFIRM + NO_HTTP)
                        .outputSchema(Map.of("type", "object")).annotations(IDEMPOTENT_WRITE).build());
    }

    public Object invoke(McpIdentity identity, String tool, Map<String, Object> args) {
        try {
            return switch (tool) {
                case START -> start(identity, args);
                case PARTS -> parts(identity, args);
                case COMPLETE -> complete(identity, args);
                default -> throw new McpToolException("INVALID_INPUT");
            };
        } catch (ResponseStatusException e) {
            throw new McpToolException(switch (e.getStatusCode().value()) {
                case 403 -> "HANDLE_NOT_FOUND";
                case 409 -> "UPLOAD_PARTS_INCOMPLETE";
                case 415 -> "INVALID_PDF";
                default -> "INVALID_INPUT";
            });
        } catch (UploadLimitException e) {
            throw new McpToolException(e.getCode());
        } catch (InvalidDocumentFilenameException e) {
            throw new McpToolException("INVALID_DOCUMENT_FILENAME");
        } catch (UnsupportedDocumentFileException e) {
            throw new McpToolException("UNSUPPORTED_FILE_TYPE");
        } catch (MarkdownContentTooLargeException e) {
            throw new McpToolException("MARKDOWN_CONTENT_TOO_LARGE");
        } catch (InvalidMarkdownContentException e) {
            throw new McpToolException("INVALID_MARKDOWN_CONTENT");
        } catch (DocumentWriteForbiddenException e) {
            throw new McpToolException("DOCUMENT_WRITE_FORBIDDEN");
        } catch (IdempotencyConflictException e) {
            throw new McpToolException("IDEMPOTENCY_CONFLICT");
        } catch (IdempotencyInProgressException e) {
            throw new McpToolException("IDEMPOTENCY_IN_PROGRESS");
        } catch (InvalidIdempotencyKeyException e) {
            throw new McpToolException("INVALID_INPUT");
        } catch (WorkspaceNotFoundException e) {
            throw new McpToolException("RESOURCE_NOT_FOUND");
        } catch (McpToolException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
    }

    private Map<String, Object> start(McpIdentity identity, Map<String, Object> args) throws Exception {
        fields(args, Set.of("filename", "size", "folder_id"));
        var response = uploads.start(identity.workspaceId(), identity.userId(),
                new DocumentDirectUploadService.StartRequest(text(args, "filename"), positive(args, "size"),
                        folderId(args)), null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticket", response.ticket());
        out.put("part_size", response.part_size());
        out.put("part_count", response.part_count());
        out.put("next_step", "Call get_upload_part_urls for parts 1.." + response.part_count() + ". " + PUT_PARTS);
        return out;
    }

    private Map<String, Object> parts(McpIdentity identity, Map<String, Object> args) throws Exception {
        fields(args, Set.of("ticket", "first_part", "count"));
        var response = uploads.partUrls(identity.workspaceId(), identity.userId(),
                new DocumentDirectUploadService.PartsRequest(text(args, "ticket"),
                        Math.toIntExact(positive(args, "first_part")), Math.toIntExact(positive(args, "count"))));
        return Map.of("parts", response.parts().stream()
                .map(p -> Map.<String, Object>of("part_number", p.part_number(), "url", p.url())).toList());
    }

    private Map<String, Object> complete(McpIdentity identity, Map<String, Object> args) throws Exception {
        fields(args, Set.of("idempotency_key", "ticket"));
        var response = uploads.completeWithoutAutoIngest(identity.workspaceId(), identity.userId(),
                text(args, "idempotency_key"), new DocumentDirectUploadService.CompleteRequest(text(args, "ticket")));
        Map<String, Object> out = new LinkedHashMap<>(mapper.convertValue(response, new TypeReference<Map<String, Object>>() {}));
        // 웹 응답에는 hash가 없다. start_pdf_conversion이 source_content_hash로 이 값을 요구하므로 MCP에서만 더한다.
        out.put("content_hash", documents.find(identity.workspaceId(), response.id())
                .map(McpDocumentDataGateway::pdfHash).orElse(null));
        out.put("next_step", "application/pdf".equals(response.mimeType())
                ? "The PDF is saved. To convert it, call start_pdf_conversion with document_id=id and source_content_hash=content_hash."
                : "The document is saved. To add it to the wiki, call start_ingest with document_id=id.");
        return out;
    }

    /** 감사 대상 추출용. start의 대상 폴더만 안전한 ID로 남긴다. */
    static List<String> targets(String tool, Map<String, Object> args) {
        if (!START.equals(tool) || args == null || !(args.get("folder_id") instanceof String folder)) return List.of();
        return List.of(UUID.fromString(folder).toString());
    }

    private static void fields(Map<String, Object> args, Set<String> keys) {
        if (args == null || !args.keySet().equals(keys)) throw new IllegalArgumentException();
    }

    private static String text(Map<String, Object> args, String key) {
        if (!(args.get(key) instanceof String value) || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }

    private static long positive(Map<String, Object> args, String key) {
        if (!(args.get(key) instanceof Number number) || number instanceof Double || number instanceof Float
                || number.longValue() < 1) throw new IllegalArgumentException();
        return number.longValue();
    }

    private static UUID folderId(Map<String, Object> args) {
        Object folder = args.get("folder_id");
        if (folder == null) return null;
        if (!(folder instanceof String value)) throw new IllegalArgumentException();
        return UUID.fromString(value);
    }

    private static McpSchema.ToolAnnotations write(boolean idempotent) {
        return McpSchema.ToolAnnotations.builder().readOnlyHint(false).destructiveHint(false)
                .idempotentHint(idempotent).openWorldHint(false).build();
    }

    private static Map<String, Object> strict(Map<String, Object> fields) {
        return Map.of("type", "object", "additionalProperties", false,
                "properties", fields, "required", List.copyOf(fields.keySet()));
    }
}
