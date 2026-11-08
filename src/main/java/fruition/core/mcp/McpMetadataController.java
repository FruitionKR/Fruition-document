package fruition.core.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpMetadataController {
    private final McpProperties properties;
    public McpMetadataController(McpProperties properties) { this.properties = properties; }
    @Operation(operationId = "getMcpProtectedResourceMetadata",
            summary = "MCP protected resource metadata 조회")
    @GetMapping("/.well-known/oauth-protected-resource/mcp")
    Metadata metadata() {
        return new Metadata(properties.resourceUri().toString(),
                List.of(properties.authorizationServerUri().toString()), List.of("read"));
    }
    record Metadata(String resource, @JsonProperty("authorization_servers") List<String> authorizationServers,
                    @JsonProperty("scopes_supported") List<String> scopesSupported) {}
}
