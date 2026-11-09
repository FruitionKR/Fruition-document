package fruition.core.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class McpAgentResponseSerializationTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void internalApprovalAndReceiptResponsesUseTheExactSnakeCaseWireContract() {
        var approval = mapper.valueToTree(new McpAgentApprovalService.ApprovalStatus(
                "VALID", null, "RESERVED", "UNKNOWN", "RESERVED"));
        assertThat(approval.properties().stream().map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of("status", "reason", "lifecycle_status", "forward_status", "settlement_status"));

        UUID attempt = UUID.randomUUID();
        var receipt = mapper.valueToTree(new McpAgentApprovalService.Receipt(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "op-1", attempt,
                "workspace", "user", UUID.randomUUID().toString(), "https://client.example/i5",
                "apply_document_edit", "COMMITTED", true, "DOCUMENT", "doc_1", 1L, 2,
                Instant.parse("2026-11-08T00:00:00Z")));
        assertThat(receipt.properties().stream().map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of("receipt_id", "approval_id", "operation_id", "attempt_id", "workspace_id",
                        "user_id", "grant_id", "client_id", "tool_name", "outcome", "changed",
                        "resource_type", "resource_id", "before_version", "after_version", "committed_at"));
        assertThat(receipt.path("attempt_id").asText()).isEqualTo(attempt.toString());
    }
}
