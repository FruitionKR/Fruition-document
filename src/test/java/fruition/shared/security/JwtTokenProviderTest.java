package fruition.shared.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {
    private static final String LEGACY = "jwt-secret-INSECURE-NOT-FOR-PRODUCTION-test";
    private static KeyPair pair() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(bytes)
                + "\n-----END " + type + "-----";
    }
    private JwtTokenProvider verifier(KeyPair pair, KeyPair previous, String legacy, String until) {
        return new JwtTokenProvider(legacy, 900, "fruition-access", "fruition-api",
                pem("PUBLIC KEY", pair.getPublic().getEncoded()), "current",
                previous == null ? "" : pem("PUBLIC KEY", previous.getPublic().getEncoded()),
                previous == null ? "" : "previous", "production", until);
    }
    private String token(KeyPair pair, String kid, String issuer, String audience, Instant expiry) {
        return Jwts.builder().subject("audit-test-user").issuer(issuer).audience().add(audience).and()
                .expiration(Date.from(expiry)).header().keyId(kid).and()
                .signWith(pair.getPrivate(), Jwts.SIG.RS256).compact();
    }
    @Test void verifiesOnlyTrustedKeysAlgorithmIssuerAudienceAndExpiry() throws Exception {
        var pair = pair();
        var verifier = verifier(pair, null, "", "");
        String valid = token(pair, "current", "fruition-access", "fruition-api", Instant.now().plusSeconds(60));
        assertTrue(verifier.isValid(valid));
        assertEquals("audit-test-user", verifier.extractUserId(valid));
        assertFalse(verifier.isValid(token(pair(), "current", "fruition-access", "fruition-api", Instant.now().plusSeconds(60))));
        assertFalse(verifier.isValid(token(pair, "unknown", "fruition-access", "fruition-api", Instant.now().plusSeconds(60))));
        assertFalse(verifier.isValid(token(pair, "current", "wrong", "fruition-api", Instant.now().plusSeconds(60))));
        assertFalse(verifier.isValid(token(pair, "current", "fruition-access", "wrong", Instant.now().plusSeconds(60))));
        assertFalse(verifier.isValid(token(pair, "current", "fruition-access", "fruition-api", Instant.now().minusSeconds(60))));
        assertFalse(verifier.isValid("not-a-jwt"));
        String confused = Jwts.builder().subject("audit-test-user").issuer("fruition-access")
                .audience().add("fruition-api").and().header().keyId("current").and()
                .signWith(Keys.hmacShaKeyFor(pair.getPublic().getEncoded()), Jwts.SIG.HS256).compact();
        assertFalse(verifier.isValid(confused));
    }
    @Test void permitsExplicitPreviousPublicKeyForRotation() throws Exception {
        var old = pair();
        var verifier = verifier(pair(), old, "", "");
        assertTrue(verifier.isValid(token(old, "previous", "fruition-access", "fruition-api", Instant.now().plusSeconds(60))));
    }
    @Test void legacyTransitionExpiresAndCanBeRemoved() throws Exception {
        var pair = pair();
        var old = new JwtTokenProvider(LEGACY, 900, "fruition-access", "fruition-api");
        String token = old.generateAccessToken("audit-test-user", "test@example.invalid");
        assertTrue(verifier(pair, null, LEGACY, Instant.now().plusSeconds(60).toString()).isValid(token));
        assertFalse(verifier(pair, null, LEGACY, Instant.now().minusSeconds(1).toString()).isValid(token));
        assertFalse(verifier(pair, null, "", "").isValid(token));
        assertThrows(IllegalArgumentException.class, () -> verifier(pair, null, LEGACY, ""));
        assertThrows(IllegalArgumentException.class, () -> verifier(pair, null, LEGACY, Instant.now().plusSeconds(172800).toString()));
    }
    @Test void productionVerifierCannotIssueTokens() throws Exception {
        var provider = verifier(pair(), null, "", "");
        assertThrows(IllegalStateException.class, () -> provider.generateAccessToken("user", "test@example.invalid"));
    }

    @Test void productionRequiresPublicKeyAndDistinctKeyIds() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenProvider(LEGACY, 900,
                "fruition-access", "fruition-api", "", "", "", "", "local, production", ""));
        var pair = pair();
        String publicPem = pem("PUBLIC KEY", pair.getPublic().getEncoded());
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenProvider("", 900,
                "fruition-access", "fruition-api", publicPem, "", "", "", "production", ""));
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenProvider("", 900,
                "fruition-access", "fruition-api", publicPem, "same", publicPem, "same", "production", ""));
    }

    @Test void rejectsPrivateWeakAndMalformedPublicKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        assertThrows(IllegalArgumentException.class, () -> verifier(generator.generateKeyPair(), null, "", ""));
        var pair = pair();
        for (String invalid : new String[] {"invalid-pem", pem("PRIVATE KEY", pair.getPrivate().getEncoded())}) {
            assertThrows(IllegalArgumentException.class, () -> new JwtTokenProvider("", 900,
                    "fruition-access", "fruition-api", invalid, "current", "", "", "production", ""));
        }
    }

    @Test void rejectsOtherRsaAlgorithmsEvenWithTrustedKey() throws Exception {
        var pair = pair();
        String token = Jwts.builder().subject("audit-test-user").issuer("fruition-access")
                .audience().add("fruition-api").and().header().keyId("current").and()
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(pair.getPrivate(), Jwts.SIG.RS384).compact();
        assertFalse(verifier(pair, null, "", "").isValid(token));
    }
}
