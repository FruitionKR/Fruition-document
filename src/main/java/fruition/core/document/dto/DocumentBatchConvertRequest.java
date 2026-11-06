package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "여러 PDF의 Markdown 변환 요청.")
public record DocumentBatchConvertRequest(
        @JsonProperty("document_ids")
        @NotEmpty
        @Size(max = 20)
        @ArraySchema(arraySchema = @Schema(description = "변환할 PDF 원본 문서 ID. 1~20개이며 중복은 한 번만 처리한다."),
                minItems = 1, maxItems = 20)
        List<@NotBlank String> documentIds
) {
}
