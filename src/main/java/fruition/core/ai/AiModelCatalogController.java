package fruition.core.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.shared.ai.AiModelCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "AI Models", description = "선택 가능한 AI 모델 카탈로그와 워크스페이스 모델 설정 API")
@RestController
@RequestMapping("/api/ai-models")
public class AiModelCatalogController {
    private final AiModelCatalog catalog;

    public AiModelCatalogController(AiModelCatalog catalog) {
        this.catalog = catalog;
    }

    @Operation(
        summary = "AI 모델 카탈로그 조회",
        description = "선택할 수 있는 provider/model 조합을 반환합니다. API key는 노출하지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = ModelsResponse.class)))
    })
    @GetMapping
    public ResponseEntity<ModelsResponse> list() {
        return ResponseEntity.ok(new ModelsResponse(catalog.enabledModels().stream()
                .map(model -> new ModelResponse(model.provider(), model.model(), model.displayName()))
                .toList()));
    }

    @Schema(description = "선택 가능한 모델 목록")
    public record ModelsResponse(
            @Schema(description = "활성화된 provider/model 조합 목록")
            List<ModelResponse> models) {}

    @Schema(description = "선택 가능한 모델 하나")
    public record ModelResponse(
            @Schema(description = "LLM provider", allowableValues = {"openai", "gemini", "claude"},
                    example = "openai")
            String provider,

            @Schema(description = "모델명. 요청의 model 필드에 그대로 넣는다.", example = "gpt-5-nano")
            String model,

            @JsonProperty("display_name")
            @Schema(description = "화면에 보여줄 이름", example = "GPT-5 nano")
            String displayName) {}
}
