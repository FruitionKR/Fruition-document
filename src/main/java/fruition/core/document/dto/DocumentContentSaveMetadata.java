package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DocumentContentSaveMetadata(
        String markdown,
        @JsonProperty("base_version") Long baseVersion
) {
}
