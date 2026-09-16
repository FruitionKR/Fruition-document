package fruition.core.agent.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunCommandRepositoryTest {

    @Mock JdbcTemplate jdbcTemplate;

    @Test
    void findMapsStoredResultAndError() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("run_id")).thenReturn("agent_1");
        when(resultSet.getString("document_id")).thenReturn("doc_1");
        when(resultSet.getLong("base_version")).thenReturn(7L);
        when(resultSet.getString("apply_operation_id")).thenReturn("op_1");
        when(resultSet.getString("status")).thenReturn("failed");
        when(resultSet.getString("result")).thenReturn("{\"changed\":true}");
        when(resultSet.getString("error_code")).thenReturn("agent_turn_failed");
        doAnswer(invocation -> {
            ResultSetExtractor<?> extractor = invocation.getArgument(1);
            return extractor.extractData(resultSet);
        }).when(jdbcTemplate).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));

        var view = new AgentRunCommandRepository(jdbcTemplate, new ObjectMapper())
                .find("ws_1", "user_1", "agent_1").orElseThrow();

        assertThat(view.status()).isEqualTo("failed");
        assertThat(view.result()).isEqualTo(new ObjectMapper().readTree("{\"changed\":true}"));
        assertThat(view.error()).isEqualTo("agent_turn_failed");
    }
}
