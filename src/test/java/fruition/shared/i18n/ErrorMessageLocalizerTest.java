package fruition.shared.i18n;

import fruition.shared.util.ErrorResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorMessageLocalizerTest {

    private final ErrorMessageLocalizer localizer = new ErrorMessageLocalizer(messageSource());

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void koreanKeepsDetailedExceptionMessage() {
        ErrorResponse response = ErrorResponse.of("DOCUMENT_NOT_FOUND", "삭제된 문서입니다.");

        assertThat(localizer.localize(response, ResponseLanguage.KO)).isSameAs(response);
    }

    @Test
    void koreanFillsBlankMessageFromBundle() {
        ErrorResponse localized = localizer.localize(ErrorResponse.of("DOCUMENT_NOT_FOUND", null), ResponseLanguage.KO);

        assertThat(localized.error().message()).isEqualTo("문서를 찾을 수 없습니다.");
    }

    @Test
    void englishReplacesMessageByCodeAndKeepsOtherFields() {
        ErrorResponse localized = localizer.localize(
                ErrorResponse.ofUsageLimit("AI_USAGE_LIMIT_EXCEEDED", "한도 초과", "DAILY_TOKENS", "2026-11-08T00:00:00+09:00"),
                ResponseLanguage.EN);

        assertThat(localized.error().code()).isEqualTo("AI_USAGE_LIMIT_EXCEEDED");
        assertThat(localized.error().message()).isEqualTo("You've reached your AI usage limit. Please try again later or tomorrow.");
        assertThat(localized.error().limitType()).isEqualTo("DAILY_TOKENS");
        assertThat(localized.error().resetAt()).isEqualTo("2026-11-08T00:00:00+09:00");
    }

    @Test
    void englishUsesDefaultForUnknownCodeAndLocalizesFieldReasons() {
        ErrorResponse localized = localizer.localize(new ErrorResponse(new ErrorResponse.ErrorDetail(
                "SOME_NEW_CODE", "새 오류", List.of(new ErrorResponse.ErrorDetail.FieldError("name", "필수입니다.")),
                null, null, null, null)), ResponseLanguage.EN);

        assertThat(localized.error().message()).isEqualTo("The request could not be processed.");
        assertThat(localized.error().details()).containsExactly(
                new ErrorResponse.ErrorDetail.FieldError("name", "The value is invalid."));
    }

    @Test
    void currentLanguageIsKoreanOutsideRequestAndForUnsupportedLocales() {
        assertThat(ResponseLanguage.current()).isEqualTo(ResponseLanguage.KO);
        LocaleContextHolder.setLocale(Locale.FRENCH);
        assertThat(ResponseLanguage.current()).isEqualTo(ResponseLanguage.KO);
        LocaleContextHolder.setLocale(Locale.US);
        assertThat(ResponseLanguage.current()).isEqualTo(ResponseLanguage.EN);
        assertThat(ResponseLanguage.current().code()).isEqualTo("en");
    }

    @Test
    void koreanAndEnglishBundlesHaveTheSameKeys() throws IOException {
        // 키가 영어 번들에만 없으면 기본(한국어) 번들 값이 영어 응답에 섞인다.
        assertThat(load("messages_en.properties").stringPropertyNames())
                .isEqualTo(load("messages.properties").stringPropertyNames());
    }

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (var reader = new InputStreamReader(
                ErrorMessageLocalizerTest.class.getClassLoader().getResourceAsStream(name), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
