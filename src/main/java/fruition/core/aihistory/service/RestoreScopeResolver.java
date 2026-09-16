package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.repository.OperationLogRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 기준 작업으로 제외할 작업 집합을 정한다.
 *
 * <p>복구는 <b>"이 작업 되돌리기" 하나</b>다. 지목한 작업과 그 이후 같은 문서의 작업을 전부
 * 걷어내며, 그 과정에서 만들어진 source page와 concept page는 받치는 기여가 사라져 삭제된다.
 * 사용자가 범위를 고르지 않는다.
 *
 * <p>지목한 작업 자신을 포함하는 이유는 로그 목록에서 "이 항목이 한 일을 없앤다"가 가장 흔한
 * 조작이기 때문이다. lint도 같은 규칙이라 두 유형의 결과가 어긋나지 않는다.
 * A2까지 살리고 싶으면 그다음 작업인 A3을 지목하면 된다.
 *
 * <p>ingest만 모은다. lint와 restore는 기여를 만들지 않아 판정에 영향이 없다.
 */
@Component
public class RestoreScopeResolver {

    private final OperationLogRepository operationLogRepository;

    public RestoreScopeResolver(OperationLogRepository operationLogRepository) {
        this.operationLogRepository = operationLogRepository;
    }

    public Set<String> resolve(OperationLog target) {
        // lint는 원문 문서에 속하지 않아 "그 문서의 이후 작업"이라는 범위를 만들 수 없다.
        // 그 작업 하나만 되돌린다.
        if (target.getTargetDocumentId() == null) {
            return Set.of(target.getOperationId());
        }

        Set<String> ids = new LinkedHashSet<>();
        ids.add(target.getOperationId());
        operationLogRepository.findByTargetDocumentAfter(
                        target.getTargetDocumentId(), target.getCreatedAt(),
                        target.getOperationId(), OperationType.ingest)
                .stream()
                .map(OperationLog::getOperationId)
                .forEach(ids::add);
        return ids;
    }
}
