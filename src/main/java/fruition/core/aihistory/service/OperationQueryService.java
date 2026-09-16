package fruition.core.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.dto.OperationLogDetailResponse;
import fruition.core.aihistory.dto.OperationLogListResponse;
import fruition.core.aihistory.exception.InvalidRestoreRequestException;
import fruition.core.aihistory.exception.OperationNotFoundException;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 작업 로그 조회. ingest·lint 상세는 생성·삭제된 Wiki 제목과 유형만 반환한다.
 */
@Service
public class OperationQueryService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    /** 첫 페이지용 기본 커서. null을 넘기면 Postgres가 파라미터 타입을 추론하지 못한다. */
    private static final Instant NO_CURSOR = Instant.parse("9999-12-31T23:59:59Z");

    /** 커서 문자열의 두 키를 가르는 문자. Instant 표기와 operation_id(base64url) 어디에도 없다. */
    private static final String CURSOR_SEPARATOR = ",";

    /**
     * status를 생략한 조회에서만 감추는 상태. 진행 중인 작업은 아직 결과가 없고, 반영에 실패한
     * 작업은 되돌릴 대상이 없어 사용자가 목록에서 할 수 있는 일이 없다.
     *
     * <p>명시 조회는 그대로 통과시킨다. {@code status=processing}은 활성 작업 탐지에,
     * {@code status=failed}·{@code status=conflict}는 실패 알림 감지에 쓴다.
     */
    private static final Set<OperationStatus> HIDDEN_BY_DEFAULT_STATUSES =
            Set.of(OperationStatus.processing, OperationStatus.applying,
                    OperationStatus.notify_pending, OperationStatus.rebuilding,
                    OperationStatus.failed, OperationStatus.conflict);

    private final OperationLogRepository operationLogRepository;
    private final OperationChangeRepository operationChangeRepository;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final ChangeDiffLoader diffLoader;
    private final ObjectMapper objectMapper;
    private final PipelineWikiStateRequester wikiStateRequester;

    public OperationQueryService(OperationLogRepository operationLogRepository,
                                 OperationChangeRepository operationChangeRepository,
                                 WorkspaceAccessGuard workspaceAccessGuard,
                                 ChangeDiffLoader diffLoader,
                                 ObjectMapper objectMapper,
                                 PipelineWikiStateRequester wikiStateRequester) {
        this.operationLogRepository = operationLogRepository;
        this.operationChangeRepository = operationChangeRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.diffLoader = diffLoader;
        this.objectMapper = objectMapper;
        this.wikiStateRequester = wikiStateRequester;
    }

    @Transactional(readOnly = true)
    public OperationLogListResponse list(String workspaceId, String userId,
                                         String type, String status, String cursor, Integer size) {
        verifyMember(workspaceId, userId);

        int limit = normalizeSize(size);
        Cursor parsed = parseCursor(cursor);
        List<OperationLog> found = operationLogRepository.findPage(
                workspaceId, parseType(type), parseStatus(status), parsed.createdAt(), parsed.operationId(),
                OperationStatus.succeeded, OperationType.document_edit, HIDDEN_BY_DEFAULT_STATUSES,
                PageRequest.of(0, limit + 1));

        // 한 건 더 읽어 다음 페이지가 있는지 본다.
        boolean hasMore = found.size() > limit;
        List<OperationLog> page = hasMore ? found.subList(0, limit) : found;
        String nextCursor = hasMore ? encodeCursor(page.get(page.size() - 1)) : null;

        return new OperationLogListResponse(
                page.stream().map(OperationLogListResponse.Item::from).toList(), nextCursor);
    }

    @Transactional(readOnly = true)
    public OperationLogDetailResponse detail(String workspaceId, String userId, String operationId) {
        verifyMember(workspaceId, userId);
        OperationLog log = operationLogRepository
                .findByOperationIdAndWorkspaceId(operationId, workspaceId)
                .orElseThrow(() -> new OperationNotFoundException(operationId));
        List<OperationChange> found = operationChangeRepository.findByOperationIdOrderByIdAsc(operationId);
        if (log.getOperationType() == OperationType.ingest || log.getOperationType() == OperationType.lint) {
            List<OperationChange> pages = found.stream()
                    .filter(change -> change.getResourceType() == ResourceType.wiki_page)
                    .filter(change -> change.getChangeType() == ChangeType.created
                            || change.getChangeType() == ChangeType.deleted)
                    .toList();
            List<String> pageIds = pages.stream().map(OperationChange::getResourceId).distinct().toList();
            Map<String, String> pageTypes = pageIds.isEmpty() ? Map.of()
                    : wikiStateRequester.lookup(pageIds, workspaceId).stream()
                            .collect(Collectors.toMap(PipelineWikiStateRequester.WikiPageSnapshot::id,
                                    PipelineWikiStateRequester.WikiPageSnapshot::pageType));
            return OperationLogDetailResponse.from(log, pages.stream()
                    .map(change -> OperationLogDetailResponse.Change.wikiTitle(
                            change, pageTypes.get(change.getResourceId())))
                    .toList(), objectMapper);
        }
        List<ChangeDiffLoader.Diff> diffs = diffLoader.load(found);

        List<OperationLogDetailResponse.Change> changes = new ArrayList<>(found.size());
        for (int i = 0; i < found.size(); i++) {
            changes.add(OperationLogDetailResponse.Change.from(found.get(i), diffs.get(i)));
        }
        return OperationLogDetailResponse.from(log, changes, objectMapper);
    }

    private void verifyMember(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
    }

    private int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    private OperationType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return OperationType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new InvalidRestoreRequestException("알 수 없는 작업 유형입니다: " + type);
        }
    }

    private OperationStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return OperationStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new InvalidRestoreRequestException("알 수 없는 상태입니다: " + status);
        }
    }

    /** 마지막으로 받은 항목을 가리키는 복합 커서. 같은 시각의 작업도 이 두 키로 갈린다. */
    private record Cursor(Instant createdAt, String operationId) {}

    private String encodeCursor(OperationLog last) {
        return last.getCreatedAt() + CURSOR_SEPARATOR + last.getOperationId();
    }

    /** 커서는 마지막으로 받은 항목의 {@code created_at,operation_id}다. */
    private Cursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            // 먼 미래 시각이 모든 행보다 크므로 operation_id는 비교에 쓰이지 않는다.
            return new Cursor(NO_CURSOR, "");
        }
        int separator = cursor.indexOf(CURSOR_SEPARATOR);
        if (separator < 0 || separator == cursor.length() - 1) {
            throw new InvalidRestoreRequestException("커서 형식이 올바르지 않습니다: " + cursor);
        }
        try {
            return new Cursor(Instant.parse(cursor.substring(0, separator)),
                    cursor.substring(separator + 1));
        } catch (DateTimeParseException e) {
            throw new InvalidRestoreRequestException("커서 형식이 올바르지 않습니다: " + cursor);
        }
    }
}
