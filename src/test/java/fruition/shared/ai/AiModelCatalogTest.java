package fruition.shared.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiModelCatalogTest {
    @Test
    void enabledModels_keepsOnlyCheapestModelPerProvider() {
        AiModelCatalog catalog = new AiModelCatalog("openai,gemini,claude");

        assertThat(catalog.enabledModels()).extracting(AiModelCatalog.AiModel::provider, AiModelCatalog.AiModel::model)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("openai", "gpt-6-luna"),
                        org.assertj.core.groups.Tuple.tuple("gemini", "gemini-3.5-flash-lite"),
                        org.assertj.core.groups.Tuple.tuple("claude", "claude-haiku-4-5-20251001"));
    }

    @Test
    void resolve_rejectsRemovedModels() {
        AiModelCatalog catalog = new AiModelCatalog("openai,gemini,claude");

        for (String model : new String[]{"gpt-6.1-sol", "gpt-6-sol", "gpt-6-astra", "gpt-5.6-sol", "gpt-5.4-mini",
                "gpt-4.1", "gpt-4o-mini", "gpt-5-nano", "o3"}) {
            assertThatThrownBy(() -> catalog.resolve("openai", model))
                    .isInstanceOf(InvalidAiModelException.class)
                    .hasMessage("선택할 수 없는 AI 모델입니다.");
        }
        for (String model : new String[]{"gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.1-pro-preview",
                "gemini-3-flash-preview", "gemini-3.1-flash-lite"}) {
            assertThatThrownBy(() -> catalog.resolve("gemini", model))
                    .isInstanceOf(InvalidAiModelException.class)
                    .hasMessage("선택할 수 없는 AI 모델입니다.");
        }
        for (String model : new String[]{"claude-sonnet-5", "claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1",
                "claude-opus-5", "claude-fable-5", "claude-opus-4-8", "claude-sonnet-4-6"}) {
            assertThatThrownBy(() -> catalog.resolve("claude", model))
                    .isInstanceOf(InvalidAiModelException.class)
                    .hasMessage("선택할 수 없는 AI 모델입니다.");
        }
    }

    @Test
    void resolve_allowsClaudeHaikuForChat() {
        assertThat(new AiModelCatalog("openai,gemini,claude").resolve("claude", "claude-haiku-4-5-20251001"))
                .isEqualTo(new AiModelCatalog.AiModel("claude", "claude-haiku-4-5-20251001", "Claude Haiku 4.5"));
    }

    @Test
    void resolveForWorkspace_rejectsClaude() {
        AiModelCatalog catalog = new AiModelCatalog("openai,gemini,claude");

        assertThat(catalog.resolveForWorkspace("gemini", "gemini-3.5-flash-lite").model()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(catalog.resolveForWorkspace("openai", "gpt-6-luna").model()).isEqualTo("gpt-6-luna");
        assertThatThrownBy(() -> catalog.resolveForWorkspace("claude", "claude-haiku-4-5-20251001"))
                .isInstanceOf(InvalidAiModelException.class)
                .hasMessage("Claude 모델은 채팅에서 직접 선택할 때만 사용할 수 있습니다.");
        assertThatThrownBy(() -> catalog.resolveForWorkspace("openai", "gpt-6-sol"))
                .isInstanceOf(InvalidAiModelException.class);
    }

    @Test
    void isWorkspaceModel_allowsOnlyOpenaiAndGeminiCatalogModels() {
        assertThat(AiModelCatalog.isWorkspaceModel("openai", "gpt-6-luna")).isTrue();
        assertThat(AiModelCatalog.isWorkspaceModel("gemini", "gemini-3.5-flash-lite")).isTrue();
        assertThat(AiModelCatalog.isWorkspaceModel("claude", "claude-haiku-4-5-20251001")).isFalse();
        assertThat(AiModelCatalog.isWorkspaceModel("gemini", "gemini-3.8-flash")).isFalse();
        assertThat(AiModelCatalog.isWorkspaceModel(null, null)).isFalse();
        assertThat(AiModelCatalog.isWorkspaceModel(AiModelCatalog.WORKSPACE_DEFAULT_PROVIDER,
                AiModelCatalog.WORKSPACE_DEFAULT_MODEL)).isTrue();
    }

    @Test
    void enabledModels_putsExistingDefaultModelFirst() {
        // 프론트는 저장된 선택이 없으면 첫 항목을 고르므로 목록이 늘어도 기본 선택이 바뀌지 않아야 한다.
        assertThat(new AiModelCatalog("openai,gemini,claude").enabledModels().get(0))
                .isEqualTo(new AiModelCatalog("openai").resolve(null, null));
        assertThat(new AiModelCatalog("gemini,claude").enabledModels().get(0).model())
                .isEqualTo("gemini-3.5-flash-lite");
        assertThat(new AiModelCatalog("claude").enabledModels().get(0).model())
                .isEqualTo("claude-haiku-4-5-20251001");
    }

    @Test
    void enabledModels_returnsOnlyEnabledProviders() {
        AiModelCatalog catalog = new AiModelCatalog("gemini");

        assertThat(catalog.enabledModels()).extracting(AiModelCatalog.AiModel::provider)
                .containsOnly("gemini");
        assertThat(catalog.resolve("gemini", "gemini-3.5-flash-lite"))
                .isEqualTo(new AiModelCatalog.AiModel("gemini", "gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite"));
        assertThatThrownBy(() -> catalog.resolve("openai", "gpt-6-luna"))
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
                .isEqualTo(new AiModelCatalog.AiModel("openai", "gpt-6-luna", "GPT-6 Luna"));
    }

    @Test
    void resolve_rejectsPartialOrDisabledSelection() {
        AiModelCatalog catalog = new AiModelCatalog("openai");

        assertThatThrownBy(() -> catalog.resolve("openai", null))
                .isInstanceOf(InvalidAiModelException.class);
        assertThatThrownBy(() -> catalog.resolve(null, "gpt-6-luna"))
                .isInstanceOf(InvalidAiModelException.class);
        assertThatThrownBy(() -> catalog.resolve("claude", "claude-legacy"))
                .isInstanceOf(InvalidAiModelException.class);
        assertThatThrownBy(() -> catalog.resolve("gemini", "gemini-2.5-flash-lite"))
                .isInstanceOf(InvalidAiModelException.class);
    }
}
