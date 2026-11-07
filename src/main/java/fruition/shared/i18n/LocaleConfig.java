package fruition.shared.i18n;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;

/**
 * {@code Accept-Language}를 ko·en 중 하나로 정한다. 헤더가 없거나 지원하지 않는 언어면 ko다.
 * Spring Boot 기본 resolver는 지원 목록이 없어 임의 언어(JVM 기본 로케일 포함)를 그대로 쓰므로 직접 둔다.
 * WebMvcConfigurer로 두어 {@code @WebMvcTest} 슬라이스에도 같은 resolver가 들어가게 한다.
 */
@Configuration
public class LocaleConfig implements WebMvcConfigurer {

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(List.of(ResponseLanguage.KOREAN, ResponseLanguage.ENGLISH));
        resolver.setDefaultLocale(ResponseLanguage.KOREAN);
        return resolver;
    }
}
