package fruition.core.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** SKIP LOCKED gives each relay a batch; payloads and occurrence times never change on retry. */
@Component
public class AnalyticsRelay {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AnalyticsSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public AnalyticsRelay(JdbcTemplate jdbc, TransactionTemplate transactions, AnalyticsSettings settings, ObjectMapper mapper) {
        this.jdbc = jdbc; this.transactions = transactions; this.settings = settings; this.mapper = mapper;
    }
    @Scheduled(fixedDelayString = "${app.analytics.relay-delay-ms:10000}")
    public void relay() {
        if (!settings.relayEnabled()) return;
        try {
            transactions.executeWithoutResult(tx -> {
                var rows = jdbc.queryForList("""
                    SELECT event_id, payload::text, attempts FROM analytics_outbox
                    WHERE sent_at IS NULL AND dead_at IS NULL AND next_attempt_at <= now()
                    ORDER BY next_attempt_at LIMIT 50 FOR UPDATE SKIP LOCKED
                    """);
                if (rows.isEmpty()) return;
                boolean success = false;
                try {
                    var batch = new ArrayList<Object>();
                    for (var row : rows) batch.add(mapper.readTree((String)row.get("payload")));
                    String body = mapper.writeValueAsString(Map.of("api_key", settings.token(), "batch", batch));
                    var request = HttpRequest.newBuilder(URI.create(settings.host().replaceAll("/+$", "") + "/batch/"))
                            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                    int code = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                    success = code >= 200 && code < 300;
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (Exception e) { /* Only aggregate counters, never bodies/credentials/errors. */ }
                for (var row : rows) {
                    if (success) jdbc.update("UPDATE analytics_outbox SET sent_at = now() WHERE event_id = ?", row.get("event_id"));
                    else {
                        int attempt = ((Number)row.get("attempts")).intValue() + 1;
                        jdbc.update("""
                            UPDATE analytics_outbox SET attempts = ?, next_attempt_at = now() + (? * interval '1 second'),
                              dead_at = CASE WHEN ? >= 12 THEN now() ELSE NULL END WHERE event_id = ?
                            """, attempt, Math.min(3600, 5L << Math.min(attempt, 10)), attempt, row.get("event_id"));
                    }
                }
            });
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("analytics relay database failure: {}", e.getClass().getSimpleName());
        }
    }
}
