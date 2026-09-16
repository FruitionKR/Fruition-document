package db.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V36BackfillApplyReadyMarkdownTest {

    @Test
    void backfillsValidRowsAndFailsRowsWithoutUsableEventsIndividually() throws Exception {
        Context context = mock(Context.class);
        Connection connection = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement updateReady = mock(PreparedStatement.class);
        PreparedStatement updateFailed = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(context.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("LEFT JOIN"))).thenReturn(select);
        when(connection.prepareStatement(contains("ready_markdown = ?"))).thenReturn(updateReady);
        when(connection.prepareStatement(contains("status = 'failed'"))).thenReturn(updateFailed);
        when(select.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, true, false);
        when(rows.getString(1)).thenReturn("run-valid", "run-malformed", "run-unsupported");
        when(rows.getString(2)).thenReturn(
                "{\"request\":{},\"payload\":{\"action\":\"markdown_create\",\"generated_markdown\":{\"markdown\":\"# ok\"}}}",
                "{",
                "{\"request\":{},\"payload\":{\"action\":\"chat_answer\"}}"
        );

        new V36__backfill_apply_ready_markdown().migrate(context);

        verify(updateReady).setString(1, "# ok");
        verify(updateReady).setString(2, "run-valid");
        verify(updateReady).executeUpdate();
        verify(updateFailed).setString(1, "run-malformed");
        verify(updateFailed).setString(1, "run-unsupported");
        verify(updateFailed, org.mockito.Mockito.times(2)).executeUpdate();
    }
}
