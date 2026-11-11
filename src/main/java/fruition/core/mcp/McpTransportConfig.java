package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpTransportConfig {
    @Bean @Order(1)
    SecurityFilterChain mcpSecurityChain(HttpSecurity http, McpProperties properties,
                                         McpTokenVerifier verifier, McpAccessClient access,
                                         ObjectMapper mapper) throws Exception {
        var entryPoint = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        http.securityMatcher("/mcp").csrf(c -> c.disable()).cors(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable()).httpBasic(b -> b.disable())
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> {
                    response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                            "Bearer resource_metadata=\"" + properties.metadataUri() + "\"");
                    entryPoint.commence(request, response, ex);
                }))
                .addFilterBefore(new McpSecurityFilter(properties, verifier, access, mapper),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    HttpServletStatelessServerTransport mcpTransport(ObjectMapper mapper) {
        return HttpServletStatelessServerTransport.builder().jsonMapper(new JacksonMcpJsonMapper(mapper))
                .messageEndpoint("/mcp").maxRequestSize(McpSecurityFilter.MAX_REQUEST_BYTES)
                .contextExtractor(request -> McpTransportContext.create(Map.of(McpSecurityFilter.IDENTITY,
                        request.getAttribute(McpSecurityFilter.IDENTITY)))).build();
    }

    @Bean
    McpStatelessSyncServer mcpServer(HttpServletStatelessServerTransport transport, McpToolCatalog catalog) {
        return McpServer.sync(transport).serverInfo("fruition-document-mcp", "mcp-tools.v9")
                .tools(catalog.tools()).build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(HttpServletStatelessServerTransport transport) {
        var registration = new ServletRegistrationBean<>(transport, "/mcp");
        registration.setName("mcpStreamableHttp"); registration.setAsyncSupported(true); registration.setLoadOnStartup(1);
        return registration;
    }
}
