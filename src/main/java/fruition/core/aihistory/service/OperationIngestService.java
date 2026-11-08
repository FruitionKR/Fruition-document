package fruition.core.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.dto.OperationResultRequest;
import fruition.core.aihistory.dto.OperationResultResponse;
import fruition.core.aihistory.exception.InvalidCallbackPayloadException;
import fruition.core.aihistory.exception.OperationNotFoundException;
import fruition.core.aihistory.exception.OperationPayloadConflictException;
import fruition.core.aihistory.repository.OperationLogRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * llmPipeline이 보낸 ingest 결과와 복구 재조립 결과를 받는다. 엔드포인트는 하나다.
 *
 * <pre>
 * ① 멱등     payload_hash 비교 → 같으면 기존 결과, 다르면 409
 * ② 정합성   등록값과 콜백값 일치 확인
 * ③ 읽기     [트랜잭션 밖] key 검증 후 본문을 읽고 hash 대조
 * ④ 적재     {@link OperationApplier}가 한 트랜잭션으로 처리
 * </pre>
 *
 * <p>③을 트랜잭션 밖에 두는 이유는 페이지 여러 개를 저장소에서 읽는 동안 DB 커넥션을
 * 붙잡지 않기 위해서다.
 *
 * <p>작업이 {@code restore}면 ④ 대신 {@link RestoreRebuildApplier}로 간다. 재조립은 기여를
 * 만들지 않고 복구가 보관해 둔 지시서의 목표 기여 수를 그대로 쓴다.
 */
@Service
public class OperationIngestService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OperationIngestService.class);

    private final OperationLogRepository operationLogRepository;
    private final WikiObjectReader objectReader;
    private final OperationApplier applier;
    private final LintOperationApplier lintApplier;
    private final RestoreRebuildApplier rebuildApplier;
    private final ObjectMapper objectMapper;

    public OperationIngestService(OperationLogRepository operationLogRepository,
                                  WikiObjectReader objectReader,
                                  OperationApplier applier,
                                  LintOperationApplier lintApplier,
                                  RestoreRebuildApplier rebuildApplier,
                                  ObjectMapper objectMapper) {
        this.operationLogRepository = operationLogRepository;
        this.objectReader = objectReader;
        this.applier = applier;
        this.lintApplier = lintApplier;
        this.rebuildApplier = rebuildApplier;
        this.objectMapper = objectMapper;
    }

    public OperationResultResponse accept(String operationId, OperationResultRequest request) {
        if (!operationId.equals(request.operationId())) {
            throw new InvalidCallbackPayloadException(
                    "경로와 본문의 operation_id가 다릅니다: " + operationId);
        }

        OperationLog operation = operationLogRepository.findByIdForUpdate(operationId)
                .orElseThrow(() -> new OperationNotFoundException(operationId));
        String payloadHash = payloadHash(request);

        // 이미 확정된 작업이면 같은 payload인지만 본다. 같으면 재전송이므로 기존 결과를 돌려준다.
        if (operation.getStatus().isTerminal()) {
            if (payloadHash.equals(operation.getPayloadHash())) {
                return new OperationResultResponse(operationId, operation.getStatus().name(),
                        operation.getChangedResourceCount());
            }
            throw new OperationPayloadConflictException(operationId);
        }

        verifyMatchesRegistration(operation, request);

        if (operation.getOperationType() == OperationType.restore) {
            // 되돌리기의 최종 상태는 AiTaskResultApplier.applyRestore가 대상 작업 ID와 함께 남긴다.
            return acceptRebuild(operation, request, payloadHash);
        }
        if (operation.getOperationType() == OperationType.lint) {
            List<LintOperationApplier.LoadedPage> loaded = request.changedPages().stream()
                    .map(page -> loadLint(operation, page))
                    .toList();
            List<OperationResultRequest.LedgerContribution> ledger = request.ledgerContributionsOrEmpty().stream()
                    .map(row -> new OperationResultRequest.LedgerContribution(
                            row.pageId(), row.operationId(), row.documentId(),
                            objectReader.validateContributionKey(row.contributionKey(),
                                    operation.getWorkspaceId(), row.pageId(), row.operationId()),
                            row.linkedByOperationId()))
                    .toList();
            return logged(operation, lintApplier.apply(operationId, request, loaded, ledger, payloadHash, Instant.now()));
        }

        List<OperationApplier.LoadedPage> loaded = request.changedPages().stream()
                .map(page -> load(operation, page))
                .toList();
        List<OperationApplier.DeletedPage> deleted = request.deletedPagesOrEmpty().stream()
                .distinct()
                .map(pageId -> new OperationApplier.DeletedPage(pageId,
                        objectReader.contributionKey(operation.getWorkspaceId(), pageId, operation.getOperationId())))
                .toList();

        return logged(operation, applier.apply(operationId, request, loaded, deleted, payloadHash, Instant.now()));
    }

    /** 재전송은 위에서 기존 결과로 돌아가므로 여기에는 처음 확정한 결과만 온다. 호출 트랜잭션의 커밋 뒤에 남긴다. */
    private OperationResultResponse logged(OperationLog operation, OperationResultResponse result) {
        Object[] fields = {operation.getOperationType().logLabel(), operation.getOperationId(), operation.getOperationType(), result.status(),
                result.recordedChanges(), operation.getWorkspaceId(), operation.getTargetDocumentId()};
        // 편입 성공은 문서 상태까지 닫는 [위키 편입 완료] 한 줄로 충분하다.
        if ("succeeded".equals(result.status()) && operation.getOperationType() != OperationType.ingest) {
            fruition.shared.logging.CommittedLog.info(log, "[{} 페이지 기록 완료] operation=wiki_operation stage=completed operationId={} type={} status={} changedResources={} workspaceId={} documentId={}", fields);
        } else if ("partially_succeeded".equals(result.status())) {
            fruition.shared.logging.CommittedLog.warn(log, "[{} 페이지 일부 기록] operation=wiki_operation stage=partial partial=true operationId={} type={} status={} changedResources={} workspaceId={} documentId={} 원인=일부 페이지만 반영했습니다. 반영된 페이지는 작업 기록에 남았습니다.", fields);
        }
        return result;
    }

    private LintOperationApplier.LoadedPage loadLint(
            OperationLog operation,
            OperationResultRequest.ChangedPage page) {
        String markdown = objectReader.read(page.markdownKey(), operation.getWorkspaceId(),
                page.pageId(), operation.getOperationId());
        String actualHash = objectReader.sha256(markdown);
        if (!actualHash.equals(page.contentHash())) {
            throw new InvalidCallbackPayloadException(
                    "본문 해시가 일치하지 않습니다: pageId=" + page.pageId());
        }
        return new LintOperationApplier.LoadedPage(
                page.pageId(), page.markdownKey(), markdown, actualHash);
    }

    /** 복구의 {@code rebuilding} 단계를 끝낸다. */
    private OperationResultResponse acceptRebuild(OperationLog operation,
                                                  OperationResultRequest request,
                                                  String payloadHash) {
        if (operation.getStatus() != OperationStatus.rebuilding
                && operation.getStatus() != OperationStatus.notify_pending) {
            throw new InvalidCallbackPayloadException(
                    "재조립을 기다리는 작업이 아닙니다: operationId=" + operation.getOperationId()
                            + " status=" + operation.getStatus());
        }

        List<RestoreRebuildApplier.RebuiltPage> loaded = request.changedPages().stream()
                .map(page -> loadRebuilt(operation, page))
                .toList();

        return rebuildApplier.apply(operation.getOperationId(), request, loaded,
                payloadHash, Instant.now());
    }

    /** 재조립은 기여 조각을 새로 만들지 않으므로 본문만 읽어 온다. */
    private RestoreRebuildApplier.RebuiltPage loadRebuilt(OperationLog operation,
                                                          OperationResultRequest.ChangedPage page) {
        String markdown = objectReader.read(page.markdownKey(), operation.getWorkspaceId(),
                page.pageId(), operation.getOperationId());
        String actualHash = objectReader.sha256(markdown);
        if (!actualHash.equals(page.contentHash())) {
            throw new InvalidCallbackPayloadException(
                    "본문 해시가 일치하지 않습니다: pageId=" + page.pageId());
        }
        return new RestoreRebuildApplier.RebuiltPage(
                page.pageId(), page.markdownKey(), markdown, actualHash);
    }

    private OperationApplier.LoadedPage load(OperationLog operation,
                                             OperationResultRequest.ChangedPage page) {
        String markdown = objectReader.read(page.markdownKey(), operation.getWorkspaceId(),
                page.pageId(), operation.getOperationId());
        String actualHash = objectReader.sha256(markdown);
        if (!actualHash.equals(page.contentHash())) {
            throw new InvalidCallbackPayloadException(
                    "본문 해시가 일치하지 않습니다: pageId=" + page.pageId());
        }
        String contributionKey = page.contributionKey() != null
                ? objectReader.validateContributionKey(page.contributionKey(),
                        operation.getWorkspaceId(), page.pageId(), operation.getOperationId())
                : objectReader.contributionKey(operation.getWorkspaceId(), page.pageId(),
                        operation.getOperationId());
        return new OperationApplier.LoadedPage(
                page.pageId(), page.markdownKey(), contributionKey, markdown, actualHash);
    }

    /** 콜백이 보낸 값은 권한 근거가 아니라 대조용이다. 생략하면 대조하지 않는다. */
    private void verifyMatchesRegistration(OperationLog operation, OperationResultRequest request) {
        if (mismatched(operation.getWorkspaceId(), request.workspaceId())
                || mismatched(operation.getUserId(), request.userId())
                || mismatched(operation.getTargetDocumentId(), request.targetDocumentId())) {
            throw new InvalidCallbackPayloadException(
                    "요청 등록 정보와 결과가 일치하지 않습니다: operationId=" + operation.getOperationId());
        }
    }

    private boolean mismatched(String registered, String reported) {
        return reported != null && !reported.equals(registered);
    }

    /** 같은 결과가 다시 오면 같은 값이 나와야 한다. */
    public String payloadHash(OperationResultRequest request) {
        try {
            String canonical = objectMapper.writeValueAsString(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("payload_hash를 계산하지 못했습니다.", e);
        }
    }
}
