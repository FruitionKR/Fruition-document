package fruition.core.aihistory.repository;

import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OperationChangeRepository extends JpaRepository<OperationChange, Long> {

    /** 로그 상세 화면용. 한 작업이 바꾼 리소스를 기록 순서대로 반환한다. */
    List<OperationChange> findByOperationIdOrderByIdAsc(String operationId);

    /** 재조립 결과가 다시 와도 같은 행을 두 번 만들지 않기 위해 쓴다. */
    boolean existsByOperationIdAndResourceIdAndChangeType(
            String operationId, String resourceId, ChangeType changeType);

    long countByOperationId(String operationId);

    /** 대상 작업 이후 같은 리소스가 다시 변경됐는지 확인한다. */
    boolean existsByResourceIdAndIdGreaterThan(String resourceId, Long id);
}
