package fruition.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.shared.util.StorageProperties;
import io.jsonwebtoken.Jwts;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.MinioClient;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.mcp.enabled=true",
        "test.isolated-database=i2_mcp_read",
        "app.mcp.resource-uri=https://mcp.example/mcp",
        "app.mcp.authorization-server-uri=https://auth.example",
        "app.mcp.allowed-origins=https://client.example",
        "app.mcp.wiki-search-uri=https://ai.example/internal/mcp/wiki/search",
        "app.mcp.cursor-secret=01234567890123456789012345678901",
        "app.mcp.max-page-size=10",
        "app.mcp.max-candidates=20",
        "app.mcp.max-response-bytes=100000",
        "app.mcp.max-cursor-bytes=4096",
        "app.mcp.max-source-blocks=100",
        "app.mcp.max-source-bytes=100000",
        "app.mcp.max-document-blocks-per-page=10",
        "app.mcp.max-document-content-bytes=10000",
        "app.mcp.dependency-timeout-seconds=5",
        "app.jwt.issuer=issuer",
        "app.jwt.key-id=kid",
        "app.jwt.secret=",
        "app.internal.callback-token=internal-test"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class McpEnabledIntegrationTest {
    private static final String USER = "user_i2_mcp";
    private static final String WORKSPACE = "ws_i2_mcp";
    private static final String CLIENT = "https://client.example/client.json";
    private static final String GRANT = "123e4567-e89b-42d3-a456-426614174000";
    private static final Set<String> OBJECTS = ConcurrentHashMap.newKeySet();
    private static KeyPair keys;
    private static MockWebServer access;
    private static MockWebServer ai;
    private static SSLContext defaultTls;

    static {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keys = generator.generateKeyPair();
            ai = new MockWebServer();
            configureAiTls(ai);
            ai.start();
            access = new MockWebServer();
            access.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                            {"decision":"ALLOW","deny_reason":null,
                             "grant":{"grant_id":"123e4567-e89b-42d3-a456-426614174000","status":"ACTIVE",
                               "revocation_reason":null,"user_id":"user_i2_mcp","workspace_id":"ws_i2_mcp",
                               "client_id":"https://client.example/client.json","scopes":["read"]},
                             "policy":{"enabled":true,"generation":2,"allowed_scopes":["read"]},
                             "current_access":{"role":"MEMBER","permissions":[]},
                             "effective_scopes":["read"],"checked_at":"2026-01-01T00:00:00Z"}
                            """);
                }
            });
            access.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired @Qualifier("mcpServlet") ServletRegistrationBean<HttpServletStatelessServerTransport> registration;
    @Autowired McpStatelessSyncServer server;
    @Autowired McpProperties properties;
    @Autowired McpDocumentReadService reads;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MinioClient minio;
    @Autowired StorageProperties storage;

    @DynamicPropertySource static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.public-key", McpEnabledIntegrationTest::publicPem);
        registry.add("app.internal.access-base-url", () -> access.url("/").toString().replaceAll("/$", ""));
        registry.add("app.mcp.wiki-search-uri", () -> ai.url("/internal/mcp/wiki/search").toString());
    }

    @AfterEach void cleanOwnRowsAndObjects() throws Exception {
        jdbc.update("DELETE FROM document_edit_states WHERE document_id LIKE 'doc_i2_%'");
        jdbc.update("DELETE FROM documents WHERE id LIKE 'doc_i2_%' OR id LIKE 'chatdoc_i2_%'");
        for (String object : OBJECTS) {
            minio.removeObject(RemoveObjectArgs.builder().bucket(storage.getBucket()).object(object).build());
        }
        OBJECTS.clear();
    }

    @AfterAll static void stopServers() throws Exception {
        try {
            access.shutdown();
            ai.shutdown();
        } finally {
            SSLContext.setDefault(defaultTls);
        }
    }

    @Test void enabledSpringRegistersFilterServletServerAndRunsSdkProtocol() throws Exception {
        insertDocument("doc_i2_transport", "Transport proof", "a".repeat(64), 1,
                "EDITABLE", null, null, null, 1);

        assertThat(properties.resourceUri().toString()).isEqualTo("https://mcp.example/mcp");
        assertThat(server).isNotNull();
        assertThat(registration.getUrlMappings()).containsExactly("/mcp");
        mvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("https://mcp.example/mcp"))
                .andExpect(jsonPath("$.authorization_servers[0]").value("https://auth.example"))
                .andExpect(jsonPath("$.scopes_supported[0]").value("read"));

        JsonNode initialized = rpc("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
                "capabilities":{},"clientInfo":{"name":"integration","version":"1"}}}
                """);
        assertThat(initialized.path("result").path("protocolVersion").asText()).isEqualTo("2025-11-25");

        JsonNode tools = rpc("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);
        assertThat(tools.path("result").path("tools")).extracting(tool -> tool.path("name").asText())
                .containsExactly("search_wiki", "find_documents", "get_document", "get_wiki_page",
                        "propose_document_edit", "propose_plan", "start_ingest", "resume_ingest", "get_window",
                        "submit_window", "finalize_ingest", "submit_lint_changes", "list_skills", "get_skill", "propose_skill_definition",
                        "list_meetings", "get_meeting_transcript", "start_pdf_conversion",
                        "get_conversion_window", "submit_conversion_window", "finalize_pdf_conversion");

        JsonNode call = rpc("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"find_documents",
                "arguments":{"query":"Transport","cursor":null,"page_size":1}}}
                """);
        assertThat(call.path("result").path("isError").asBoolean()).isFalse();
        assertThat(call.path("result").path("structuredContent").path("results").get(0)
                .path("document_id").asText()).isEqualTo("doc_i2_transport");

        String rawTitle = "[Wiki](https://evil.example/title)";
        String rawBody = "![diagram](https://evil.example/image.png)\n# Wiki 한글  \n";
        String pageBody = "외부 이미지(evil.example)\n# Wiki 한글  \n";
        ai.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
                mapper.writeValueAsString(Map.of("schema_version", "mcp.wiki-page.v1",
                        "page_id", "wiki_page_i2", "page_type", "SOURCE", "title", rawTitle,
                        "body", rawBody, "content_hash", "sha256:" + sha256(rawBody),
                        "data_boundary", "UNTRUSTED_WORKSPACE_CONTENT"))));
        JsonNode pageCall = rpc("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_wiki_page",
                "arguments":{"page_id":"wiki_page_i2"}}}
                """);
        assertThat(pageCall.path("result").path("isError").asBoolean()).isFalse();
        assertThat(pageCall.path("result").path("structuredContent").path("title").asText())
                .isEqualTo("Wiki (evil.example)");
        assertThat(pageCall.path("result").path("structuredContent").path("body").asText()).isEqualTo(pageBody);
        assertThat(pageCall.path("result").path("structuredContent").path("content_hash").asText())
                .isEqualTo("sha256:" + sha256(pageBody));
        JsonNode textContent = mapper.readTree(pageCall.path("result").path("content").get(0).path("text").asText());
        assertThat(textContent.path("title").asText()).isEqualTo("Wiki (evil.example)");
        assertThat(textContent.path("body").asText()).isEqualTo(pageBody);
        assertThat(textContent.path("content_hash").asText()).isEqualTo("sha256:" + sha256(pageBody));

        RecordedRequest pageRequest = ai.takeRequest(5, TimeUnit.SECONDS);
        assertThat(pageRequest).isNotNull();
        assertThat(pageRequest.getMethod()).isEqualTo("GET");
        assertThat(pageRequest.getRequestUrl().encodedPath()).isEqualTo("/wiki/pages/wiki_page_i2");
        assertThat(pageRequest.getRequestUrl().queryParameter("workspace_id")).isEqualTo(WORKSPACE);
        assertThat(pageRequest.getRequestUrl().queryParameter("mcp_read")).isEqualTo("true");
        assertThat(pageRequest.getHeader("X-Internal-Token")).isEqualTo("internal-test");
    }

    @Test void readsNeverIngestedChatAndEditedDocumentsFromCurrentDocumentState() throws Exception {
        String lazyBody = "# Lazy 한글  \n\nTrailing whitespace  \n";
        String lazyHash = sha256(lazyBody);
        String lazyObject = "sources/documents/doc_i2_lazy/original";
        insertDocument("doc_i2_lazy", "Lazy", lazyHash, 1, "EDITABLE", "upload", lazyObject, null,
                lazyBody.getBytes(StandardCharsets.UTF_8).length);

        String neverBody = "# Never ingested\n\nCurrent body\n";
        String neverHash = sha256(neverBody);
        String object = "sources/documents/doc_i2_never/original";
        put(object, neverBody);
        insertDocument("doc_i2_never", "Never", neverHash, 1, "EDITABLE", "upload", object, null,
                neverBody.getBytes(StandardCharsets.UTF_8).length);

        String chatBody = "# Chat 한글\n\nQ: hello  \nA: 세계  \n";
        insertDocument("chatdoc_i2_supported", "Chat", "d".repeat(64), 1, "EDITABLE", "chat_export",
                "sources/documents/chatdoc_i2_supported/original", chatBody,
                chatBody.getBytes(StandardCharsets.UTF_8).length);

        String editedBody = "# Edited\n\nnew state\n";
        String editedHash = sha256(editedBody);
        insertDocument("doc_i2_edited", "Edited", editedHash, 1, "EDITABLE", "upload", null, null,
                editedBody.getBytes(StandardCharsets.UTF_8).length);
        jdbc.update("""
                INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at)
                VALUES (?, ?, ?, 2, now(), now())
                """, "doc_i2_edited", editedBody, editedHash);

        String postgresVersion = jdbc.queryForObject("SHOW server_version", String.class);
        assertThat(jdbc.queryForObject("SELECT encode(sha256(convert_to(?, 'UTF8')), 'hex')", String.class, chatBody))
                .isEqualTo(sha256(chatBody));
        System.out.println("MCP identity PostgreSQL version=" + postgresVersion);

        var lazySearch = result(reads.find(identity(), "Lazy", null, 10).results(), "doc_i2_lazy");
        var chatSearch = result(reads.find(identity(), "Chat", null, 10).results(), "chatdoc_i2_supported");
        var editedSearch = result(reads.find(identity(), "Edited", null, 10).results(), "doc_i2_edited");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_edit_states WHERE document_id = ?",
                Integer.class, "doc_i2_lazy")).isZero();
        put(lazyObject, lazyBody);

        var never = reads.get(identity(), "doc_i2_never", null);
        var chat = reads.get(identity(), "chatdoc_i2_supported", null);
        var edited = reads.get(identity(), "doc_i2_edited", null);
        var lazy = reads.get(identity(), "doc_i2_lazy", null);

        assertSameIdentity(lazySearch, lazy);
        assertThat(text(lazy)).isEqualTo(lazyBody);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_edit_states WHERE document_id = ?",
                Integer.class, "doc_i2_lazy")).isOne();
        assertThat(text(never)).isEqualTo(neverBody);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_edit_states WHERE document_id = ?",
                Integer.class, "doc_i2_never")).isOne();
        assertThat(text(chat)).isEqualTo(chatBody);
        assertThat(chat.contentHash()).isEqualTo("sha256:" + sha256(chatBody));
        assertThat(text(edited)).isEqualTo(editedBody);
        assertThat(edited.currentVersion()).isEqualTo(2);
        assertThat(edited.contentHash()).isEqualTo("sha256:" + editedHash);
        assertSameIdentity(chatSearch, chat);
        assertSameIdentity(editedSearch, edited);
    }

    private static McpDocumentReadService.DocumentResult result(
            java.util.List<McpDocumentReadService.DocumentResult> results, String documentId) {
        return results.stream().filter(result -> result.documentId().equals(documentId)).findFirst().orElseThrow();
    }

    private static void assertSameIdentity(McpDocumentReadService.DocumentResult search,
                                           McpDocumentReadService.DocumentReadResponse read) {
        assertThat(search.currentVersion()).isEqualTo(read.currentVersion());
        assertThat(search.contentHash()).isEqualTo(read.contentHash());
    }

    private JsonNode rpc(String body) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setSecure(true); request.setServerName("mcp.example"); request.setServerPort(443);
        request.setAsyncSupported(true); request.setContentType("application/json");
        request.addHeader("Accept", "application/json, text/event-stream");
        request.addHeader("Authorization", "Bearer " + token());
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        springSecurityFilterChain.doFilter(request, response, (rawRequest, rawResponse) ->
                registration.getServlet().service((HttpServletRequest) rawRequest, (HttpServletResponse) rawResponse));
        assertThat(response.getStatus()).isEqualTo(200);
        String responseBody = response.getContentAsString(StandardCharsets.UTF_8);
        if (responseBody.startsWith("event:")) {
            responseBody = responseBody.lines().filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).trim()).findFirst().orElseThrow();
        }
        return mapper.readTree(responseBody);
    }

    private void insertDocument(String id, String displayName, String currentHash, long currentVersion,
                                String role, String origin, String sourceUri, String pipelineMarkdown,
                                long byteSize) {
        jdbc.update("""
                INSERT INTO documents(id, workspace_id, user_id, filename, display_name, normalized_filename,
                    mime_type, byte_size, status, source_uri, content_hash, current_content_hash, current_version,
                    document_role, sort_order, uploaded_at, updated_at, origin, pipeline_input_markdown)
                VALUES (?, ?, ?, ?, ?, ?, 'text/markdown', ?, 'completed', ?, ?, ?, ?, ?, 0, now(), now(), ?, ?)
                """, id, WORKSPACE, USER, displayName + ".md", displayName, displayName.toLowerCase(), byteSize,
                sourceUri, "f".repeat(64), currentHash, currentVersion, role, origin, pipelineMarkdown);
    }

    private void put(String object, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        minio.putObject(PutObjectArgs.builder().bucket(storage.getBucket()).object(object)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1).contentType("text/markdown").build());
        OBJECTS.add(object);
    }

    private static String text(McpDocumentReadService.DocumentReadResponse response) {
        return response.blocks().stream().map(McpDocumentReadService.Block::text)
                .collect(java.util.stream.Collectors.joining());
    }

    private static McpIdentity identity() {
        return new McpIdentity(USER, WORKSPACE, CLIENT, GRANT, 2, Set.of("read"), "MEMBER", Set.of());
    }

    private static String token() {
        Instant now = Instant.now();
        return Jwts.builder().header().keyId("kid").and().subject(USER).issuer("issuer")
                .audience().add("https://mcp.example/mcp").and()
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(300)))
                .claim("workspace_id", WORKSPACE).claim("client_id", CLIENT).claim("grant_id", GRANT)
                .claim("policy_generation", 2).claim("scope", "read").id(UUID.randomUUID().toString())
                .signWith(keys.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static String publicPem() {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
    }

    private static void configureAiTls(MockWebServer server) throws Exception {
        // The real client requires HTTPS; this certificate is trusted only inside this test JVM.
        defaultTls = SSLContext.getDefault();
        var certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(Base64.getDecoder().decode(AI_CERTIFICATE)));
        var privateKey = KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(AI_PRIVATE_KEY)));
        var serverKeys = KeyStore.getInstance("PKCS12");
        serverKeys.load(null);
        serverKeys.setKeyEntry("ai", privateKey, new char[0], new java.security.cert.Certificate[]{certificate});
        var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(serverKeys, new char[0]);
        var serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keyManagers.getKeyManagers(), null, new SecureRandom());
        server.useHttps(serverTls.getSocketFactory(), false);

        var trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null);
        trusted.setCertificateEntry("ai", certificate);
        var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trusted);
        var clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trustManagers.getTrustManagers(), new SecureRandom());
        SSLContext.setDefault(clientTls);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static final String AI_PRIVATE_KEY = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDDXWIvoDucKiAAicK1Xfrs0jIrWrHEqlG+oCaQFOzTGBZsYrSc03ij3O1+QgguXOZZcp2KFtd/a/3X20DW1p+tASbSOLpoDTwptn3eSLemfUQecG62H7mYDIyPNjjRRIUPSdXrKPfmVVe243AL1Tb8y9E3tOeLDnYuf60NLwBl7GWjdmVW5pR+O/ZFxMOJgxTkP547VVuVO0ESuUZEXQa6Fmi+ZmCUdmD6aD1qBpK9nDnnhfKEUwpk+bv/zDeZiTlpoNk4pefo+zJoWiXFpny4cNozQPuozK1GBUDuhw2+bf3zqIJh52iSPnXxOadqkOQ4M46fvnd6INh4gX291JoTAgMBAAECggEAIHEqc+Q04erw44X86mdP2vHQ2Pg3mwP5jSFFJhoDdc6DMr51ZbGMUBHI8nV+rExLCEGLPborUQKa5bSIIOUFIRKW4sP+2HaSLg8a+cWzkZctr6xWBjJgfIpg8Qs9BitNuUs2ldV4zSwQ49MC8UQX3ITwGkMVTDc7kt8q2oyakVIVAzM/qFvIPib1UZYgpXGj40+/32PYlQTGo5oyxODB9ao/St6D7c/XlyRqeuqNKnwQOfY1FAdfFTuOI12++uZybIMbXuv5bTslGwfrN5p3usAoOWmounVg564f2GwLVcbs5NMLVABe8XNiv8z8zr/uwcCDv1mOwOhtxeInTSmt8QKBgQDmsjEiJCpwYCAKn5WLQgfwERT1OgBocW3aERNy2J39z9Nal1OrFblDuh4Hqdn+IguyBK/WzQ6CjF1p+qO3LYwcD9lxptTvJexj4ldy+N8tVrGgT06qblHLh6H4NAq3T6T+fP6WioUVHLtBy4BcBf1LL82TMYgzml+1rVnjNs4myQKBgQDYyxw35JTX+bbg4uj/mVuLdN8ruAR6fnOHgZfAG1UdaslNNwCr7+OusZmDvrEDPDJ+MazNErzfDhAvg4yzf5jIIMs91tkUNVegM+Q09qgiZp5vjmuBDDs+Zi0ZMwUO/3HsfIW9SdSH92X7yDPtwRksLRZ2gXJRFT3Ek7B6q4J7+wKBgQDPJnK4FYGGJsqev22o/kHib3RCPzLelfvuNCcimSBFKLjAP+IBXiBdkGImv33flPKrh0ndt4Ov9hprb4RSl7OX/yNhykyp8YOyrSuZMaA1NgOJz6zznNwVxlTKavC0ae/eFJ8Gzptxubrr+a/o8fff66zM301OCPCoUmDEhhHXAQKBgBoQOSFDGt3oSKM6xHZOMqJOd7Ii2m77mtoUq3DOGGqD9xWQYkVJft72vRUIrPFNoGhpgnPZJ9nNMAb2UskVswGEZ8rUdJ6oVxOMiYgQi2AvZCbXQXdvSAQVdqCL8LS/4ytiQRXjtWhvRtSBWjTgPS2BciznHR5AZn+mndXxnqtXAoGAEU0v63KCDjY2sjJ6OKXjtqq+XJLdFPstb7gvWnM1XmvaX+/bAdeoAxZKZd8p33HLchYULW3OLWRWSJO1a0yMWDgN/G5ToyKzO2Fqw+w8ro09k0cOCb2w8nxV2DLYair28jteen4L9D4yvBEsnTj3zBubUlIUvyvNYfPh/XMjX98=";
    private static final String AI_CERTIFICATE = "MIIDJTCCAg2gAwIBAgIUR2zRzy2Zw1J3YttE3yQFyKkNQWowDQYJKoZIhvcNAQELBQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MTEwODAwMTg0MloXDTM2MTEwNTAwMTg0MlowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAw11iL6A7nCogAInCtV367NIyK1qxxKpRvqAmkBTs0xgWbGK0nNN4o9ztfkIILlzmWXKdihbXf2v919tA1tafrQEm0ji6aA08KbZ93ki3pn1EHnButh+5mAyMjzY40USFD0nV6yj35lVXtuNwC9U2/MvRN7Tniw52Ln+tDS8AZexlo3ZlVuaUfjv2RcTDiYMU5D+eO1VblTtBErlGRF0GuhZovmZglHZg+mg9agaSvZw554XyhFMKZPm7/8w3mYk5aaDZOKXn6PsyaFolxaZ8uHDaM0D7qMytRgVA7ocNvm3986iCYedokj518TmnapDkODOOn753eiDYeIF9vdSaEwIDAQABo28wbTAdBgNVHQ4EFgQUHWmhJoW8Bsk+0iX9R644fhE4SfcwHwYDVR0jBBgwFoAUHWmhJoW8Bsk+0iX9R644fhE4SfcwDwYDVR0TAQH/BAUwAwEB/zAaBgNVHREEEzARgglsb2NhbGhvc3SHBH8AAAEwDQYJKoZIhvcNAQELBQADggEBALn8oCa8QUohVnWQc9cUieJxlRJibnTanl/BYfPwqJX3MtDmkvZrZpwGRzLiwXrJTxBbJn9ttR+ygrjHePqPhfAfGyBl/S3f3XZI2Gx8VZBxiMZ2qQFs9nKz6vqlV5Uez7K+vtps2UOTVDkW1SFBEexq/zCL7GXOUYRyzw7V32kWUTJjtiVX8zeTCtR5ju33noQeY+210kcxrnE80azsEB0YdDhAawxTM0mfz6WqI881g6xqXjleDprNvAjbCOHG1e9fY5rT0XwBAn2LCv43PCY+oGt02OEbfqRSRHDdcymKmVJyTu6+FchRBEVGmtdlhnbmA0RHABR4uTb8i9NPiwI=";
}
