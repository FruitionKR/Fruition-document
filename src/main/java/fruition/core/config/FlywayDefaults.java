package fruition.core.config;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * Spring 밖에서 Flyway를 직접 돌리는 곳(운영 migration Job의 --migrate-only, 테스트)이 같은 설정을 쓰게 한다.
 * Spring 기동 경로는 application.properties의 spring.flyway.postgresql.transactional-lock으로 맞춘다.
 */
public final class FlywayDefaults {

    /**
     * Flyway 기본값(transactional lock)은 advisory lock을 잡은 트랜잭션을 migration 내내 열어 둔다.
     * CREATE INDEX CONCURRENTLY는 먼저 시작한 트랜잭션이 끝나길 기다리므로 그 잠금 트랜잭션을 기다리다 멈춘다.
     * 세션 단위 advisory lock을 쓰면 열린 트랜잭션이 없어 CONCURRENTLY migration(V85, V87)이 끝난다.
     */
    public static final Map<String, String> PROPERTIES = Map.of("flyway.postgresql.transactional.lock", "false");

    private FlywayDefaults() {
    }

    public static FluentConfiguration configure() {
        return Flyway.configure().configuration(PROPERTIES);
    }
}
