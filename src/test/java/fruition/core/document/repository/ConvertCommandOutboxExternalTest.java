package fruition.core.document.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** converter worker.parse_command(restoration=external)가 받는 필드 그대로인지 본다(Fruition-ai converter/test_worker.py). */
class ConvertCommandOutboxExternalTest {
    @Test
    void externalPrepareCommandMatchesConverterContractWithoutProvider() throws Exception {
        AiCommandOutboxWriter writer = mock(AiCommandOutboxWriter.class);
        ObjectMapper mapper = new ObjectMapper();
        String conversion = "0f6f3d1e-2b7a-4c8e-9d5f-1a2b3c4d5e6f";

        new ConvertCommandOutbox(writer, mapper, "ai.convert.command").enqueueExternalPrepare(
                conversion, "ws_1", "user_1", "doc_1", "sources/documents/doc_1/original", 10, 10, false);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        String runId = "mcpconvert:" + conversion + ":10";
        verify(writer).enqueueJoined(eq(runId), eq("ai.convert.command"), eq(runId), payload.capture());
        JsonNode command = mapper.readTree(payload.getValue());
        assertThat(Set.copyOf(command.properties().stream().map(java.util.Map.Entry::getKey).toList())).isEqualTo(Set.of(
                "run_id", "parent_run_id", "kind", "restoration", "conversion_id", "workspace_id", "user_id",
                "document_id", "source_key", "start_page", "page_count", "describe_figures"));
        assertThat(command.path("parent_run_id").asText()).isEqualTo("mcpconvert:" + conversion);
        assertThat(command.path("kind").asText()).isEqualTo("convert_batch");
        assertThat(command.path("restoration").asText()).isEqualTo("external");
        assertThat(command.path("start_page").asInt()).isEqualTo(10);
    }
}
