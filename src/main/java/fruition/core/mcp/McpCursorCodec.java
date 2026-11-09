package fruition.core.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpCursorCodec {
    private final ObjectMapper mapper;
    private final byte[] secret;
    private final int maxBytes;
    public McpCursorCodec(ObjectMapper mapper, McpProperties properties) {
        this.mapper = mapper; this.secret = properties.cursorSecret(); this.maxBytes = properties.maxCursorBytes();
    }
    public String encode(McpIdentity id, String tool, Map<String, Object> values) {
        try {
            Map<String,Object> payload = new LinkedHashMap<>();
            payload.put("u", id.userId()); payload.put("w", id.workspaceId()); payload.put("g", id.grantId());
            payload.put("c", id.clientId()); payload.put("p", id.policyGeneration()); payload.put("t", tool);
            payload.putAll(values);
            byte[] json = mapper.writeValueAsBytes(payload);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(json) + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(json));
            if (token.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
            return token;
        } catch (McpToolException e) { throw e; }
        catch (Exception e) { throw new McpToolException("DEPENDENCY_UNAVAILABLE"); }
    }
    public Map<String,Object> decode(String cursor, McpIdentity id, String tool) {
        try {
            if (cursor == null || cursor.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw new IllegalArgumentException();
            String[] parts = cursor.split("\\.", -1); if (parts.length != 2) throw new IllegalArgumentException();
            byte[] json = Base64.getUrlDecoder().decode(parts[0]);
            if (!MessageDigest.isEqual(hmac(json), Base64.getUrlDecoder().decode(parts[1]))) throw new IllegalArgumentException();
            Map<String,Object> p = mapper.readValue(json, new TypeReference<>() {});
            if (!id.userId().equals(p.get("u")) || !id.workspaceId().equals(p.get("w"))
                    || !id.grantId().equals(p.get("g")) || !id.clientId().equals(p.get("c"))
                    || id.policyGeneration() != ((Number)p.get("p")).longValue() || !tool.equals(p.get("t")))
                throw new IllegalArgumentException();
            return p;
        } catch (RuntimeException e) { throw new McpToolException("HANDLE_NOT_FOUND"); }
        catch (Exception e) { throw new McpToolException("HANDLE_NOT_FOUND"); }
    }
    private byte[] hmac(byte[] bytes) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256")); return mac.doFinal(bytes);
    }
    public static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
