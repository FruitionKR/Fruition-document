package fruition.core.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Savepoint;
import java.time.Instant;
import java.util.*;

/** No JPA calls or transactional proxy inside the savepoint: PostgreSQL errors cannot poison product commits. */
@Component
public class AnalyticsRecorder {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AnalyticsSettings settings;
    public AnalyticsRecorder(JdbcTemplate jdbc, ObjectMapper mapper, AnalyticsSettings settings) {
        this.jdbc = jdbc; this.mapper = mapper; this.settings = settings;
    }
    public void safely(Runnable work) {
        if (!settings.enabled()) return;
        metadata(work);
    }
    /** Product run provenance is retained while capture is disabled, without writing an event. */
    public void metadata(Runnable work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            LoggerFactory.getLogger(getClass()).warn("analytics skipped: no product transaction");
            return;
        }
        Connection connection = DataSourceUtils.getConnection(Objects.requireNonNull(jdbc.getDataSource()));
        Savepoint point = null;
        try {
            point = connection.setSavepoint();
            work.run();
            connection.releaseSavepoint(point);
        } catch (Exception error) {
            try {
                if (point != null) { connection.rollback(point); connection.releaseSavepoint(point); }
            } catch (Exception rollbackError) {
                throw new IllegalStateException("Could not restore analytics savepoint", rollbackError);
            }
            // Never log exception messages/payloads: SQL exceptions can contain user data.
            LoggerFactory.getLogger(getClass()).warn("analytics recording failed: {}", error.getClass().getSimpleName());
        } finally { DataSourceUtils.releaseConnection(connection, jdbc.getDataSource()); }
    }
    public void record(String event, String key, String actor, String workspace, Instant occurred, Map<String,Object> properties) {
        safely(() -> insert(event, key, actor, workspace, occurred, properties));
    }
    void insert(String event, String key, String actor, String workspace, Instant occurred, Map<String,Object> properties) {
        AnalyticsSchema.validate(event, properties);
        if (actor == null || actor.isBlank() || workspace == null || workspace.isBlank())
            throw new IllegalArgumentException("Missing analytics identity");
        UUID id = eventId(event, key);
        var props = new LinkedHashMap<>(properties);
        props.put("event_id", id.toString()); props.put("schema_version", 1);
        props.put("event_source", "document_server"); props.put("environment", settings.environment());
        props.put("app_version", settings.appVersion()); props.put("$groups", Map.of("workspace", workspace));
        props.put("workspace_id", workspace);
        var envelope = Map.of("uuid", id.toString(), "event", event, "distinct_id", actor,
                "timestamp", occurred.toString(), "properties", props);
        try {
            jdbc.update("INSERT INTO analytics_outbox(event_id, event_name, payload, occurred_at) VALUES (?, ?, CAST(? AS jsonb), ?) ON CONFLICT DO NOTHING",
                    id, event, mapper.writeValueAsString(envelope), java.sql.Timestamp.from(occurred));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }
    public static UUID eventId(String event, String key) {
        try {
            UUID namespace = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            sha.update(ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits()).putLong(namespace.getLeastSignificantBits()).array());
            byte[] bytes = sha.digest(("fruition.analytics.v1:" + event + ":" + key).getBytes(StandardCharsets.UTF_8));
            bytes[6] = (byte)((bytes[6] & 0x0f) | 0x50); bytes[8] = (byte)((bytes[8] & 0x3f) | 0x80);
            ByteBuffer b = ByteBuffer.wrap(bytes); return new UUID(b.getLong(), b.getLong());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
