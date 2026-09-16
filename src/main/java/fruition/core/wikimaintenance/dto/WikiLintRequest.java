package fruition.core.wikimaintenance.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.IOException;

@Schema(description = "Wiki 정합성 점검(lint) 실행 요청. 두 값 모두 true/false만 받는다.")
public record WikiLintRequest(
        @JsonProperty("materialize_promotions")
        @JsonDeserialize(using = StrictBooleanDeserializer.class)
        @Schema(description = "점검에서 찾은 승격 후보를 실제로 반영할지 여부", example = "false")
        Boolean materializePromotions,

        @JsonProperty("dry_run")
        @JsonDeserialize(using = StrictBooleanDeserializer.class)
        @Schema(description = "true면 Wiki를 바꾸지 않고 점검 결과만 낸다.", example = "true")
        Boolean dryRun
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
    }
}
