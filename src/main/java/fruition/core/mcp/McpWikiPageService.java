package fruition.core.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.document.service.AiMarkdownSanitizer;
import fruition.core.wiki.dto.WikiPageDetailResponse;
import fruition.core.wiki.exception.WikiPageNotFoundException;
import fruition.core.wiki.service.WikiService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * get_wiki_page. 본문은 웹 상세 조회({@link WikiService#findById})가 고른 것을 그대로 쓴다
 * (AI 본문 → 최신 버전 행 → 저장 객체). 여기서는 MCP 경계 처리(크기 제한·무해화·hash·신뢰 경계 표시)만 더한다.
 */
@Service
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpWikiPageService {
    private static final Pattern PAGE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,254}");
    private final WikiService wiki;
    private final McpProperties properties;

    public McpWikiPageService(WikiService wiki, McpProperties properties) {
        this.wiki = wiki;
        this.properties = properties;
    }

    public Response get(McpIdentity identity, String pageId) {
        if (!validPageId(pageId)) throw new McpToolException("INVALID_INPUT");
        WikiPageDetailResponse page;
        try {
            page = wiki.findById(identity.workspaceId(), identity.userId(), pageId);
        } catch (WikiPageNotFoundException | WorkspaceNotFoundException e) {
            throw new McpToolException("PAGE_NOT_FOUND");
        }
        String pageType = page.pageType() == null ? "" : page.pageType().toUpperCase(Locale.ROOT);
        if (page.markdown() == null || page.title() == null
                || !("SOURCE".equals(pageType) || "CONCEPT".equals(pageType))) {
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
        String body = AiMarkdownSanitizer.sanitize(page.markdown());
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        if (page.markdown().getBytes(StandardCharsets.UTF_8).length > properties.maxDocumentContentBytes()
                || bodyBytes.length > properties.maxDocumentContentBytes()) {
            throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        }
        return new Response("mcp.wiki-page.v1", page.id(), pageType, AiMarkdownSanitizer.sanitize(page.title()),
                body, "sha256:" + sha256(bodyBytes), "UNTRUSTED_WORKSPACE_CONTENT");
    }

    static boolean validPageId(String pageId) {
        return PAGE_ID.matcher(pageId == null ? "" : pageId).matches();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Response(@JsonProperty("schema_version") String schemaVersion,
                           @JsonProperty("page_id") String pageId,
                           @JsonProperty("page_type") String pageType,
                           String title, String body,
                           @JsonProperty("content_hash") String contentHash,
                           @JsonProperty("data_boundary") String dataBoundary) {}
}
