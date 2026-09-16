package fruition.core.wiki.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "워크스페이스 Wiki 그래프 전체. 노드는 페이지, 엣지는 페이지 간 관계다.")
public record WikiGraphResponse(
        @Schema(description = "Wiki 페이지 노드 목록")
        List<WikiGraphNode> nodes,

        @Schema(description = "페이지 사이의 관계 목록")
        List<WikiGraphEdge> edges
) {}
