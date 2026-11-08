package fruition.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record McpOwnedRunsResponse(
        List<Item> items,
        @JsonProperty("next_cursor") String nextCursor
) {
    public record Item(
            @JsonProperty("run_id") String runId,
            String status
    ) {}
}
