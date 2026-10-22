package fruition.core.query.service;

import java.util.List;

/** Exposes the package-private production command to cross-service integration tests. */
public final class AnalyticsQueryCommandFixture {
    private AnalyticsQueryCommandFixture() {}

    public static Object command(String run, String session, QueryService.QueryMessageContext context) {
        return new QueryRunService.QueryCommand(run, "query", "workspace", "original-actor", session,
                "private question", "test", "test", false, null, List.of(), context);
    }
}
