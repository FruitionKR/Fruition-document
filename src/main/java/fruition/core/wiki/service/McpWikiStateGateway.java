package fruition.core.wiki.service;

import fruition.core.wiki.repository.PipelineWikiStateRequester;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpWikiStateGateway {
    private final PipelineWikiStateRequester requester;
    public McpWikiStateGateway(PipelineWikiStateRequester requester) { this.requester = requester; }
    public Context documentContext(String workspaceId, String documentId) {
        var value = requester.documentContext(workspaceId, documentId);
        return new Context(value.sourceBlocks() == null ? null : value.sourceBlocks().stream()
                .map(b -> new Block(b.blockId(), b.position(), b.text())).toList(), value.sourceContentHash());
    }
    public java.util.Optional<Page> page(String workspaceId, String pageId) {
        return requester.page(workspaceId, pageId)
                .map(value -> new Page(value.markdown(), value.updatedAt()));
    }
    public record Context(java.util.List<Block> sourceBlocks, String sourceContentHash) {}
    public record Block(String blockId, Integer position, String text) {}
    public record Page(String markdown, java.time.Instant updatedAt) {}
}
