package fruition.core.aihistory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 작업 1회가 바꾼 리소스 한 건. 일어난 일의 감사 기록이며 나중에 고치지 않는다.
 *
 * <p>재조립이 끝나도 {@link ChangeType#delegated} 행을 갱신하지 않고
 * {@link ChangeType#rebuilt} 행을 새로 추가한다.
 *
 * <p>diff 본문은 저장하지 않고 줄 수만 남긴다. 전체 본문이 버전 테이블에 있어 조회 시 계산할 수 있다.
 */
@Entity
@Table(name = "ai_operation_changes")
public class OperationChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operation_id", nullable = false)
    private String operationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 20)
    private ResourceType resourceType;

    /** 다형 참조라 FK가 없다. 대상이 삭제돼도 이 기록은 남는다. action이면 복구 조치 식별자다. */
    @Column(name = "resource_id", nullable = false)
    private String resourceId;

    /** 변경 시점의 리소스 표시 이름. 이후 rename/delete와 무관하게 감사 로그에 남는다. */
    @Column(name = "resource_display_name")
    private String resourceDisplayName;

    /** 손대기 직전 버전. NULL이면 새로 만든 것이며 되돌릴 지점이 없다. */
    @Column(name = "before_revision")
    private Long beforeRevision;

    /** 이 작업이 만든 버전. 위임·실패면 NULL이다. */
    @Column(name = "after_revision")
    private Long afterRevision;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private ChangeType changeType;

    /** 리소스별 요약. 재조립 실패 사유도 여기 남긴다. */
    @Column(name = "change_summary", columnDefinition = "TEXT")
    private String changeSummary;

    @Column private Integer additions;

    @Column private Integer deletions;

    protected OperationChange() {}

    public OperationChange(String operationId, ResourceType resourceType, String resourceId,
                           Long beforeRevision, Long afterRevision, ChangeType changeType,
                           String changeSummary, Integer additions, Integer deletions) {
        this(operationId, resourceType, resourceId, null, beforeRevision, afterRevision,
                changeType, changeSummary, additions, deletions);
    }

    public OperationChange(String operationId, ResourceType resourceType, String resourceId,
                           String resourceDisplayName, Long beforeRevision, Long afterRevision,
                           ChangeType changeType, String changeSummary,
                           Integer additions, Integer deletions) {
        this.operationId = operationId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.resourceDisplayName = resourceDisplayName;
        this.beforeRevision = beforeRevision;
        this.afterRevision = afterRevision;
        this.changeType = changeType;
        this.changeSummary = changeSummary;
        this.additions = additions;
        this.deletions = deletions;
    }

    public Long getId() { return id; }
    public String getOperationId() { return operationId; }
    public ResourceType getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getResourceDisplayName() { return resourceDisplayName; }
    public Long getBeforeRevision() { return beforeRevision; }
    public Long getAfterRevision() { return afterRevision; }
    public ChangeType getChangeType() { return changeType; }
    public String getChangeSummary() { return changeSummary; }
    public Integer getAdditions() { return additions; }
    public Integer getDeletions() { return deletions; }
}
