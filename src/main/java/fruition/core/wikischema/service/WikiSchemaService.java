package fruition.core.wikischema.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.wikischema.dto.WikiSchemaDraftRequest;
import fruition.core.wikischema.dto.WikiSchemaPreviewRequest;
import fruition.core.wikischema.repository.PipelineWikiSchemaRequester;
import fruition.core.authz.WorkspaceAccessGuard;
import org.springframework.stereotype.Service;

@Service
public class WikiSchemaService {

    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final PipelineWikiSchemaRequester requester;

    public WikiSchemaService(WorkspaceAccessGuard workspaceAccessGuard,
                             PipelineWikiSchemaRequester requester) {
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.requester = requester;
    }

    public JsonNode preview(String workspaceId, String userId, WikiSchemaPreviewRequest request) {
        verifyWorkspaceMembership(workspaceId, userId);
        return requester.preview(request.rawMarkdown());
    }

    public JsonNode createDraft(String workspaceId, String userId, WikiSchemaDraftRequest request) {
        verifyWorkspaceMembership(workspaceId, userId);
        return requester.createDraft(request.rawMarkdown(), request.name(), workspaceId, userId);
    }

    public JsonNode activate(String workspaceId, String userId, String schemaId) {
        verifyWorkspaceMembership(workspaceId, userId);
        return requester.activate(schemaId);
    }

    public JsonNode getActive(String workspaceId, String userId) {
        verifyWorkspaceMembership(workspaceId, userId);
        return requester.getActive(workspaceId, userId);
    }

    private void verifyWorkspaceMembership(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
    }
}
