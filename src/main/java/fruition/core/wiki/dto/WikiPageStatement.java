package fruition.core.wiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Concept 페이지의 statement. lint REMOVE_STATEMENT가 statement_id와 text_hash로 대상을 고른다.")
public record WikiPageStatement(
        @JsonProperty("statement_id")
        @Schema(description = "statement ID")
        String statementId,

        @Schema(description = "statement 본문")
        String text,

        @JsonProperty("text_hash")
        @Schema(description = "statement 본문 hash(sha256:...)", example = "sha256:0000000000000000000000000000000000000000000000000000000000000000")
        String textHash
) {}
