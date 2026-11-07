package fruition.shared.logging;

import org.slf4j.Logger;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Per-process, bounded failure summaries. Repeated attempts emit at most once per minute.
 * The next attempt triggers a summary; this class does not create a scheduler or change retries.
 * Call recovered only after the operation succeeds. Idle entries expire without claiming recovery.
 */
public final class RepeatedFailureLogger {
    private final Logger logger;
    private final LongSupplier millis;
    private final long interval;
    private final int capacity;
    private final Map<String, State> states = new LinkedHashMap<>();
    public RepeatedFailureLogger(Logger logger) {
        this(logger, System::currentTimeMillis, Duration.ofMinutes(1), 1024);
    }
    RepeatedFailureLogger(Logger logger, LongSupplier millis, Duration interval, int capacity) {
        if (interval.isZero() || interval.isNegative() || capacity < 1) throw new IllegalArgumentException();
        this.logger = logger; this.millis = millis; this.interval = interval.toMillis(); this.capacity = capacity;
    }
    public void warn(String key, String message, Throwable failure) { failed(key, message, failure, false); }
    public void error(String key, String message, Throwable failure) { failed(key, message, failure, true); }
    private synchronized void failed(String key, String message, Throwable failure, boolean error) {
        long now = millis.getAsLong();
        states.entrySet().removeIf(e -> now - e.getValue().lastSeen > Duration.ofHours(1).toMillis());
        // Different exception types or severities are new failures, not duplicates.
        String fingerprint = (error ? "ERROR:" : "WARN:") + (failure == null ? "none" : failure.getClass().getName());
        State state = states.get(key);
        if (state == null || !state.fingerprint.equals(fingerprint)) {
            if (state == null && states.size() >= capacity) {
                Iterator<String> oldest = states.keySet().iterator(); oldest.next(); oldest.remove();
            }
            states.put(key, new State(now, fingerprint));
            if (error) logger.error("{} failureKey={}", message, key, failure);
            else logger.warn("{} failureKey={}", message, key, failure);
            return;
        }
        state.total++; state.suppressed++; state.lastSeen = now;
        if (now - state.lastReported >= interval) {
            String summary = "[반복 오류 요약] failureKey={} totalCount={} suppressedCount={} elapsedMs={} message={}";
            if (error) logger.error(summary, key, state.total, state.suppressed, now - state.firstSeen, message);
            else logger.warn(summary, key, state.total, state.suppressed, now - state.firstSeen, message);
            state.suppressed = 0; state.lastReported = now;
        }
    }
    public synchronized void recovered(String key) {
        State state = states.remove(key);
        if (state != null) logger.info("[반복 오류 복구] operation=repeated_failure stage=recovered failureKey={} totalCount={} suppressedCount={} elapsedMs={}",
                key, state.total, state.suppressed, millis.getAsLong() - state.firstSeen);
    }
    private static final class State {
        final long firstSeen;
        final String fingerprint;
        long lastSeen, lastReported, total = 1, suppressed;
        State(long now, String fingerprint) {
            firstSeen = lastSeen = lastReported = now; this.fingerprint = fingerprint;
        }
    }
}
