package fruition.core.document.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.domain.Document;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentAccessPolicyMcpTest {
    @Test void directMcpAccessNeverFallsBackToCachedOwner() throws Exception {
        var guard = mock(WorkspaceAccessGuard.class); var jdbc = mock(JdbcTemplate.class); var rs = mock(ResultSet.class);
        when(rs.getString("document_id")).thenReturn("doc_a"); when(rs.getString("access")).thenReturn("revoked");
        doAnswer(inv -> { ((RowCallbackHandler) inv.getArgument(1)).processRow(rs); return null; })
                .when(jdbc).query(anyString(), any(RowCallbackHandler.class), eq("workspace"));
        var policy = new DocumentAccessPolicy(guard, jdbc);
        var viewer = policy.viewer("workspace", "member", false, false);
        var document = new Document("doc_a", "workspace", "other", "a.md", "text/markdown", 1, null, "a".repeat(64));
        assertThat(viewer.canRead(document)).isFalse();
        verifyNoInteractions(guard);
    }
}
