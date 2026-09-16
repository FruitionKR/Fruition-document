package fruition.core.query.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.IOException;

@Schema(description = "Wiki 기반 자연어 질의 요청. provider와 model은 함께 생략하거나 함께 전달해야 한다.")
public record QueryRequest(
        @NotBlank(message = "질문은 비어 있을 수 없습니다.")
        @Schema(description = "질문 문장", example = "검색 인덱싱은 어떻게 동작하나요?")
        String question,

        @Schema(description = "LLM provider. model과 짝을 이뤄야 하며 생략하면 워크스페이스 설정을 쓴다.",
                allowableValues = {"openai", "gemini", "claude"}, example = "openai")
        String provider,

        @Schema(description = "모델명. provider와 짝을 이뤄야 한다.", example = "gpt-5-nano")
        String model,

        @JsonProperty("allow_web_search")
        @JsonDeserialize(using = StrictBooleanDeserializer.class)
        @NotNull(message = "웹 검색 사용 여부는 필수입니다.")
        @Schema(description = "이 질의에만 적용되는 웹 검색 허용 여부. true/false만 받으며 다른 값은 400이다. "
                + "false면 내부 문서가 뒷받침하는 범위만 답한다.",
                example = "false")
        Boolean allowWebSearch
) {
    public static final class StrictBooleanDeserializer extends JsonDeserializer<Boolean> {
        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return switch (parser.currentToken()) {
                case VALUE_TRUE -> Boolean.TRUE;
                case VALUE_FALSE -> Boolean.FALSE;
                default -> (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
            };
        }

        @Override
        public Boolean getNullValue(DeserializationContext context) throws JsonMappingException {
            if (context.getParser().currentToken() == JsonToken.VALUE_NULL) {
                return context.reportInputMismatch(Boolean.class, "웹 검색 사용 여부는 true 또는 false여야 합니다.");
            }
            return null;
        }
    }
}
