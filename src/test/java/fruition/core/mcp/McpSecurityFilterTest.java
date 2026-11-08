package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpSecurityFilterTest {
    @Test void missingBearerGetsPathAwareProtectedResourceChallenge() throws Exception {
        Fixture f = fixture(); var request = new MockHttpServletRequest("POST", "/mcp");
        request.setSecure(true); request.setServerName("mcp.example"); request.setServerPort(443);
        var response = new MockHttpServletResponse();
        f.filter.doFilter(request, response, (r, s) -> { throw new AssertionError(); });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(
                "Bearer resource_metadata=\"https://mcp.example/.well-known/oauth-protected-resource/mcp\"");
    }

    @Test void rejectsOriginMismatchAndNeverTrustsForwardedHttps() throws Exception {
        Fixture f = fixture();
        var origin = f.request(); origin.setSecure(true); origin.setServerName("mcp.example"); origin.setServerPort(443);
        origin.addHeader("Origin", "https://evil.example");
        f.filter.doFilter(origin, new MockHttpServletResponse(), (r, s) -> { throw new AssertionError(); });
        var forwarded = f.request(); forwarded.setSecure(false); forwarded.setServerName("internal"); forwarded.setServerPort(8080);
        forwarded.addHeader("Forwarded", "proto=https;host=mcp.example");
        var response = new MockHttpServletResponse();
        f.filter.doFilter(forwarded, response, (r, s) -> { throw new AssertionError(); });
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test void nativeMissingOriginStillAuthenticatesAndGzipUsesDecompressedFourMibCap() throws Exception {
        Fixture f = fixture(); var request = f.request(); request.setSecure(true); request.setServerName("mcp.example"); request.setServerPort(443);
        request.addHeader("Content-Encoding", "gzip"); request.setContent(gzip(new byte[McpSecurityFilter.MAX_REQUEST_BYTES + 1]));
        var response = new MockHttpServletResponse(); AtomicBoolean invoked = new AtomicBoolean();
        f.filter.doFilter(request, response, (r, s) -> invoked.set(true));
        assertThat(response.getStatus()).isEqualTo(413); assertThat(invoked).isFalse();
    }

    @Test void insufficientReadScopeGetsStepUpChallengeButOtherForbiddenResponsesDoNot() throws Exception {
        var verifier = mock(McpTokenVerifier.class); var access = mock(McpAccessClient.class);
        var token = new McpTokenVerifier.Token("user", "workspace", "https://client.example/client.json",
                "grant", 1, Set.of("write_propose"));
        when(verifier.verify(anyString())).thenReturn(token);
        when(access.authorize(token, Set.of("read")))
                .thenThrow(new McpAuthorizationException(403, "INSUFFICIENT_SCOPE"));
        var filter = new McpSecurityFilter(McpTokenVerifierTest.props(), verifier, access, new ObjectMapper());
        var request = new MockHttpServletRequest("POST", "/mcp"); request.addHeader("Authorization", "Bearer token");
        request.setSecure(true); request.setServerName("mcp.example"); request.setServerPort(443);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (r, s) -> { throw new AssertionError(); });
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(
                "Bearer error=\"insufficient_scope\", scope=\"read\", resource_metadata=\""
                        + "https://mcp.example/.well-known/oauth-protected-resource/mcp\"");

        org.mockito.Mockito.reset(access);
        when(access.authorize(token, Set.of("read"))).thenThrow(new McpAuthorizationException(403, "ACCESS_DENIED"));
        request = new MockHttpServletRequest("POST", "/mcp"); request.addHeader("Authorization", "Bearer token");
        request.setSecure(true); request.setServerName("mcp.example"); request.setServerPort(443);
        var other = new MockHttpServletResponse();
        filter.doFilter(request, other, (r, s) -> { throw new AssertionError(); });
        assertThat(other.getStatus()).isEqualTo(403);
        assertThat(other.getHeader("WWW-Authenticate")).isNull();
    }

    @Test void proposalCallPreflightsWriteScopeWithoutRequiringRead() throws Exception {
        var verifier = mock(McpTokenVerifier.class); var access = mock(McpAccessClient.class);
        var token = new McpTokenVerifier.Token("user", "workspace", "https://client.example/client.json",
                "grant", 1, Set.of("write_propose"));
        when(verifier.verify(anyString())).thenReturn(token);
        when(access.authorize(token, Set.of("write_propose")))
                .thenThrow(new McpAuthorizationException(403, "INSUFFICIENT_SCOPE"));
        var filter = new McpSecurityFilter(McpTokenVerifierTest.props(), verifier, access, new ObjectMapper());
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer token");
        request.setSecure(true); request.setServerName("mcp.example"); request.setServerPort(443);
        request.setContent("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"propose_document_edit","arguments":{}}}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (r, s) -> { throw new AssertionError(); });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(
                "Bearer error=\"insufficient_scope\", scope=\"write_propose\", resource_metadata=\""
                        + "https://mcp.example/.well-known/oauth-protected-resource/mcp\"");
        verify(access).authorize(token, Set.of("write_propose"));
    }

    @Test void wikiToolCallRequiresWikiWriteAndReadOnlyGrantNeverReachesHandler() throws Exception {
        var verifier = mock(McpTokenVerifier.class); var access = mock(McpAccessClient.class);
        var token = new McpTokenVerifier.Token("user", "workspace", "https://client.example/client.json",
                "grant", 1, Set.of("read", "write_propose"));
        when(verifier.verify(anyString())).thenReturn(token);
        when(access.authorize(token, Set.of("wiki_write")))
                .thenThrow(new McpAuthorizationException(403, "INSUFFICIENT_SCOPE"));
        var filter = new McpSecurityFilter(McpTokenVerifierTest.props(), verifier, access, new ObjectMapper());
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer token");
        request.setSecure(true); request.setServerName("mcp.example"); request.setServerPort(443);
        request.setContent("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"start_ingest","arguments":{}}}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (r, s) -> { throw new AssertionError(); });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("WWW-Authenticate")).contains("scope=\"wiki_write\"");
        verify(access).authorize(token, Set.of("wiki_write"));
    }

    @Test void pdfConversionProposalPreflightsWriteProposeScope() throws Exception {
        var verifier = mock(McpTokenVerifier.class); var access = mock(McpAccessClient.class);
        var token = new McpTokenVerifier.Token("user", "workspace", "https://client.example/client.json",
                "grant", 1, Set.of("read"));
        when(verifier.verify(anyString())).thenReturn(token);
        when(access.authorize(token, Set.of("write_propose")))
                .thenThrow(new McpAuthorizationException(403, "INSUFFICIENT_SCOPE"));
        var filter = new McpSecurityFilter(McpTokenVerifierTest.props(), verifier, access, new ObjectMapper());
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer token");
        request.setSecure(true); request.setServerName("mcp.example"); request.setServerPort(443);
        request.setContent("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"propose_pdf_conversion","arguments":{}}}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (r, s) -> { throw new AssertionError(); });

        assertThat(response.getStatus()).isEqualTo(403);
        verify(access).authorize(token, Set.of("write_propose"));
    }

    private static byte[] gzip(byte[] input) throws Exception {
        var out = new ByteArrayOutputStream(); try (var gzip = new GZIPOutputStream(out)) { gzip.write(input); }
        return out.toByteArray();
    }
    private static Fixture fixture() {
        var verifier = mock(McpTokenVerifier.class); var access = mock(McpAccessClient.class);
        var token = new McpTokenVerifier.Token("user", "workspace", "https://client.example/client.json", "grant", 1, Set.of("read"));
        when(verifier.verify(anyString())).thenReturn(token);
        when(access.authorize(token, Set.of("read"))).thenReturn(new McpIdentity("user", "workspace", token.clientId(), "grant", 1,
                Set.of("read"), "MEMBER", Set.of()));
        return new Fixture(new McpSecurityFilter(McpTokenVerifierTest.props(), verifier, access, new ObjectMapper()));
    }
    private record Fixture(McpSecurityFilter filter) {
        MockHttpServletRequest request() { var r = new MockHttpServletRequest("POST", "/mcp");
            r.addHeader("Authorization", "Bearer token"); return r; }
    }
}
