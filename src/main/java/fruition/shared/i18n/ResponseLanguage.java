package fruition.shared.i18n;

import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

/**
 * 요청자 화면 언어. 프론트가 보내는 {@code Accept-Language}로 정하며 ko·en만 지원한다.
 * 그 밖의 언어·헤더 없음·요청 밖(Kafka 소비자·스케줄러)은 모두 {@link #KO}다.
 */
public enum ResponseLanguage {
    KO("ko"),
    EN("en");

    public static final Locale KOREAN = Locale.KOREAN;
    public static final Locale ENGLISH = Locale.ENGLISH;

    private final String code;

    ResponseLanguage(String code) {
        this.code = code;
    }

    /** AI command의 {@code response_language} 값("ko"|"en"). */
    public String code() {
        return code;
    }

    public Locale locale() {
        return this == EN ? ENGLISH : KOREAN;
    }

    public boolean isEnglish() {
        return this == EN;
    }

    public static ResponseLanguage of(Locale locale) {
        return locale != null && "en".equalsIgnoreCase(locale.getLanguage()) ? EN : KO;
    }

    /** "ko"|"en" 문자열. 없거나 다른 값이면 KO다. */
    public static ResponseLanguage of(String code) {
        return code != null && "en".equalsIgnoreCase(code.trim()) ? EN : KO;
    }

    /**
     * 지금 처리 중인 요청의 언어. DispatcherServlet이 {@link LocaleConfig}의 resolver로 정한 값을 읽는다.
     * 요청 밖에서는 JVM 기본 로케일에 기대지 않고 KO를 돌려준다.
     */
    public static ResponseLanguage current() {
        LocaleContext context = LocaleContextHolder.getLocaleContext();
        return context == null ? KO : of(context.getLocale());
    }
}
