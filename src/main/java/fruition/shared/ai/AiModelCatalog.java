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
    public static final String DEFAULT_MODEL = "gpt-6-luna";

    // 2026-10-06 실제 호출에서 JSON 응답을 확인한 텍스트 생성 모델이다(#45).
    // 2026-10-10 공급사 종료·자동 전환 대상 모델은 뺐다(#86).
    // 표시 이름은 각 provider 공식 모델 문서의 표기를 따른다(2026-10-06 확인).
    // 화이트리스트라 폐기·JSON 모드 미지원·비텍스트 모델은 넣지 않는다.
    // Fruition-access에도 같은 목록이 있다(Fruition-access#8). 바꿀 때 함께 맞춘다.
    // 모델을 추가하면 공식 단가 행도 마이그레이션으로 넣는다(AiModelCatalogPriceIntegrationTest가 확인한다).
    // 프론트는 저장된 선택이 없으면 첫 항목을 고른다. 그래서 provider마다 기본 모델(openai는 DEFAULT_MODEL)을 맨 앞에 둔다.
    private static final List<AiModel> SUPPORTED_MODELS = List.of(
            new AiModel("openai", "gpt-6-luna", "GPT-6 Luna"),
            new AiModel("openai", "gpt-6.1-sol", "GPT-6.1 Sol"),
            new AiModel("openai", "gpt-6-sol", "GPT-6 Sol"),
            new AiModel("openai", "gpt-6-astra", "GPT-6 Astra"),
            new AiModel("openai", "gpt-5.6-sol", "GPT-5.6 Sol"),
            new AiModel("openai", "gpt-5.6-terra", "GPT-5.6 Terra"),
            new AiModel("openai", "gpt-5.6-luna", "GPT-5.6 Luna"),
            new AiModel("openai", "gpt-5.5", "GPT-5.5"),
            new AiModel("openai", "gpt-5.4", "GPT-5.4"),
            new AiModel("openai", "gpt-5.4-mini", "GPT-5.4 Mini"),
            new AiModel("openai", "gpt-4.1", "GPT-4.1"),
            new AiModel("openai", "gpt-4.1-mini", "GPT-4.1 Mini"),
            new AiModel("openai", "gpt-4o", "GPT-4o"),
            new AiModel("openai", "gpt-4o-mini", "GPT-4o Mini"),
            new AiModel("gemini", "gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite"),
            new AiModel("gemini", "gemini-3.8-flash", "Gemini 3.8 Flash"),
            new AiModel("gemini", "gemini-3.6-flash", "Gemini 3.6 Flash"),
            new AiModel("gemini", "gemini-3.1-pro-preview", "Gemini 3.1 Pro preview"),
            new AiModel("gemini", "gemini-3-flash-preview", "Gemini 3 Flash preview"),
            new AiModel("claude", "claude-sonnet-5", "Claude Sonnet 5"),
            new AiModel("claude", "claude-opus-5-5", "Claude Opus 5.5"),
            new AiModel("claude", "claude-sonnet-5-5", "Claude Sonnet 5.5"),
            new AiModel("claude", "claude-fable-5-1", "Claude Fable 5.1"),
            new AiModel("claude", "claude-opus-5", "Claude Opus 5"),
            new AiModel("claude", "claude-fable-5", "Claude Fable 5"),
            new AiModel("claude", "claude-opus-4-8", "Claude Opus 4.8"),
            new AiModel("claude", "claude-sonnet-4-6", "Claude Sonnet 4.6"),
            new AiModel("claude", "claude-haiku-4-5-20251001", "Claude Haiku 4.5")
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
