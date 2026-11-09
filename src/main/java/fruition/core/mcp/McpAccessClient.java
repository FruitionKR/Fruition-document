package fruition.core.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpAccessClient {
    private final RestClient client;
    private final String baseUrl;
    private final ObjectMapper mapper;

    public McpAccessClient(PipelineClientFactory factory, ObjectMapper mapper, McpProperties properties,
                           @Value("${app.internal.access-base-url}") String baseUrl) {
        this.client = factory.restClient(properties.dependencyTimeoutSeconds());
        this.baseUrl = baseUrl;
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public McpIdentity authorize(McpTokenVerifier.Token token) {
        return authorize(token, Set.of("read"));
    }

    public McpIdentity authorize(McpTokenVerifier.Token token, Set<String> requiredScopes) {
        return authorizeCurrent(new TokenContext(token.userId(), token.workspaceId(), token.clientId(),
                        token.policyGeneration(), token.scopes()), token.grantId(), "TOOL_CALL", requiredScopes,
                Set.of(), true, "ACTIVE_GRANT", null, Set.of("ACTIVE"));
    }

    public McpIdentity authorizeProposal(McpProposalActor actor) {
        return authorizeCurrent(new TokenContext(actor.userId(), actor.workspaceId(), actor.clientId(),
                        actor.policyGeneration(), Set.of("write_propose")), actor.grantId(), "PROPOSAL_CREATE",
                Set.of("write_propose"), Set.of(), false, "ACTIVE_GRANT", null, Set.of("ACTIVE"));
    }

    public McpIdentity authorizeWebApproval(McpProposalActor actor) {
        return authorizeCurrent(new TokenContext(actor.userId(), actor.workspaceId(), actor.clientId(),
                        actor.policyGeneration(), Set.of("write_propose")), actor.grantId(), "WEB_APPROVAL",
                Set.of("write_propose"), Set.of(), false, "ACTIVE_GRANT", null, Set.of("ACTIVE"));
    }

    public McpIdentity authorizeOperationBoundary(McpProposalActor actor, long approvedPolicyGeneration) {
        if (approvedPolicyGeneration != actor.policyGeneration()) {
            throw new McpAuthorizationException(403, "ACCESS_DENIED");
        }
        return authorizeCurrent(new TokenContext(actor.userId(), actor.workspaceId(), actor.clientId(),
                        actor.policyGeneration(), Set.of("write_propose")), actor.grantId(), "OPERATION_BOUNDARY",
                Set.of(), Set.of(), false, "EXPLICIT_WEB_APPROVAL", approvedPolicyGeneration,
                Set.of("ACTIVE", "USER_DISCONNECTED"));
    }

    public McpIdentity authorizeWikiSource(McpProposalActor actor) {
        String permission = "submit_lint_changes".equals(actor.toolName()) ? "wiki.lint" : "wiki.ingest";
        if (!Set.of("start_ingest", "submit_lint_changes").contains(actor.toolName())) {
            throw new McpAuthorizationException(403, "ACCESS_DENIED");
        }
        return authorizeCurrent(new TokenContext(actor.userId(), actor.workspaceId(), actor.clientId(),
                        actor.policyGeneration(), Set.of("wiki_write")), actor.grantId(), "PROPOSAL_CREATE",
                Set.of("wiki_write"), Set.of(permission), false, "ACTIVE_GRANT", null, Set.of("ACTIVE"));
    }

    public McpIdentity authorizeWikiProposal(McpProposalActor actor, String permission) {
        return authorizeCurrent(new TokenContext(actor.userId(), actor.workspaceId(), actor.clientId(),
                        actor.policyGeneration(), Set.of("wiki_write")), actor.grantId(), "PROPOSAL_CREATE",
                Set.of("wiki_write"), Set.of(permission), false, "ACTIVE_GRANT", null, Set.of("ACTIVE"));
    }

    public McpIdentity authorizeWikiWebApproval(McpProposalActor actor, String permission) {
        return authorizeCurrent(new TokenContext(actor.userId(), actor.workspaceId(), actor.clientId(),
                        actor.policyGeneration(), Set.of("wiki_write")), actor.grantId(), "WEB_APPROVAL",
                Set.of("wiki_write"), Set.of(permission), false, "ACTIVE_GRANT", null, Set.of("ACTIVE"));
    }

    public McpIdentity authorizeWikiOperationBoundary(McpProposalActor actor, long approvedGeneration,
                                                      String permission) {
        if (approvedGeneration != actor.policyGeneration()) {
            throw new McpAuthorizationException(403, "POLICY_GENERATION_MISMATCH");
        }
        return authorizeCurrent(new TokenContext(actor.userId(), actor.workspaceId(), actor.clientId(),
                        actor.policyGeneration(), Set.of("wiki_write")), actor.grantId(), "OPERATION_BOUNDARY",
                Set.of(), Set.of(permission), false, "EXPLICIT_WEB_APPROVAL", approvedGeneration,
                Set.of("ACTIVE", "USER_DISCONNECTED"));
    }

    private McpIdentity authorizeCurrent(TokenContext context, String grantId, String phase,
                                         Set<String> requiredScopes, Set<String> requiredPermissions,
                                         boolean exactTokenScopes,
                                         String executionBasis, Long approvedPolicyGeneration,
                                         Set<String> allowedGrantStatuses) {
        if (requiredScopes == null || requiredPermissions == null
                || !Set.of("read", "write_propose", "wiki_write").containsAll(requiredScopes)
                || !Set.of("wiki.ingest", "wiki.lint").containsAll(requiredPermissions)) {
            throw new McpAuthorizationException(403, "INSUFFICIENT_SCOPE");
        }
        try {
            String raw = client.post().uri(baseUrl + "/internal/mcp/grants/" + grantId + "/authorize")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(context,
                            new Action(phase, requiredScopes, requiredPermissions, executionBasis, approvedPolicyGeneration)))
                    .retrieve().body(String.class);
            Response r = mapper.readValue(raw, Response.class);
            if (!"ALLOW".equals(r.decision) || r.grant == null || r.policy == null || r.currentAccess == null
                    || !allowedGrantStatuses.contains(r.grant.status) || !r.policy.enabled
                    || "NONE".equals(r.currentAccess.role)
                    || !grantId.equals(r.grant.grantId) || !context.subject.equals(r.grant.userId)
                    || !context.workspaceId.equals(r.grant.workspaceId) || !context.clientId.equals(r.grant.clientId)
                    || (exactTokenScopes ? !context.scopes.equals(r.grant.scopes) : !r.grant.scopes.containsAll(requiredScopes))
                    || context.policyGeneration != r.policy.generation
                    || !r.policy.allowedScopes.containsAll(requiredScopes)
                    || !r.effectiveScopes.containsAll(requiredScopes)
                    || !Set.copyOf(r.currentAccess.permissions == null ? List.of() : r.currentAccess.permissions)
                            .containsAll(requiredPermissions)
                    || ("WEB_APPROVAL".equals(phase) && (r.clientName == null || r.clientName.isBlank()))) {
                throw new McpAuthorizationException(403, r.denyReason == null ? "ACCESS_DENIED" : r.denyReason);
            }
            return new McpIdentity(context.subject, context.workspaceId, context.clientId, grantId,
                    context.policyGeneration, Set.copyOf(r.grant.scopes), r.currentAccess.role,
                    Set.copyOf(r.currentAccess.permissions == null ? List.of() : r.currentAccess.permissions),
                    r.clientName);
        } catch (McpAuthorizationException e) {
            throw e;
        } catch (Exception e) {
            throw new McpAuthorizationException(503, "AUTHZ_UNAVAILABLE");
        }
    }

    record Request(@JsonProperty("token_context") TokenContext tokenContext, Action action) {}
    record TokenContext(String subject, @JsonProperty("workspace_id") String workspaceId,
                        @JsonProperty("client_id") String clientId,
                        @JsonProperty("policy_generation") long policyGeneration, Set<String> scopes) {}
    record Action(String phase, @JsonProperty("required_scopes") Set<String> requiredScopes,
                  @JsonProperty("required_permissions") Set<String> requiredPermissions,
                  @JsonProperty("execution_basis") String executionBasis,
                  @JsonProperty("approved_policy_generation") Long approvedPolicyGeneration) {}
    record Response(String decision, @JsonProperty("deny_reason") String denyReason, Grant grant, Policy policy,
                    @JsonProperty("current_access") CurrentAccess currentAccess,
                    @JsonProperty("effective_scopes") Set<String> effectiveScopes,
                    @JsonProperty("client_name") String clientName,
                    @JsonProperty("checked_at") String checkedAt) {}
    record Grant(@JsonProperty("grant_id") String grantId, String status,
                 @JsonProperty("revocation_reason") String revocationReason,
                 @JsonProperty("user_id") String userId, @JsonProperty("workspace_id") String workspaceId,
                 @JsonProperty("client_id") String clientId, Set<String> scopes) {}
    record Policy(boolean enabled, long generation, @JsonProperty("allowed_scopes") Set<String> allowedScopes) {}
    record CurrentAccess(String role, List<String> permissions) {}
}
