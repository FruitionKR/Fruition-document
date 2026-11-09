package fruition.core.mcp;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpTokenVerifier {
    private static final Set<String> SCOPES = Set.of("read", "write_propose", "wiki_write");
    private final JwtParser parser;
    private final String audience;

    public McpTokenVerifier(McpProperties properties,
                            @Value("${app.jwt.issuer}") String issuer,
                            @Value("${app.jwt.public-key}") String publicPem,
                            @Value("${app.jwt.key-id}") String keyId,
                            @Value("${app.jwt.previous-public-key:}") String previousPem,
                            @Value("${app.jwt.previous-key-id:}") String previousKeyId) {
        if (keyId.isBlank() || publicPem.isBlank() || (!previousPem.isBlank()
                && (previousKeyId.isBlank() || keyId.equals(previousKeyId))))
            throw new IllegalArgumentException("MCP requires distinct RSA verification key IDs");
        PublicKey current = parseKey(publicPem);
        PublicKey previous = previousPem.isBlank() ? null : parseKey(previousPem);
        this.audience = properties.resourceUri().toString();
        parser = Jwts.parser().keyLocator(header -> {
            if (!"RS256".equals(header.getAlgorithm())) throw new UnsupportedJwtException("RS256 required");
            if (keyId.equals(header.get("kid"))) return current;
            if (previous != null && previousKeyId.equals(header.get("kid"))) return previous;
            throw new UnsupportedJwtException("unknown kid");
        }).requireIssuer(issuer).requireAudience(audience).build();
    }

    public Token verify(String compact) {
        Claims c = parser.parseSignedClaims(compact).getPayload();
        if (!Set.of(audience).equals(c.getAudience())) throw new MalformedJwtException("invalid audience");
        String sub = text(c, "sub"), workspace = text(c, "workspace_id"), client = text(c, "client_id");
        String grant = text(c, "grant_id"), jti = text(c, "jti");
        Number generation = c.get("policy_generation", Number.class);
        if (c.getExpiration() == null || c.getIssuedAt() == null || c.getIssuedAt().toInstant().isAfter(Instant.now().plusSeconds(30))
                || generation == null || generation.longValue() < 1 || !isUuid4(grant) || jti.isBlank()
                || !isHttpsClient(client)) throw new MalformedJwtException("missing or invalid MCP claims");
        Object raw = c.get("scope");
        List<?> values = raw instanceof String s ? List.of(s.split(" ")) : raw instanceof List<?> l ? l : List.of();
        Set<String> scopes = new HashSet<>();
        for (Object value : values) if (!(value instanceof String s) || !SCOPES.contains(s) || !scopes.add(s))
            throw new MalformedJwtException("invalid scope");
        if (scopes.isEmpty()) throw new MalformedJwtException("scope required");
        return new Token(sub, workspace, client, grant, generation.longValue(), Set.copyOf(scopes));
    }

    private static String text(Claims c, String key) {
        String value = c.get(key, String.class);
        if (value == null || value.isBlank() || value.length() > 512) throw new MalformedJwtException("missing claim");
        return value;
    }
    private static boolean isUuid4(String value) {
        try { return UUID.fromString(value).version() == 4 && UUID.fromString(value).toString().equals(value); }
        catch (RuntimeException e) { return false; }
    }
    private static boolean isHttpsClient(String value) {
        try { var u = java.net.URI.create(value); return "https".equals(u.getScheme()) && u.getHost() != null && u.getFragment() == null; }
        catch (RuntimeException e) { return false; }
    }
    private static PublicKey parseKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", ""));
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            if (((RSAKey) key).getModulus().bitLength() < 2048) throw new IllegalArgumentException();
            return key;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid MCP RSA public key"); }
    }
    public record Token(String userId, String workspaceId, String clientId, String grantId,
                        long policyGeneration, Set<String> scopes) {}
}
