package fruition.core.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspacePermissions;
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
        requireAuthorForScope(workspaceId, userId, request.scopeType());
        return requester.author(workspaceId, userId, request, workspaceAiModelClient.get(workspaceId), runId);
    }

    public JsonNode publish(String workspaceId, String userId, SkillPublishRequest request, String runId) {
        requireMember(workspaceId, userId);
        requireAuthorForScope(workspaceId, userId, request.scopeType());
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
        requireAuthorForExisting(workspaceId, userId, skillId);
        return requester.update(workspaceId, userId, skillId, request, workspaceAiModelClient.get(workspaceId), runId);
    }

    public JsonNode setEnabled(String workspaceId, String userId, String skillId, boolean enabled) {
        requireMember(workspaceId, userId);
        requireAuthorForExisting(workspaceId, userId, skillId);
        return requester.setEnabled(workspaceId, userId, skillId, enabled);
    }

    public void delete(String workspaceId, String userId, String skillId) {
        requireMember(workspaceId, userId);
        requireAuthorForExisting(workspaceId, userId, skillId);
        requester.delete(workspaceId, userId, skillId);
    }

    /** 개인 스킬이 아닌 모든 범위(범위 누락 포함)는 skill.author가 필요하다(fail-closed). */
    private void requireAuthorForScope(String workspaceId, String userId, String scopeType) {
        if (!"personal".equals(scopeType)) {
            workspaceAccessGuard.requirePermission(workspaceId, userId, WorkspacePermissions.SKILL_AUTHOR);
        }
    }

    /**
     * 기존 스킬의 범위를 pipeline 조회로 확인한다. 활성화·비활성화도 skills.status를 바꿔
     * 팀 스킬이면 모든 멤버에게 영향을 주므로 수정·삭제와 같이 판정한다.
     */
    private void requireAuthorForExisting(String workspaceId, String userId, String skillId) {
        requireAuthorForScope(workspaceId, userId,
                requester.get(workspaceId, userId, skillId).path("scope_type").asText(null));
    }

    private void requireMember(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
    }
}
