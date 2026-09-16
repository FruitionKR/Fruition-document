package fruition.core.config;

import fruition.core.agent.security.AgentServiceTokenFilter;
import fruition.shared.logging.HttpRequestLoggingFilter;
import fruition.shared.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * document 앱 보안 구성. 로그인·OAuth는 access 앱 소유이므로 여기는
 * stateless로 access가 발급한 JWT를 검증만 한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final List<String> corsAllowedOrigins;
    private final String agentServiceToken;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          @Value("${app.cors.allowed-origins}") List<String> corsAllowedOrigins,
                          @Value("${AGENT_INTERNAL_TOKEN:}") String agentServiceToken) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.corsAllowedOrigins = corsAllowedOrigins;
        this.agentServiceToken = agentServiceToken;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsAllowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // 요청 추적 ID는 브라우저 클라이언트가 읽어야 서버 로그와 대조할 수 있다.
        configuration.setExposedHeaders(List.of(HttpRequestLoggingFilter.REQUEST_ID_HEADER));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                // deny-by-default: 공개 경로만 명시하고 나머지는 전부 인증을 요구한다.
                .authorizeHttpRequests(auth -> auth
                        // SSE 완료는 async 디스패치로 돌아오는데 그 시점엔 SecurityContext가 비어 있다.
                        // 인가를 다시 걸면 Access Denied가 나고, 응답이 이미 커밋된 뒤라 오류도 못 내보낸다.
                        // 최초 요청에서 이미 인가를 통과한 같은 요청의 연장이다.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // actuator는 management.server.port로 분리돼 있고 이 필터 체인은 두 포트에
                        // 모두 걸린다. 업무 포트에는 엔드포인트가 매핑되지 않아(404) 여기서 열어도
                        // 공개 범위는 관리 포트로 한정된다.
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        // "/swagger-ui/**"는 "/swagger-ui.html"을 매칭하지 않는다 — 진입 URL을 따로 연다.
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // access(인증 서비스)가 호출하는 내부 API: 컨트롤러에서 X-Internal-Token을 검증한다
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new AgentServiceTokenFilter(agentServiceToken), JwtAuthenticationFilter.class);

        return http.build();
    }
}
