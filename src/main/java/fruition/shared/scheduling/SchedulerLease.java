package fruition.shared.scheduling;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 여러 Pod가 같은 주기로 도는 스케줄 작업 중 이번 회차를 한 Pod만 하게 한다. 외부 호출을 Pod 수만큼 반복하는
 * 작업(AI 상태 조회·원본 해시·환율)에 쓴다. 결과는 원래 여러 Pod가 돌아도 같아야 한다(배타 보장이 아니다).
 *
 * <p>잠금 행의 만료 시각을 조건부 UPSERT 한 문장으로 미래로 민다. 문장이 끝나면 커넥션을 돌려주므로 외부 호출 동안
 * 커넥션이나 세션 잠금을 쥐지 않는다. 작업이 끝나도 풀지 않고 {@code hold} 동안 다른 Pod가 이 회차를 건너뛰게 한다.
 * 작업이 {@code hold}보다 오래 걸리거나 잡은 Pod가 죽으면 만료 뒤 다른 Pod가 이어서 한다.
 */
@Component
public class SchedulerLease {

    private final JdbcTemplate jdbc;

    public SchedulerLease(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code hold} 동안 이 작업을 맡았으면 true. 주기 작업은 주기보다 조금 짧게 잡는다. 잡은 Pod가 다음 회차에도
     * 만료 뒤라 이어서 맡고, 다른 Pod의 회차는 건너뛰어 전체 실행 간격이 한 Pod일 때와 같아진다.
     */
    public boolean tryAcquire(String name, Duration hold) {
        return jdbc.update("""
                INSERT INTO scheduler_leases (name, locked_until) VALUES (?, now() + ? * interval '1 millisecond')
                ON CONFLICT (name) DO UPDATE SET locked_until = EXCLUDED.locked_until
                WHERE scheduler_leases.locked_until <= now()
                """, name, hold.toMillis()) == 1;
    }

    /** 고정 간격 작업의 기본 점유 시간. 주기의 80%라 잡은 Pod가 다음 회차를 놓치지 않는다. */
    public static Duration forInterval(long intervalMs) {
        return Duration.ofMillis(intervalMs * 4 / 5);
    }
}
