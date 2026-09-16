package fruition.shared.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AiModelCatalog {

    public static final String DEFAULT_PROVIDER = "openai";
    public static final String DEFAULT_MODEL = "gpt-5-nano";

    private static final List<AiModel> SUPPORTED_MODELS = List.of(
            new AiModel("openai", "gpt-5-nano", "GPT-5 nano"),
            new AiModel("gemini", "gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite"),
            new AiModel("claude", "claude-sonnet-5", "Claude Sonnet 5")
    );

    private final Set<String> enabledProviders;

    public AiModelCatalog(@Value("${app.ai.enabled-providers:openai,gemini,claude}") String enabledProviders) {
        this.enabledProviders = Arrays.stream(enabledProviders.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public List<AiModel> enabledModels() {
        return SUPPORTED_MODELS.stream()
                .filter(model -> enabledProviders.contains(model.provider()))
                .toList();
    }

    public AiModel resolve(String provider, String model) {
        String resolvedProvider = provider == null ? DEFAULT_PROVIDER : provider.trim().toLowerCase();
        String resolvedModel = model == null ? DEFAULT_MODEL : model.trim();
        if ((provider == null) != (model == null)) {
            throw new InvalidAiModelException("provider와 model은 함께 전달해야 합니다.");
        }
        return enabledModels().stream()
                .filter(candidate -> candidate.provider().equals(resolvedProvider)
                        && candidate.model().equals(resolvedModel))
                .findFirst()
                .orElseThrow(() -> new InvalidAiModelException("선택할 수 없는 AI 모델입니다."));
    }

    public record AiModel(String provider, String model, String displayName) {}
}
