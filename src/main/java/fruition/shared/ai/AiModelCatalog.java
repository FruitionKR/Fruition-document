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

    /** 워크스페이스 공용 설정(ingest_lint)의 기본값. access의 workspaces 기본값과 같다. */
    public static final String WORKSPACE_DEFAULT_PROVIDER = "gemini";
    public static final String WORKSPACE_DEFAULT_MODEL = "gemini-3.5-flash-lite";

    /** 사용자가 요청마다 직접 고르는 기능(채팅 질의, Agent turn)에서만 쓸 수 있는 provider. */
    private static final Set<String> CHAT_ONLY_PROVIDERS = Set.of("claude");

    // 2026-10-06 실제 호출에서 JSON 응답을 확인한 텍스트 생성 모델이다(#45).
    // 2026-10-18 비용 때문에 provider마다 가장 싼 모델 하나만 남겼다(저가 모델 정책).
    // 표시 이름은 각 provider 공식 모델 문서의 표기를 따른다(2026-10-06 확인).
    // 화이트리스트라 폐기·JSON 모드 미지원·비텍스트 모델은 넣지 않는다.
    // 이 목록이 기준이다. Fruition-access에도 같은 목록이 있고, access CI가 이 저장소 dev의 목록과 비교한다(#45, Fruition-access#8).
    // 목록을 바꿀 때는 이 저장소 PR을 먼저 dev에 머지한 뒤 access를 맞춘다.
    // 모델을 추가하면 공식 단가 행도 마이그레이션으로 넣는다(AiModelCatalogPriceIntegrationTest가 확인한다).
    // 뺀 모델의 단가 행(ai_model_prices)은 과거 청구 기록용으로 남긴다.
    // 프론트는 저장된 선택이 없으면 첫 항목을 고른다. 그래서 openai 기본 모델(DEFAULT_MODEL)을 맨 앞에 둔다.
    private static final List<AiModel> SUPPORTED_MODELS = List.of(
            new AiModel("openai", "gpt-6-luna", "GPT-6 Luna"),
            new AiModel("gemini", "gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite"),
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

    /**
     * 워크스페이스 공용 설정(ingest_lint)에 저장할 모델을 고른다. ingest·wiki lint·스킬·회의록·변환 repair가 함께 쓰므로
     * 채팅 전용 provider(claude)는 받지 않는다.
     */
    public AiModel resolveForWorkspace(String provider, String model) {
        AiModel resolved = resolve(provider, model);
        if (CHAT_ONLY_PROVIDERS.contains(resolved.provider())) {
            throw new InvalidAiModelException("Claude 모델은 채팅에서 직접 선택할 때만 사용할 수 있습니다.");
        }
        return resolved;
    }

    /** 워크스페이스 공용 설정에 쓸 수 있는 조합인지 본다. 설정을 읽어 쓰는 쪽의 방어선이다. */
    public static boolean isWorkspaceModel(String provider, String model) {
        return provider != null && !CHAT_ONLY_PROVIDERS.contains(provider)
                && SUPPORTED_MODELS.stream().anyMatch(candidate -> candidate.provider().equals(provider)
                        && candidate.model().equals(model));
    }

    public record AiModel(String provider, String model, String displayName) {}
}
