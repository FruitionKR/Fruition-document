package fruition.core.document.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.dto.DocumentContentSaveMetadata;
import fruition.core.document.exception.InvalidMarkdownContentException;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocumentAssetSaveRequestParser {

    /** placeholder UUID는 프론트가 만든다. UUID 형태만 강제하고 version·variant는 제한하지 않는다. */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "attachment://([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
    );
    private static final String PART_PREFIX = "attachment_";

    private final ObjectMapper objectMapper;

    public DocumentAssetSaveRequestParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedAssetSaveRequest parse(String metadataJson, MultiValueMap<String, MultipartFile> fileParts) {
        DocumentContentSaveMetadata metadata = parseMetadata(metadataJson);
        if (metadata.markdown() == null || metadata.baseVersion() == null || metadata.baseVersion() < 1) {
            throw invalid("metadata에는 markdown과 1 이상의 base_version이 필요합니다.");
        }

        Set<UUID> placeholders = extractPlaceholders(metadata.markdown());
        Map<UUID, MultipartFile> attachments = new LinkedHashMap<>();
        fileParts.forEach((partName, files) -> {
            if (!partName.startsWith(PART_PREFIX)) {
                throw invalid("지원하지 않는 file part입니다: " + partName);
            }
            UUID attachmentId = parsePartId(partName);
            if (files.size() != 1 || attachments.putIfAbsent(attachmentId, files.getFirst()) != null) {
                throw invalid("같은 attachment file part를 중복해서 보낼 수 없습니다.");
            }
        });

        if (!placeholders.equals(attachments.keySet())) {
            throw invalid("attachment placeholder와 file part가 정확히 대응해야 합니다.");
        }
        return new ParsedAssetSaveRequest(metadata.markdown(), metadata.baseVersion(), Map.copyOf(attachments));
    }

    private DocumentContentSaveMetadata parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            throw invalid("metadata part가 필요합니다.");
        }
        try {
            return objectMapper.readValue(metadataJson, DocumentContentSaveMetadata.class);
        } catch (JsonProcessingException exception) {
            throw invalid("metadata JSON 형식이 올바르지 않습니다.");
        }
    }

    private Set<UUID> extractPlaceholders(String markdown) {
        Set<UUID> placeholders = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(markdown);
        while (matcher.find()) placeholders.add(UUID.fromString(matcher.group(1)));
        return placeholders;
    }

    private UUID parsePartId(String partName) {
        try {
            return UUID.fromString(partName.substring(PART_PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw invalid("attachment file part 이름의 UUID가 올바르지 않습니다.");
        }
    }

    private InvalidMarkdownContentException invalid(String message) {
        return new InvalidMarkdownContentException(message);
    }

    public record ParsedAssetSaveRequest(
            String markdown,
            long baseVersion,
            Map<UUID, MultipartFile> attachments
    ) {
    }
}
