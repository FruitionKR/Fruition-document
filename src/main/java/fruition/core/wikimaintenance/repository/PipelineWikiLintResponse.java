package fruition.core.wikimaintenance.repository;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** 공개 응답 원본과 로그 저장에 필요한 lint 결과를 함께 보관한다. */
public record PipelineWikiLintResponse(
        String operationId,
        List<ChangedPage> changedPages,
        JsonNode body
) {

    public PipelineWikiLintResponse {
        changedPages = List.copyOf(changedPages);
    }

    public static PipelineWikiLintResponse from(JsonNode body) {
        List<ChangedPage> changedPages = new ArrayList<>();
        for (JsonNode page : body.path("changed_pages")) {
            changedPages.add(new ChangedPage(
                    textOrNull(page, "page_id"),
                    textOrNull(page, "page_type"),
                    textOrNull(page, "markdown_key"),
                    textOrNull(page, "contribution_key"),
                    textOrNull(page, "content_hash")));
        }
        return new PipelineWikiLintResponse(
                textOrNull(body, "operation_id"), changedPages, body);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public record ChangedPage(
            String pageId,
            String pageType,
            String markdownKey,
            String contributionKey,
            String contentHash
    ) {}
}
