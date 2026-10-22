package fruition.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import org.springframework.beans.factory.annotation.Autowired;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final JwtParser rsaParser;
    private final JwtParser legacyParser;
    private final Instant legacyUntil;
    private final long accessTokenExpirationSeconds;
    private final String issuer;
    private final String audience;

    /** Local/test compatibility; production requires an RSA public key. */
    public JwtTokenProvider(String secret, long ttl, String issuer, String audience) {
        this(secret, ttl, issuer, audience, "", "", "", "", "", "");
    }

    @Autowired
    public JwtTokenProvider(@Value("${app.jwt.secret:}") String secret,
                            @Value("${app.jwt.access-token-expiration-seconds}") long accessTokenExpirationSeconds,
                            @Value("${app.jwt.issuer}") String issuer,
                            @Value("${app.jwt.audience}") String audience,
                            @Value("${app.jwt.public-key:}") String publicPem,
                            @Value("${app.jwt.key-id:}") String keyId,
                            @Value("${app.jwt.previous-public-key:}") String previousPem,
                            @Value("${app.jwt.previous-key-id:}") String previousKeyId,
                            @Value("${spring.profiles.active:}") String profiles,
                            @Value("${app.jwt.legacy-until:}") String legacyUntil) {
        this.accessTokenExpirationSeconds = accessTokenExpirationSeconds;
        this.issuer = issuer;
        this.audience = audience;
        boolean production = Arrays.stream(profiles.split(",")).anyMatch(p -> p.trim().equals("production"));
        if (publicPem.isBlank()) {
            if (production) {
                throw new IllegalArgumentException("Production JWT requires RSA verification keys");
            }
            this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            this.rsaParser = null;
            this.legacyUntil = null;
            this.legacyParser = legacyParser(key, issuer, audience);
        } else {
            if (keyId.isBlank() || (!previousPem.isBlank() && (previousKeyId.isBlank() || keyId.equals(previousKeyId)))) {
                throw new IllegalArgumentException("JWT key IDs must be nonempty and distinct");
            }
            PublicKey current = parsePublicKey(publicPem);
            PublicKey previous = previousPem.isBlank() ? null : parsePublicKey(previousPem);
            this.key = null;
            this.rsaParser = Jwts.parser().keyLocator(header -> {
                if (!"RS256".equals(header.get("alg"))) {
                    throw new UnsupportedJwtException("Only RS256 is accepted");
                }
                if (keyId.equals(header.get("kid"))) return current;
                if (previous != null && previousKeyId.equals(header.get("kid"))) return previous;
                throw new UnsupportedJwtException("Unknown JWT key ID");
            })
                    .requireIssuer(issuer).requireAudience(audience).build();
            if (!secret.isBlank()) {
                if (legacyUntil.isBlank()) {
                    throw new IllegalArgumentException("Legacy JWT verification requires an explicit expiry");
                }
                this.legacyUntil = Instant.parse(legacyUntil);
                if (this.legacyUntil.isAfter(Instant.now().plus(Duration.ofHours(24)))) {
                    throw new IllegalArgumentException("Legacy JWT transition cannot exceed 24 hours");
                }
                this.legacyParser = legacyParser(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), issuer, audience);
            } else {
                this.legacyUntil = null;
                this.legacyParser = null;
            }
        }
    }

    private static JwtParser legacyParser(SecretKey key, String issuer, String audience) {
        return Jwts.parser().keyLocator(header -> {
            if (!java.util.Set.of("HS256", "HS384", "HS512").contains(header.get("alg"))) {
                throw new UnsupportedJwtException("Only HMAC is accepted during legacy verification");
            }
            return key;
        }).requireIssuer(issuer).requireAudience(audience).build();
    }

    private static PublicKey parsePublicKey(String pem) {
        try {
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decodePem(pem, "PUBLIC KEY")));
            if (((RSAKey) key).getModulus().bitLength() < 2048) throw new IllegalArgumentException("RSA key must be at least 2048 bits");
            return key;
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid JWT RSA public key");
        }
    }

    private static byte[] decodePem(String pem, String type) {
        if (!pem.startsWith("-----BEGIN " + type + "-----")) throw new IllegalArgumentException("Invalid PEM type");
        return Base64.getDecoder().decode(pem.replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "").replaceAll("\\s", ""));
    }

    public String generateAccessToken(String userId, String email) {
        if (rsaParser != null) {
            throw new IllegalStateException("This service only verifies JWTs");
        }
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(userId)
                .issuer(issuer)
                .audience().add(audience).and()
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTokenExpirationSeconds)));
        return builder.signWith(key).compact();
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String extractUserId(String token) {
        return parseClaims(token).getSubject();
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationSeconds;
    }

    private Claims parseClaims(String token) {
        Claims claims = parseVerifiedClaims(token);
        if (claims.getExpiration() == null || claims.getSubject() == null || claims.getSubject().isBlank()) {
            throw new MalformedJwtException("JWT requires expiration and a nonblank subject");
        }
        return claims;
    }

    private Claims parseVerifiedClaims(String token) {
        if (rsaParser == null) return legacyParser.parseSignedClaims(token).getPayload();
        try {
            return rsaParser.parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // Time-bounded migration only; removing the old secret disables this path entirely.
            if (legacyParser != null && Instant.now().isBefore(legacyUntil)) {
                return legacyParser.parseSignedClaims(token).getPayload();
            }
            throw e;
        }
    }
}
