package fruition.core.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpProductGuideClient {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final McpProperties properties;

    public McpProductGuideClient(PipelineClientFactory factory, ObjectMapper mapper, McpProperties properties) {
        this.client = factory.restClient(properties.dependencyTimeoutSeconds());
        this.mapper = mapper;
        this.properties = properties;
    }

    public Response read() {
        try {
            return client.get().uri(properties.wikiSearchUri().resolve("../product-guide"))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() != 200
                                || response.getHeaders().getContentType() == null
                                || !MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType()))
                            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
                        int limit = properties.maxResponseBytes();
                        if (response.getHeaders().getContentLength() > limit)
                            throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
                        var input = response.getBody();
                        byte[] bytes = input.readNBytes(limit);
                        if (input.read() != -1) throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
                        var body = mapper.readTree(bytes);
                        if (body == null || !body.isObject() || body.size() != 3
                                || !"mcp.product-guide.v1".equals(body.path("schema_version").textValue())
                                || !body.path("title").isTextual() || body.path("title").textValue().isBlank()
                                || !body.path("markdown").isTextual() || body.path("markdown").textValue().isBlank())
                            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
                        return mapper.treeToValue(body, Response.class);
                    });
        } catch (McpToolException e) { throw e; }
        catch (Exception e) { throw new McpToolException("DEPENDENCY_UNAVAILABLE"); }
    }

    public record Response(@JsonProperty("schema_version") String schemaVersion, String title, String markdown) {}
}
