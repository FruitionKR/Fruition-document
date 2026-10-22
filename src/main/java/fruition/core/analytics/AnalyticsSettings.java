package fruition.core.analytics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record AnalyticsSettings(boolean enabled, String token, String host, String environment, String appVersion) {
    public AnalyticsSettings(
            @Value("${ANALYTICS_ENABLED:false}") boolean enabled,
            @Value("${POSTHOG_PROJECT_TOKEN:}") String token,
            @Value("${POSTHOG_HOST:https://us.i.posthog.com}") String host,
            @Value("${ANALYTICS_ENVIRONMENT:local}") String environment,
            @Value("${APP_VERSION:unknown}") String appVersion) {
        boolean validEnvironment = java.util.Set.of("local", "staging", "production").contains(environment);
        this.enabled = enabled && validEnvironment;
        this.token = token;
        this.host = host;
        if (!validEnvironment)
            org.slf4j.LoggerFactory.getLogger(AnalyticsSettings.class).warn("analytics disabled: invalid environment configuration");
        this.environment = validEnvironment ? environment : "local";
        this.appVersion = appVersion;
    }
    public boolean relayEnabled() {
        return enabled && token != null && token.matches("phc_[A-Za-z0-9_-]+");
    }
    @Override public String toString() {
        return "AnalyticsSettings[enabled=" + enabled + ", token=[redacted], environment=" + environment + "]";
    }
}
