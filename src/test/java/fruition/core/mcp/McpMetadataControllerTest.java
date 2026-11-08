package fruition.core.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class McpMetadataControllerTest {
    @Test void servesExactPathAwareProtectedResourceMetadata() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new McpMetadataController(McpTokenVerifierTest.props())).build();
        mvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.resource").value("https://mcp.example/mcp"))
                .andExpect(jsonPath("$.authorization_servers[0]").value("https://auth.example"))
                .andExpect(jsonPath("$.scopes_supported[0]").value("read"));
    }
}
