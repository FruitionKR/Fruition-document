package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpCursorCodecTest {
    @Test void cursorIsBoundToUserWorkspaceGrantClientPolicyAndTool() {
        var codec = new McpCursorCodec(new ObjectMapper(), McpTokenVerifierTest.props());
        var owner = identity("user-a", "workspace-a", "grant-a");
        String cursor = codec.encode(owner, "find_documents", Map.of("o", 1));
        assertThat(codec.decode(cursor, owner, "find_documents").get("o")).isEqualTo(1);
        assertThatThrownBy(() -> codec.decode(cursor, identity("user-b", "workspace-a", "grant-a"), "find_documents"))
                .isInstanceOf(McpToolException.class).hasMessage("HANDLE_NOT_FOUND");
        assertThatThrownBy(() -> codec.decode(cursor, owner, "get_document"))
                .isInstanceOf(McpToolException.class).hasMessage("HANDLE_NOT_FOUND");
        assertThatThrownBy(() -> codec.decode(cursor + "x", owner, "find_documents"))
                .isInstanceOf(McpToolException.class).hasMessage("HANDLE_NOT_FOUND");
    }
    private static McpIdentity identity(String user, String workspace, String grant) {
        return new McpIdentity(user, workspace, "https://client.example/client.json", grant, 1,
                Set.of("read"), "MEMBER", Set.of());
    }
}
