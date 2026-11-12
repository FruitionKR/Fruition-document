package fruition.core.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.document.service.AiMarkdownSanitizer;
import fruition.core.wiki.dto.WikiPageDetailResponse;
import fruition.core.wiki.exception.WikiPageNotFoundException;
import fruition.core.wiki.service.WikiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * get_wiki_page. 본문은 웹 상세 조회({@link WikiService#read})가 고른 것을 그대로 쓴다
 * (AI 본문 → 최신 버전 행 → 저장 객체). 여기서는 ADR 0046의 MCP 경계 처리만 더한다:
 * 빈 본문도 정상 응답, 읽는 동안 페이지가 바뀌면 {@code PAGE_CHANGED}, 전체 읽기 deadline,
 * 크기 제한·무해화·hash·신뢰 경계 표시.
 */
@Service
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpWikiPageService {
    private static final Pattern PAGE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,254}");
    // ponytail: deadline이 지나면 응답은 끝내지만 작업 스레드는 하위 호출의 자체 timeout까지 남을 수 있다(가상 스레드라 비용은 작다).
    private static final ExecutorService READS = Executors.newVirtualThreadPerTaskExecutor();
    private final WikiService wiki;
    private final McpProperties properties;
    private final long deadlineMs;

    public McpWikiPageService(WikiService wiki, McpProperties properties,
                              @Value("${app.mcp.wiki-page-deadline-ms}") long deadlineMs) {
        if (deadlineMs < 1) throw new IllegalArgumentException("MCP wiki page deadline must be positive");
        this.wiki = wiki;
        this.properties = properties;
        this.deadlineMs = deadlineMs;
    }

    public Response get(McpIdentity identity, String pageId) {
        if (!validPageId(pageId)) throw new McpToolException("INVALID_INPUT");
        Future<Response> read = READS.submit(() -> read(identity, pageId));
        try {
            return read.get(deadlineMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        } catch (TimeoutException e) {
            read.cancel(true);
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        } catch (InterruptedException e) {
            read.cancel(true);
            Thread.currentThread().interrupt();
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
    }

    private Response read(McpIdentity identity, String pageId) {
        WikiService.PageRead read;
        try {
            read = wiki.read(identity.workspaceId(), identity.userId(), pageId);
        } catch (WikiPageNotFoundException | WorkspaceNotFoundException e) {
            throw new McpToolException("PAGE_NOT_FOUND");
        }
        WikiPageDetailResponse page = read.detail();
        String pageType = page.pageType() == null ? "" : page.pageType().toUpperCase(Locale.ROOT);
        if (read.body().isEmpty() || page.title() == null
                || !("SOURCE".equals(pageType) || "CONCEPT".equals(pageType))) {
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
        requireUnchanged(identity, page);
        String raw = read.body().get();
        String body = AiMarkdownSanitizer.sanitize(raw);
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        if (raw.getBytes(StandardCharsets.UTF_8).length > properties.maxDocumentContentBytes()
                || bodyBytes.length > properties.maxDocumentContentBytes()) {
            throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        }
        return new Response("mcp.wiki-page.v1", page.id(), pageType, AiMarkdownSanitizer.sanitize(page.title()),
                body, "sha256:" + sha256(bodyBytes), lintTarget(page, read.objectMarkdown()),
                "UNTRUSTED_WORKSPACE_CONTENT");
    }

    /** 본문을 고른 뒤 페이지 metadata와 최신 revision이 읽기 시작 때와 같고 아직 active인지 본다. */
    private void requireUnchanged(McpIdentity identity, WikiPageDetailResponse before) {
        WikiService.PageState after = wiki.currentState(identity.workspaceId(), identity.userId(), before.id())
                .orElse(null);
        if (after == null || !"active".equals(after.status())
                || !Objects.equals(after.pageType(), before.pageType())
                || !Objects.equals(after.title(), before.title())
                || !Objects.equals(after.markdownUri(), before.markdownUri())
                || !Objects.equals(after.status(), before.status())
                || !Objects.equals(after.updatedAt(), before.updatedAt())
                || !Objects.equals(after.revision(), before.revision())) {
            throw new McpToolException("PAGE_CHANGED");
        }
    }

    /**
     * pipeline lint가 대조하는 값과 같은 기준의 target_snapshot. version은 updated_at의 epoch 마이크로초,
     * hash는 markdown_uri 원문 객체(없으면 빈 문자열)의 SHA-256이다. 응답 body는 무해화돼 있어 그 hash와 다르다.
     */
    private static LintTarget lintTarget(WikiPageDetailResponse page, String objectMarkdown) {
        if (page.updatedAt() == null) return null;
        return new LintTarget(page.id(), McpWikiApprovalService.pageVersion(page.updatedAt()), "sha256:"
                + sha256((objectMarkdown == null ? "" : objectMarkdown).getBytes(StandardCharsets.UTF_8)));
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
                           @JsonProperty("lint_target") LintTarget lintTarget,
                           @JsonProperty("data_boundary") String dataBoundary) {}

    /** submit_lint_changes target_snapshot 항목에 그대로 넣는 값. */
    public record LintTarget(@JsonProperty("page_id") String pageId, long version,
                             @JsonProperty("content_hash") String contentHash) {}
}
