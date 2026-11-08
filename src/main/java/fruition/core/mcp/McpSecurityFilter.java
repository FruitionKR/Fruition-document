package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

public class McpSecurityFilter extends OncePerRequestFilter {
    public static final String IDENTITY = McpIdentity.class.getName();
    public static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;
    private final McpProperties properties;
    private final McpTokenVerifier verifier;
    private final McpAccessClient access;
    private final ObjectMapper mapper;

    public McpSecurityFilter(McpProperties properties, McpTokenVerifier verifier,
                             McpAccessClient access, ObjectMapper mapper) {
        this.properties = properties; this.verifier = verifier; this.access = access; this.mapper = mapper;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        String challengeScope = "read";
        try {
            validateEndpoint(request);
            String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (auth == null || !auth.startsWith("Bearer ") || auth.length() <= 7)
                throw new McpAuthorizationException(401, "INVALID_TOKEN");
            McpTokenVerifier.Token token = verifier.verify(auth.substring(7));
            CachedBodyRequest body = bodyRequest(request);
            Set<String> requiredScopes = requiredScopes(body.bytes());
            challengeScope = String.join(" ", requiredScopes);
            McpIdentity identity = access.authorize(token, requiredScopes);
            body.setAttribute(IDENTITY, identity);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(identity, null, List.of()));
            chain.doFilter(body, response);
        } catch (McpAuthorizationException | JwtException | IllegalArgumentException e) {
            int status = e instanceof McpAuthorizationException a ? a.status() : 401;
            String code = e instanceof McpAuthorizationException a ? a.code() : "INVALID_TOKEN";
            if (status == 401) response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                    "Bearer resource_metadata=\"" + properties.metadataUri() + "\"");
            else if (status == 403 && "INSUFFICIENT_SCOPE".equals(code))
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\", "
                        + "scope=\"" + challengeScope + "\", resource_metadata=\"" + properties.metadataUri() + "\"");
            write(response, status, code);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void validateEndpoint(HttpServletRequest r) {
        URI expected = properties.resourceUri();
        int port = r.getServerPort();
        int expectedPort = expected.getPort() < 0 ? 443 : expected.getPort();
        if (!r.isSecure() || !expected.getHost().equalsIgnoreCase(r.getServerName()) || port != expectedPort)
            throw new McpAuthorizationException(400, "CANONICAL_ENDPOINT_REQUIRED");
        String origin = r.getHeader(HttpHeaders.ORIGIN);
        if (origin == null) return;
        try {
            URI value = URI.create(origin);
            if ("null".equals(origin) || value.getUserInfo() != null || value.getQuery() != null
                    || value.getFragment() != null || (!value.getPath().isEmpty() && !"/".equals(value.getPath()))
                    || properties.allowedOrigins().stream().noneMatch(value::equals))
                throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new McpAuthorizationException(403, "ORIGIN_DENIED"); }
    }

    private CachedBodyRequest bodyRequest(HttpServletRequest r) {
        String encoding = r.getHeader(HttpHeaders.CONTENT_ENCODING);
        boolean identity = encoding == null || encoding.isBlank() || "identity".equalsIgnoreCase(encoding);
        if (!identity && !"gzip".equalsIgnoreCase(encoding))
            throw new McpAuthorizationException(415, "UNSUPPORTED_CONTENT_ENCODING");
        byte[] bytes;
        try (var in = identity ? r.getInputStream() : new GZIPInputStream(r.getInputStream());
             var out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192]; int read;
            while ((read = in.read(chunk)) >= 0) {
                if (out.size() + read > MAX_REQUEST_BYTES) throw new McpAuthorizationException(413, "REQUEST_TOO_LARGE");
                out.write(chunk, 0, read);
            }
            bytes = out.toByteArray();
        } catch (McpAuthorizationException e) { throw e; }
        catch (IOException e) { throw new McpAuthorizationException(400, "INVALID_REQUEST"); }
        return new CachedBodyRequest(r, bytes);
    }

    private Set<String> requiredScopes(byte[] body) {
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        try {
            var root = mapper.readTree(body);
            if (root != null && root.isArray()) root.forEach(item -> addScope(item, scopes));
            else addScope(root, scopes);
        } catch (Exception ignored) {
            // Malformed JSON remains an SDK protocol error after ordinary read authorization.
        }
        if (scopes.isEmpty()) scopes.add("read");
        return scopes;
    }

    private static void addScope(com.fasterxml.jackson.databind.JsonNode request, Set<String> scopes) {
        if (request == null || !request.isObject()) return;
        if (!"tools/call".equals(request.path("method").asText())) return;
        String name = request.path("params").path("name").asText();
        scopes.add(Set.of("propose_document_edit", "propose_plan").contains(name) ? "write_propose" : "read");
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] bytes;
        private CachedBodyRequest(HttpServletRequest request, byte[] bytes) {
            super(request);
            this.bytes = bytes;
        }
        private byte[] bytes() { return bytes; }
        @Override public jakarta.servlet.ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            return new jakarta.servlet.ServletInputStream() {
                public int read() { return in.read(); }
                public boolean isFinished() { return in.available() == 0; }
                public boolean isReady() { return true; }
                public void setReadListener(jakarta.servlet.ReadListener listener) {}
            };
        }
        @Override public String getHeader(String name) {
            if (HttpHeaders.CONTENT_ENCODING.equalsIgnoreCase(name)) return null;
            if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) return Integer.toString(bytes.length);
            return super.getHeader(name);
        }
        @Override public int getContentLength() { return bytes.length; }
        @Override public long getContentLengthLong() { return bytes.length; }
    }

    private void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), Map.of("error", code));
    }
}
