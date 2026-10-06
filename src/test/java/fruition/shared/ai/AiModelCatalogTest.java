package fruition.shared.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiModelCatalogTest {
    @Test
    void enabledModels_returnsSeveralModelsPerProvider() {
        AiModelCatalog catalog = new AiModelCatalog("openai,gemini,claude");

        assertThat(catalog.enabledModels()).hasSize(38);
        assertThat(catalog.enabledModels()).filteredOn(model -> model.provider().equals("openai")).hasSize(21);
        assertThat(catalog.enabledModels()).filteredOn(model -> model.provider().equals("gemini")).hasSize(8);
        assertThat(catalog.enabledModels()).filteredOn(model -> model.provider().equals("claude")).hasSize(9);
        assertThat(catalog.enabledModels()).extracting(AiModelCatalog.AiModel::model)
                .contains("gpt-5-nano", "gpt-6.1-sol", "o4-mini", "gemini-3.1-flash-lite", "gemini-3.8-flash",
                        "claude-sonnet-5", "claude-opus-5-5", "claude-haiku-4-5-20251001")
                .doesNotHaveDuplicates();
    }

    @Test
    void enabledModels_putsExistingDefaultModelFirst() {
        // 프론트는 저장된 선택이 없으면 첫 항목을 고르므로 목록이 늘어도 기본 선택이 바뀌지 않아야 한다.
        assertThat(new AiModelCatalog("openai,gemini,claude").enabledModels().get(0))
                .isEqualTo(new AiModelCatalog("openai").resolve(null, null));
        assertThat(new AiModelCatalog("gemini,claude").enabledModels().get(0).model())
                .isEqualTo("gemini-3.1-flash-lite");
        assertThat(new AiModelCatalog("claude").enabledModels().get(0).model())
                .isEqualTo("claude-sonnet-5");
    }

    @Test
    void enabledModels_returnsOnlyEnabledProviders() {
        AiModelCatalog catalog = new AiModelCatalog("gemini");

        assertThat(catalog.enabledModels()).extracting(AiModelCatalog.AiModel::provider)
                .containsOnly("gemini");
        assertThat(catalog.resolve("gemini", "gemini-3.5-flash-lite"))
                .isEqualTo(new AiModelCatalog.AiModel("gemini", "gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite"));
        assertThatThrownBy(() -> catalog.resolve("openai", "gpt-6.1-sol"))
                .isInstanceOf(InvalidAiModelException.class);
    }

    @Test
    void resolve_rejectsModelsThatFailAtProvider() {
        AiModelCatalog catalog = new AiModelCatalog("openai,gemini,claude");

        // 폐기(404), JSON 모드 미지원, 비텍스트 계열은 provider 목록에 있어도 선택할 수 없다.
        for (String model : new String[]{"gpt-5-chat-latest", "gpt-5.3-chat-latest", "gpt-3.5-turbo-1106",
                "gpt-4", "gpt-4-0613", "gpt-image-1", "gpt-4o-mini-tts", "text-embedding-3-small"}) {
            assertThatThrownBy(() -> catalog.resolve("openai", model)).isInstanceOf(InvalidAiModelException.class);
        }
        assertThatThrownBy(() -> catalog.resolve("gemini", "gemini-2.5-flash"))
                .isInstanceOf(InvalidAiModelException.class);
        assertThatThrownBy(() -> catalog.resolve("claude", "gpt-5"))
                .isInstanceOf(InvalidAiModelException.class);
    }

    @Test
    void resolve_omittedSelection_usesCurrentDefault() {
        AiModelCatalog catalog = new AiModelCatalog("openai");

        assertThat(catalog.resolve(null, null))
                .isEqualTo(new AiModelCatalog.AiModel("openai", "gpt-5-nano", "GPT-5 nano"));
    }

    @Test
    void resolve_rejectsPartialOrDisabledSelection() {
        AiModelCatalog catalog = new AiModelCatalog("openai");

        assertThatThrownBy(() -> catalog.resolve("openai", null))
                .isInstanceOf(InvalidAiModelException.class);
        assertThatThrownBy(() -> catalog.resolve(null, "gpt-5-nano"))
                .isInstanceOf(InvalidAiModelException.class);
        assertThatThrownBy(() -> catalog.resolve("claude", "claude-legacy"))
                .isInstanceOf(InvalidAiModelException.class);
        assertThatThrownBy(() -> catalog.resolve("gemini", "gemini-2.5-flash-lite"))
                .isInstanceOf(InvalidAiModelException.class);
    }
}
