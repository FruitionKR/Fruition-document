package fruition.core.meeting;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 회의당 실시간 연결 1개를 보장하는 Redis 잠금. document-svc Pod가 여러 개라 메모리로는 막을 수 없다.
 * 연결 중 30초마다 연장하고, Pod가 죽으면 90초 뒤 자연히 풀린다.
 */
@Component
public class MeetingLiveLock {
    static final Duration TTL = Duration.ofSeconds(90);

    private final StringRedisTemplate redis;

    public MeetingLiveLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean tryAcquire(String meetingId, String token) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(meetingId), token, TTL));
    }

    public void renew(String meetingId, String token) {
        if (token.equals(redis.opsForValue().get(key(meetingId)))) {
            redis.expire(key(meetingId), TTL);
        }
    }

    // ponytail: 조회와 삭제 사이 경합은 90초 TTL 잠금이 이미 만료·재획득된 드문 경우뿐이다. 문제되면 Lua 비교 삭제로 바꾼다.
    public void release(String meetingId, String token) {
        if (token.equals(redis.opsForValue().get(key(meetingId)))) {
            redis.delete(key(meetingId));
        }
    }

    public boolean isHeld(String meetingId) {
        return Boolean.TRUE.equals(redis.hasKey(key(meetingId)));
    }

    private static String key(String meetingId) {
        return "speech:live:" + meetingId;
    }
}
