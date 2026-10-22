package fruition.core.agent.service;

/** Exposes the package-private production command to cross-service integration tests. */
public final class AnalyticsAgentCommandFixture {
    private AnalyticsAgentCommandFixture() {}

    public static Object command(String run, String session, String pair, String userMessage, String assistantMessage) {
        var context = new AgentTurnService.AgentMessageContext(pair, userMessage, assistantMessage);
        return new AgentTurnService.AgentCommand(run, "agent", "workspace", "original-actor", session,
                context, null, null, null, "private question", "test", "test", false,
                null, null, null, null, null, null, null, null, "task", null);
    }
}
