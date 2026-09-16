package fruition.shared.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiModelCatalogTest {
    @Test
    void enabledModels_returnsOnlyEnabledProviders() {
        AiModelCatalog catalog = new AiModelCatalog("openai,gemini,claude");

        assertThat(catalog.enabledModels()).extracting(AiModelCatalog.AiModel::provider)
                .containsExactly("openai", "gemini", "claude");
        assertThat(catalog.enabledModels()).extracting(AiModelCatalog.AiModel::model)
                .containsExactly("gpt-5-nano", "gemini-3.1-flash-lite", "claude-sonnet-5");
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
