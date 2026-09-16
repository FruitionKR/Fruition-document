package fruition.core.aihistory.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentApplyOperationStoreTest {

    @Mock JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.api.BeforeEach
    void registeredTask() {
        org.mockito.Mockito.lenient().when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.contains("set_config"), org.mockito.ArgumentMatchers.eq(String.class),
                org.mockito.ArgumentMatchers.anyString())).thenReturn("run");
        org.mockito.Mockito.lenient().when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("FOR UPDATE OF task"),
                org.mockito.ArgumentMatchers.eq(String.class), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.List.of("run"));
    }

    @Test
    void consume_acceptsOnlyOneCompletedRun() {
        AgentApplyOperationStore store = new AgentApplyOperationStore(jdbcTemplate);
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("write_1"), org.mockito.ArgumentMatchers.eq("op_1"),
                org.mockito.ArgumentMatchers.eq("user_1"), org.mockito.ArgumentMatchers.eq("doc_1")))
                .thenReturn(1, 0, 0, 0);
        when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Boolean.class),
                org.mockito.ArgumentMatchers.eq("op_1"), org.mockito.ArgumentMatchers.eq("user_1"),
                org.mockito.ArgumentMatchers.eq("doc_1"), org.mockito.ArgumentMatchers.eq("write_1")))
                .thenReturn(true);
        when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Boolean.class),
                org.mockito.ArgumentMatchers.eq("op_1"), org.mockito.ArgumentMatchers.eq("user_1"),
                org.mockito.ArgumentMatchers.eq("doc_1"), org.mockito.ArgumentMatchers.eq("write_2")))
                .thenReturn(false);

        assertThat(store.consume("op_1", "user_1", "doc_1", "write_1")).isTrue();
        assertThat(store.consume("op_1", "user_1", "doc_1", "write_1")).isTrue();
        assertThat(store.consume("op_1", "user_1", "doc_1", "write_2")).isFalse();
        assertThat(store.consume("op_1", "other-user", "doc_1", "write_1")).isFalse();
        verify(jdbcTemplate, times(4)).update(org.mockito.ArgumentMatchers.contains("agent_apply_projections"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void newOperationId_isUnique() {
        AgentApplyOperationStore store = new AgentApplyOperationStore(jdbcTemplate);
        assertThat(store.newOperationId()).isNotEqualTo(store.newOperationId());
    }

    @Test
    void consume_rejectsStaleBaseAndDifferentMarkdown_butRetriesExactPair() {
        AgentApplyOperationStore store = new AgentApplyOperationStore(jdbcTemplate);
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("base_version = ?"),
                org.mockito.ArgumentMatchers.eq("write_1"), org.mockito.ArgumentMatchers.eq("op_1"),
                org.mockito.ArgumentMatchers.eq("user_1"), org.mockito.ArgumentMatchers.eq("doc_1"),
                org.mockito.ArgumentMatchers.eq(6L), org.mockito.ArgumentMatchers.eq("# ready")))
                .thenReturn(0);
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("base_version = ?"),
                org.mockito.ArgumentMatchers.eq("write_1"), org.mockito.ArgumentMatchers.eq("op_1"),
                org.mockito.ArgumentMatchers.eq("user_1"), org.mockito.ArgumentMatchers.eq("doc_1"),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("# altered")))
                .thenReturn(0);
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("base_version = ?"),
                org.mockito.ArgumentMatchers.eq("write_1"), org.mockito.ArgumentMatchers.eq("op_1"),
                org.mockito.ArgumentMatchers.eq("user_1"), org.mockito.ArgumentMatchers.eq("doc_1"),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("# ready")))
                .thenReturn(1, 0);
        when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.contains("base_version = ?"),
                org.mockito.ArgumentMatchers.eq(Boolean.class), org.mockito.ArgumentMatchers.eq("op_1"),
                org.mockito.ArgumentMatchers.eq("user_1"), org.mockito.ArgumentMatchers.eq("doc_1"),
                org.mockito.ArgumentMatchers.eq(6L), org.mockito.ArgumentMatchers.eq("# ready"),
                org.mockito.ArgumentMatchers.eq("write_1"))).thenReturn(false);
        when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.contains("base_version = ?"),
                org.mockito.ArgumentMatchers.eq(Boolean.class), org.mockito.ArgumentMatchers.eq("op_1"),
                org.mockito.ArgumentMatchers.eq("user_1"), org.mockito.ArgumentMatchers.eq("doc_1"),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("# altered"),
                org.mockito.ArgumentMatchers.eq("write_1"))).thenReturn(false);
        when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.contains("base_version = ?"),
                org.mockito.ArgumentMatchers.eq(Boolean.class), org.mockito.ArgumentMatchers.eq("op_1"),
                org.mockito.ArgumentMatchers.eq("user_1"), org.mockito.ArgumentMatchers.eq("doc_1"),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("# ready"),
                org.mockito.ArgumentMatchers.eq("write_1"))).thenReturn(true);

        assertThat(store.consume("op_1", "user_1", "doc_1", "write_1", 6L, "# ready")).isFalse();
        assertThat(store.consume("op_1", "user_1", "doc_1", "write_1", 7L, "# altered")).isFalse();
        assertThat(store.consume("op_1", "user_1", "doc_1", "write_1", 7L, "# ready")).isTrue();
        assertThat(store.consume("op_1", "user_1", "doc_1", "write_1", 7L, "# ready")).isTrue();
        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains("base_version = ?"),
                org.mockito.ArgumentMatchers.eq("write_1"), org.mockito.ArgumentMatchers.eq("op_1"),
                org.mockito.ArgumentMatchers.eq("user_1"), org.mockito.ArgumentMatchers.eq("doc_1"),
                org.mockito.ArgumentMatchers.eq(6L), org.mockito.ArgumentMatchers.eq("# ready"));
        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains("base_version = ?"),
                org.mockito.ArgumentMatchers.eq("write_1"), org.mockito.ArgumentMatchers.eq("op_1"),
                org.mockito.ArgumentMatchers.eq("user_1"), org.mockito.ArgumentMatchers.eq("doc_1"),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("# altered"));
        verify(jdbcTemplate, times(2)).update(org.mockito.ArgumentMatchers.contains("base_version = ?"),
                org.mockito.ArgumentMatchers.eq("write_1"), org.mockito.ArgumentMatchers.eq("op_1"),
                org.mockito.ArgumentMatchers.eq("user_1"), org.mockito.ArgumentMatchers.eq("doc_1"),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("# ready"));
    }
}
