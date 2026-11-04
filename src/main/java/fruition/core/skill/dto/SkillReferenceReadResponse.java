package fruition.core.skill.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillReferenceReadResponse(
        @JsonProperty("document_role") String documentRole,
        String markdown,
        Boolean truncated,
        @JsonProperty("total_chars") Integer totalChars
) {
    public SkillReferenceReadResponse(String documentRole, String markdown) {
        this(documentRole, markdown, null, null);
    }
}
