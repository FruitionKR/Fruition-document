package fruition.core.aitask;

import fruition.shared.i18n.ResponseLanguage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AiErrorMessagesEnTest {

    private static final Pattern HANGUL = Pattern.compile("[\\uAC00-\\uD7A3]");

    @Test
    void englishTablesHaveTheSameKeysAsKoreanTables() throws Exception {
        for (String name : List.of("MESSAGES", "AGENT_OVERRIDES", "CONVERT_OVERRIDES", "TRANSCRIPTION_OVERRIDES")) {
            assertThat(map(AiErrorMessagesEn.class, name).keySet()).as(name)
                    .isEqualTo(map(AiErrorMessages.class, name).keySet());
        }
        assertThat(prefixes(AiErrorMessagesEn.class)).isEqualTo(prefixes(AiErrorMessages.class));
    }

    @Test
    void englishMessagesContainNoKorean() throws Exception {
        for (String name : List.of("MESSAGES", "AGENT_OVERRIDES", "CONVERT_OVERRIDES", "TRANSCRIPTION_OVERRIDES")) {
            map(AiErrorMessagesEn.class, name).values().forEach(value -> assertThat(HANGUL.matcher(value).find()).as(value).isFalse());
        }
    }

    @Test
    void featureMessagesFollowLanguageAndKoreanStaysTheDefault() {
        assertThat(AiErrorMessages.agent("provider_unavailable")).isEqualTo(AiErrorMessages.agent("provider_unavailable", ResponseLanguage.KO));
        assertThat(AiErrorMessages.agent("provider_unavailable", ResponseLanguage.EN))
                .isEqualTo("The AI service didn't respond or had a temporary problem, so the document wasn't changed. Please try again in a moment.");
        assertThat(AiErrorMessages.query("provider_unavailable", ResponseLanguage.EN))
                .isEqualTo("The AI service had a temporary problem and couldn't process this. Please try again in a moment.");
        assertThat(AiErrorMessages.convert("input_rejected", ResponseLanguage.EN))
                .isEqualTo("PDF conversion failed. The PDF is password-protected or damaged and can't be converted.");
        assertThat(AiErrorMessages.ingest(null, ResponseLanguage.EN))
                .isEqualTo("Adding to the wiki failed. The document is unchanged, so please try again.");
        assertThat(AiErrorMessages.lint("markdown_unknown_new", ResponseLanguage.EN))
                .startsWith("Couldn't create an edit");
    }

    @Test
    void storedKoreanMessageIsRebuiltWithTheFeatureThatMadeIt() {
        String convert = AiErrorMessages.convert("input_rejected");
        String agent = AiErrorMessages.agent("provider_unavailable");
        String transcription = AiErrorMessages.transcription(AiErrorMessages.TRANSCRIPTION_CANCELLED);

        assertThat(AiErrorMessages.localizeStored("input_rejected", convert, ResponseLanguage.EN))
                .isEqualTo(AiErrorMessages.convert("input_rejected", ResponseLanguage.EN));
        assertThat(AiErrorMessages.localizeStored("provider_unavailable", agent, ResponseLanguage.EN))
                .isEqualTo(AiErrorMessages.agent("provider_unavailable", ResponseLanguage.EN));
        // 채팅 말풍선은 255자로 잘려 저장될 수 있다.
        assertThat(AiErrorMessages.localizeStored("provider_unavailable", agent.substring(0, 20), ResponseLanguage.EN))
                .isEqualTo(AiErrorMessages.agent("provider_unavailable", ResponseLanguage.EN));
        assertThat(AiErrorMessages.localizeStored(AiErrorMessages.TRANSCRIPTION_CANCELLED, transcription, ResponseLanguage.EN))
                .isEqualTo("Transcription was cancelled. Please upload the recording again.");
    }

    @Test
    void storedMessageStaysForKoreanRequestsAndRowsWithoutCode() {
        assertThat(AiErrorMessages.localizeStored("provider_unavailable", "저장된 문구", ResponseLanguage.KO)).isEqualTo("저장된 문구");
        assertThat(AiErrorMessages.localizeStored(null, "이전 문구", ResponseLanguage.EN)).isEqualTo("이전 문구");
        // 기능 문구와 맞지 않으면 코드의 공통 영어 문구를 쓴다.
        assertThat(AiErrorMessages.localizeStored("AI_USAGE_LIMIT_EXCEEDED", "한도 문구", ResponseLanguage.EN))
                .isEqualTo(AiErrorMessagesEn.AI_USAGE_LIMIT_EXCEEDED);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> map(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<String, String>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static List<String> prefixes(Class<?> type) throws Exception {
        Field field = type.getDeclaredField("PREFIX_MESSAGES");
        field.setAccessible(true);
        return ((List<Map.Entry<String, String>>) field.get(null)).stream().map(Map.Entry::getKey).toList();
    }
}
