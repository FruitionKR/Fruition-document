package fruition.core.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.skill.dto.SkillAuthoringRequest;
import fruition.core.skill.dto.SkillPublishRequest;
import fruition.core.skill.dto.SkillUpdateRequest;
import fruition.core.skill.repository.PipelineSkillRequester;
import org.springframework.stereotype.Service;

@Service
public class SkillService {

    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final PipelineSkillRequester requester;
    private final WorkspaceAiModelClient workspaceAiModelClient;

    public SkillService(WorkspaceAccessGuard workspaceAccessGuard, PipelineSkillRequester requester,
                        WorkspaceAiModelClient workspaceAiModelClient) {
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.requester = requester;
        this.workspaceAiModelClient = workspaceAiModelClient;
    }

    public JsonNode author(String workspaceId, String userId, SkillAuthoringRequest request, String runId) {
        requireMember(workspaceId, userId);
        return requester.author(workspaceId, userId, request, workspaceAiModelClient.get(workspaceId), runId);
    }

    public JsonNode publish(String workspaceId, String userId, SkillPublishRequest request, String runId) {
        requireMember(workspaceId, userId);
        return requester.publish(workspaceId, userId, request, workspaceAiModelClient.get(workspaceId), runId);
    }

    public JsonNode list(String workspaceId, String userId) {
        requireMember(workspaceId, userId);
        return requester.list(workspaceId, userId);
    }

    public JsonNode get(String workspaceId, String userId, String skillId) {
        requireMember(workspaceId, userId);
        return requester.get(workspaceId, userId, skillId);
    }

    public JsonNode update(String workspaceId, String userId, String skillId, SkillUpdateRequest request, String runId) {
        requireMember(workspaceId, userId);
        return requester.update(workspaceId, userId, skillId, request, workspaceAiModelClient.get(workspaceId), runId);
    }

    public JsonNode setEnabled(String workspaceId, String userId, String skillId, boolean enabled) {
        requireMember(workspaceId, userId);
        return requester.setEnabled(workspaceId, userId, skillId, enabled);
    }

    private void requireMember(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
    }
}
