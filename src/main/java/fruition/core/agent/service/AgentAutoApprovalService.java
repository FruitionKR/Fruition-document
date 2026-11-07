package fruition.core.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.agent.dto.AgentAutoApprovalSettings;
import fruition.core.agent.repository.AgentAutoApprovalSettingsRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.DocumentEditTargets;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 사용자의 자동 승인 설정을 읽고 쓰며, 지금 설정과 대상 권한으로 계획을 자동 승인할지 판단한다. */
@Service
public class AgentAutoApprovalService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentAutoApprovalService.class);

    private final AgentAutoApprovalSettingsRepository settingsRepository;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final DocumentAccessPolicy documentAccessPolicy;
    private final DocumentEditTargets editTargets;

    public AgentAutoApprovalService(AgentAutoApprovalSettingsRepository settingsRepository,
                                    WorkspaceAccessGuard workspaceAccessGuard,
                                    DocumentAccessPolicy documentAccessPolicy,
                                    DocumentEditTargets editTargets) {
        this.settingsRepository = settingsRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.documentAccessPolicy = documentAccessPolicy;
        this.editTargets = editTargets;
    }

    /** 경로의 워크스페이스는 인증·라우팅용이다. 설정은 사용자 단위로 모든 워크스페이스에 적용된다. */
    public AgentAutoApprovalSettings get(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        return settingsRepository.find(userId);
    }

    public AgentAutoApprovalSettings update(String workspaceId, String userId, AgentAutoApprovalSettings settings) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        settingsRepository.save(userId, settings);
        log.info("[Agent 자동 승인 설정 변경] operation=agent_auto_approval_update stage=completed workspaceId={} userId={} contentEdit={} organize={}",
                workspaceId, userId, settings.contentEdit(), settings.organize());
        return settingsRepository.find(userId);
    }

    /** 승인 직전에 설정을 다시 읽는다. 계획이 만들어진 뒤 설정을 끄면 그 계획도 수동 승인으로 남는다. */
    public AgentAutoApprovalPolicy.Decision decide(String workspaceId, String userId, JsonNode run) {
        AgentAutoApprovalSettings settings = settingsRepository.find(userId);
        if (!settings.anyEnabled()) {
            return AgentAutoApprovalPolicy.decide(settings, run, null);
        }
        return AgentAutoApprovalPolicy.decide(settings, run, permissions(workspaceId, userId));
    }

    private AgentAutoApprovalPolicy.TargetPermissions permissions(String workspaceId, String userId) {
        DocumentAccessPolicy.Viewer viewer = documentAccessPolicy.viewer(workspaceId, userId);
        return new AgentAutoApprovalPolicy.TargetPermissions() {
            @Override
            public boolean canEditDocument(String documentId) {
                return editTargets.canEditDocument(workspaceId, viewer, documentId);
            }

            @Override
            public boolean canEditFolder(UUID folderId) {
                return editTargets.canEditFolder(workspaceId, viewer, folderId);
            }

            @Override
            public UUID folderOfDocument(String documentId) {
                return editTargets.folderOfDocument(workspaceId, documentId);
            }

            @Override
            public UUID parentOfFolder(UUID folderId) {
                return editTargets.parentOfFolder(workspaceId, folderId);
            }
        };
    }
}
