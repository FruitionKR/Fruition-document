package fruition.shared.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 브라우저가 S3에 직접 올리는 multipart 업로드의 공통 절차. 서명된 ticket에 키·크기·uploadId를 담아 두고,
 * 조각별 presigned URL 발급, 중단, 조각 검증 후 조립을 맡는다. 무엇을 올리는지(PDF·회의 녹음)와 확정 후 처리는 호출자가 정한다.
 * ticket 서명 키는 인증 토큰과 분리하여 업로드 ticket의 Bearer 사용을 막는다.
 */
public class DirectMultipartUpload {
    public record Started(String ticket, long partSize, int partCount) {}

    private final MinioClient storage;
    private final MultipartStorage multipart;
    private final StorageProperties properties;
    private final String audience;
    private final SecretKey ticketKey;

    public DirectMultipartUpload(MinioClient storage, MultipartStorage multipart, StorageProperties properties,
                                 String secret, String audience) throws Exception {
        this.storage = storage;
        this.multipart = multipart;
        this.properties = properties;
        this.audience = audience;
        this.ticketKey = Keys.hmacShaKeyFor(MessageDigest.getInstance("SHA-256")
                .digest((secret + ":" + audience + ":v1").getBytes(StandardCharsets.UTF_8)));
    }

    /** multipart를 시작하고 ticket을 발급한다. {@code extraClaims}는 호출자가 확정 때 다시 읽을 값이다. */
    public Started start(String userId, String workspaceId, String key, String contentType, long size,
                         Map<String, Object> extraClaims) throws Exception {
        long partSize = Math.max(64L * 1024 * 1024, (size + 9999) / 10000);
        String uploadId = multipart.start(key, contentType);
        ZonedDateTime expires = ZonedDateTime.now(ZoneOffset.UTC).plusDays(1);
        String ticket = Jwts.builder().subject(userId).audience().add(audience).and().claims(extraClaims)
                .claim("workspace", workspaceId).claim("key", key).claim("size", size)
                .claim("upload", uploadId).claim("partSize", partSize)
                .expiration(Date.from(expires.toInstant())).signWith(ticketKey).compact();
        return new Started(ticket, partSize, partCount(size, partSize));
    }

    /** 서명·만료·발급 대상(사용자·워크스페이스)을 확인한다. 멤버십은 호출자가 이어서 확인한다. */
    public Claims verify(String workspaceId, String userId, String ticket) {
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(ticketKey).requireAudience(audience).build()
                    .parseSignedClaims(ticket).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "업로드 확인 정보가 만료되었거나 올바르지 않습니다.");
        }
        if (!userId.equals(claims.getSubject()) || !workspaceId.equals(claims.get("workspace", String.class))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 사용자의 업로드를 사용할 수 없습니다.");
        }
        return claims;
    }

    /** @return {@code firstPart}부터 {@code count}개 조각의 PUT용 presigned URL(조각 번호 순) */
    public List<String> partUrls(Claims claims, int firstPart, int count) throws Exception {
        long size = ((Number) claims.get("size")).longValue();
        long partSize = ((Number) claims.get("partSize")).longValue();
        int total = partCount(size, partSize);
        if (firstPart < 1 || count < 1 || count > 32 || (long) firstPart + count - 1 > total) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "업로드 조각 범위가 올바르지 않습니다.");
        }
        List<String> urls = new ArrayList<>();
        for (int part = firstPart; part < firstPart + count; part++) {
            urls.add(storage.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(properties.getBucket()).object(claims.get("key", String.class))
                    .method(io.minio.http.Method.PUT).expiry(15 * 60)
                    .extraQueryParams(Map.of("uploadId", claims.get("upload", String.class), "partNumber", String.valueOf(part)))
                    .build()));
        }
        return urls;
    }

    public void abort(Claims claims) throws Exception {
        multipart.abort(claims.get("key", String.class), claims.get("upload", String.class));
    }

    /** 조각 수·크기·순서를 검사해 객체로 조립하고 그 정보를 돌려준다. 이미 조립된 객체가 있으면(재시도) 그대로 쓴다. */
    public StatObjectResponse assemble(Claims claims) throws Exception {
        String key = claims.get("key", String.class);
        try {
            // complete 응답 유실 또는 DB 재시도에서는 이미 조립된 객체를 재사용한다.
            return storage.statObject(StatObjectArgs.builder().bucket(properties.getBucket()).object(key).build());
        } catch (io.minio.errors.ErrorResponseException missing) {
            if (!Set.of("NoSuchKey", "NoSuchObject", "NotFound").contains(missing.errorResponse().code())) throw missing;
        }
        var parts = multipart.parts(key, claims.get("upload", String.class));
        long size = ((Number) claims.get("size")).longValue();
        long partSize = ((Number) claims.get("partSize")).longValue();
        int count = partCount(size, partSize);
        if (parts.size() != count) throw new ResponseStatusException(HttpStatus.CONFLICT, "전송되지 않은 조각이 있습니다.");
        for (int index = 0; index < count; index++) {
            long expected = Math.min(partSize, size - index * partSize);
            if (parts.get(index).partNumber() != index + 1 || parts.get(index).partSize() != expected) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "업로드 조각의 크기 또는 순서가 일치하지 않습니다.");
            }
        }
        multipart.finish(key, claims.get("upload", String.class), parts);
        return storage.statObject(StatObjectArgs.builder().bucket(properties.getBucket()).object(key).build());
    }

    private static int partCount(long size, long partSize) {
        return (int) ((size + partSize - 1) / partSize);
    }
}
