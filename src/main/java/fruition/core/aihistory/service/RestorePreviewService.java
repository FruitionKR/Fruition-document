package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.dto.DocumentRestorePlan;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.dto.RestorePreviewResponse;
import fruition.core.aihistory.exception.OperationNotFoundException;
import fruition.core.aihistory.exception.InvalidRestoreRequestException;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 복구 미리보기. 무엇이 삭제·복원·재작성되는지 계산해 보여주고 실행에 쓸 토큰을 발급한다.
 *
 * <p>Wiki 복구는 본문을 읽지 않고 페이지 유형과 기여 명단으로 판단한다. 문서 편집 복구는 canonical 편집
 * 상태의 revision을 확인하고, 필요한 경우 기존 원본에서 편집 상태를 초기화한다.
 */
@Service
public class RestorePreviewService {

    private final OperationLogRepository operationLogRepository;
    private final WikiPageContributionRepository contributionRepository;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final RestoreScopeResolver scopeResolver;
    private final RestorePlanner planner;
    private final PreviewTokenSigner tokenSigner;
    private final RestoreTargetValidator validator;
    private final DocumentRestorePlanner documentPlanner;
    private final LintRestorePlanner lintRestorePlanner;

    public RestorePreviewService(OperationLogRepository operationLogRepository,
                                 WikiPageContributionRepository contributionRepository,
                                 WorkspaceAccessGuard workspaceAccessGuard,
                                 RestoreScopeResolver scopeResolver,
                                 RestorePlanner planner,
                                 PreviewTokenSigner tokenSigner,
                                 DocumentRestorePlanner documentPlanner,
                                 LintRestorePlanner lintRestorePlanner,
                                 RestoreTargetValidator validator) {
        this.operationLogRepository = operationLogRepository;
        this.contributionRepository = contributionRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.scopeResolver = scopeResolver;
        this.planner = planner;
        this.tokenSigner = tokenSigner;
        this.documentPlanner = documentPlanner;
        this.lintRestorePlanner = lintRestorePlanner;
        this.validator = validator;
    }

    /**
     * 되돌리면 무엇이 바뀌는지 계산한다.
     *
     * <p>실행과 <b>같은 검증</b>을 거친다. 여기서 통과한 것이 실행에서 거절되면 사용자가 확인
     * 화면을 다 보고 나서 실패한다.
     */
    @Transactional
    public RestorePreviewResponse preview(String workspaceId, String userId, String operationId) {
        OperationLog target = loadOperation(workspaceId, userId, operationId);
        validator.requireRestorable(target);

        // 문서 편집은 되돌릴 편집 revision이 변경내역에 이미 적혀 있어 계산할 것이 없다.
        if (target.getOperationType() == OperationType.document_edit) {
            DocumentRestorePlan plan = documentPlanner.plan(target);
            return RestorePreviewResponse.from(operationId, plan, tokenSigner.sign(operationId, plan));
        }

        if (target.getOperationType() == OperationType.lint) {
            LintRestorePlanner.Context lintPlan = lintRestorePlanner.plan(target);
            validator.requireApplicable(target, lintPlan.plan());
            return RestorePreviewResponse.from(operationId, lintPlan.plan(),
                    tokenSigner.sign(operationId, lintPlan.contributions()));
        }

        Set<String> excluded = scopeResolver.resolve(target);
        Map<String, List<WikiPageContribution>> contributions = loadContributions(excluded);
        RestorePlan plan = planner.plan(excluded, contributions, workspaceId);
        validator.requireApplicable(target, plan);

        return RestorePreviewResponse.from(operationId, plan,
                tokenSigner.sign(operationId, contributions));
    }

    /**
     * 제외 대상이 건드린 페이지의 <b>전체</b> 기여를 페이지별로 모은다.
     * 판정과 토큰 서명이 같은 값을 봐야 하므로 한 번만 읽어 둘 다에 넘긴다.
     */
    Map<String, List<WikiPageContribution>> loadContributions(Set<String> excludedOperationIds) {
        if (excludedOperationIds.isEmpty()) {
            return Map.of();
        }
        List<String> pageIds =
                contributionRepository.findActivePageIdsByOperationIds(excludedOperationIds);
        if (pageIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<WikiPageContribution>> byPage = new LinkedHashMap<>();
        for (WikiPageContribution c : contributionRepository.findByPageIds(pageIds)) {
            byPage.computeIfAbsent(c.getPageId(), key -> new java.util.ArrayList<>()).add(c);
        }
        return byPage;
    }

    OperationLog loadOperation(String workspaceId, String userId, String operationId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        return operationLogRepository.findByOperationIdAndWorkspaceId(operationId, workspaceId)
                .orElseThrow(() -> new OperationNotFoundException(operationId));
    }
}
