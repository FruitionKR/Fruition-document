package fruition.core.mcp;

import fruition.shared.security.JwtTokenProvider;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpTokenVerifierTest {
    private static KeyPair keys;
    private static McpProperties properties;

    @BeforeAll static void setup() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); keys = generator.generateKeyPair();
        properties = props();
    }

    @Test void acceptsOnlyCompleteRs256McpAudienceClaims() {
        var verifier = verifier();
        assertThat(verifier.verify(token("https://mcp.example/mcp", "issuer", "kid", true)).workspaceId())
                .isEqualTo("workspace-a");
        assertThatThrownBy(() -> verifier.verify(token("fruition-api", "issuer", "kid", true))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> verifier.verify(token("https://mcp.example/mcp", "wrong", "kid", true))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> verifier.verify(token("https://mcp.example/mcp", "issuer", "unknown", true))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> verifier.verify(token("https://mcp.example/mcp", "issuer", "kid", false))).isInstanceOf(RuntimeException.class);
    }

    @Test void webVerifierRejectsMcpAudience() {
        var web = new JwtTokenProvider("", 60, "issuer", "fruition-api", pem(), "kid", "", "", "production", "");
        assertThat(web.isValid(token("https://mcp.example/mcp", "issuer", "kid", true))).isFalse();
    }

    @Test void dualAudienceTokenIsRejectedAtBothBoundaries() {
        String token = token(List.of("https://mcp.example/mcp", "fruition-api"), "issuer", "kid", true);
        assertThatThrownBy(() -> verifier().verify(token)).isInstanceOf(RuntimeException.class);
        var web = new JwtTokenProvider("", 60, "issuer", "fruition-api", pem(), "kid", "", "", "production", "");
        assertThat(web.isValid(token)).isFalse();
    }

    private McpTokenVerifier verifier() { return new McpTokenVerifier(properties, "issuer", pem(), "kid", "", ""); }
    private String token(String audience, String issuer, String kid, boolean required) {
        return token(List.of(audience), issuer, kid, required);
    }
    private String token(List<String> audiences, String issuer, String kid, boolean required) {
        Instant now = Instant.now();
        var audience = Jwts.builder().header().keyId(kid).and().subject("user-a").issuer(issuer).audience();
        audiences.forEach(audience::add);
        var b = audience.and()
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(300)))
                .claim("workspace_id", "workspace-a").claim("client_id", "https://client.example/client.json")
                .claim("grant_id", UUID.randomUUID().toString()).claim("policy_generation", 2).claim("scope", "read")
                .id(required ? UUID.randomUUID().toString() : "");
        return b.signWith(keys.getPrivate(), Jwts.SIG.RS256).compact();
    }
    private static String pem() { return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'})
            .encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----"; }
    static McpProperties props() { return new McpProperties("https://mcp.example/mcp", "https://auth.example",
            List.of("https://client.example"), "https://ai.example/internal/mcp/wiki/search", "x".repeat(32),
            10, 20, 100_000, 4096, 100, 100_000, 10, 10_000, 5); }
}
