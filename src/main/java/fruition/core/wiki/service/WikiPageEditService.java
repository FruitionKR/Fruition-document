package fruition.core.wiki.service;

import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.aihistory.service.LineCounter;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspacePermissions;
import fruition.core.document.exception.InvalidMarkdownContentException;
import fruition.core.document.exception.MarkdownContentTooLargeException;
import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.domain.WikiPageVersion;
import fruition.core.wiki.dto.WikiPageContentSaveRequest;
import fruition.core.wiki.dto.WikiPageContentSaveResponse;
import fruition.core.wiki.dto.WikiPageDetailResponse;
import fruition.core.wiki.exception.PipelineWikiPageException;
import fruition.core.wiki.exception.WikiPageNotFoundException;
import fruition.core.wiki.exception.WikiPageVersionConflictException;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import fruition.core.wiki.repository.WikiWorkspaceVersionRepository;
import fruition.shared.idempotency.IdempotencyConflictException;
import fruition.shared.idempotency.InvalidIdempotencyKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * 사람이 Wiki 페이지 본문을 고친다(#75). 본문 저장은 ai가 수동 기여로 하고(ai ADR 0031),
 * revision 채번·충돌 검사·기여·버전·작업 로그 기록은 document가 한다.
 */
@Service
public class WikiPageEditService {

    private static final int MAX_MARKDOWN_BYTES = 5 * 1024 * 1024;
    private static final String SUMMARY = "Wiki 페이지 본문을 직접 수정했습니다.";

    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final PipelineWikiStateRequester wikiStateRequester;
    private final WikiPageVersionRepository versionRepository;
    private final WikiPageContributionRepository contributionRepository;
    private final OperationLogRepository operationLogRepository;
    private final OperationChangeRepository operationChangeRepository;
    private final WikiWorkspaceVersionRepository workspaceVersionRepository;
    private final LineCounter lineCounter;

    public WikiPageEditService(WorkspaceAccessGuard workspaceAccessGuard,
                               PipelineWikiStateRequester wikiStateRequester,
                               WikiPageVersionRepository versionRepository,
                               WikiPageContributionRepository contributionRepository,
                               OperationLogRepository operationLogRepository,
                               OperationChangeRepository operationChangeRepository,
                               WikiWorkspaceVersionRepository workspaceVersionRepository,
                               LineCounter lineCounter) {
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.wikiStateRequester = wikiStateRequester;
        this.versionRepository = versionRepository;
        this.contributionRepository = contributionRepository;
        this.operationLogRepository = operationLogRepository;
        this.operationChangeRepository = operationChangeRepository;
        this.workspaceVersionRepository = workspaceVersionRepository;
        this.lineCounter = lineCounter;
    }

    /**
     * 페이지 잠금을 쥔 채 ai를 동기 호출한다. ingest·lint·복구 결과 반영도 같은 잠금으로 revision을 채번하므로
     * 그 사이에 다른 revision이 끼지 않는다.
     */
    @Transactional
    public WikiPageContentSaveResponse save(String workspaceId, String userId, String pageId,
                                            WikiPageContentSaveRequest request) {
        workspaceAccessGuard.requirePermission(workspaceId, userId, WorkspacePermissions.WIKI_INGEST);
        String markdown = validate(request);
        long baseRevision = request.baseRevision();
        String operationId = operationId(workspaceId, pageId, request.revisionWriteId());

        versionRepository.lockPage(pageId);
        Optional<WikiPageVersion> written = versionRepository.findFirstByIdPageIdAndOperationId(pageId, operationId);
        if (written.isPresent()) {
            return replay(written.get(), baseRevision, markdown);
        }
        Optional<WikiPageVersion> previous = versionRepository.findTopByIdPageIdOrderByIdRevisionDesc(pageId);
        long current = previous.map(WikiPageVersion::getRevision).orElse(0L);

        // ai의 현재 본문을 먼저 본다. ai가 이 작업을 이미 저장했는지(기록 누락 재시도)와,
        // ai에서 끝났지만 아직 반영되지 않은 결과가 있는지(revision 순서 역전)를 가른다.
        WikiPageDetailResponse page = wikiStateRequester.page(workspaceId, pageId)
                .orElseThrow(() -> new WikiPageNotFoundException(pageId));
        if (aiHoldsEdit(page, workspaceId, pageId, operationId)) {
            // ai 저장은 끝났는데 document 기록만 빠진 재시도다. ai의 현재 본문이 이 작업 본문이므로
            // 그 뒤 다른 결과가 끼지 않았다. base_revision을 다시 묻지 않고 현재 revision 다음에 기록한다.
            if (page.markdown() != null && !page.markdown().equals(markdown)) {
                throw new IdempotencyConflictException("같은 revision_write_id를 다른 저장 요청에 사용할 수 없습니다.");
            }
        } else {
            if (current != baseRevision) {
                throw new WikiPageVersionConflictException(current);
            }
            // 문서 본문 저장과 같이 같은 본문은 revision을 올리지 않는다.
            if (previous.isPresent() && previous.get().getMarkdown().equals(markdown)) {
                return response(previous.get());
            }
            // 최신 버전 본문은 ai가 결과로 준 객체를 읽어 남긴 것이라 ai의 현재 본문과 같다. 다르면 ai에서 끝난
            // 작업 결과가 아직 반영되지 않은 것이다. 지금 저장하면 그 결과가 나중에 더 높은 revision으로 기록돼
            // 순서가 뒤집히므로 거절한다. 반영이 끝나면 새 revision으로 다시 시도한다.
            if (previous.isPresent() && hasText(page.markdown())
                    && !page.markdown().equals(previous.get().getMarkdown())) {
                throw new WikiPageVersionConflictException(current);
            }
        }

        var result = wikiStateRequester.manualEdit(workspaceId, userId, pageId, operationId, markdown)
                .orElseThrow(() -> new WikiPageNotFoundException(pageId));
        if (!pageId.equals(result.pageId())) {
            throw new PipelineWikiPageException("Wiki 본문을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.", 503, null);
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long revision = current + 1;
        // 기여·버전이 작업 로그를 참조하므로 먼저 넣는다. ai가 기여를 워크스페이스로 찾을 때도 이 행을 조인한다.
        operationLogRepository.saveAndFlush(OperationLog.completed(
                operationId, workspaceId, userId, OperationType.wiki_edit, null, SUMMARY, 1, now));
        // 원문 문서가 없는 기여다. 문서 삭제가 끄는 대상이 아니고, 복구 때 keep_contributions에 남는다.
        contributionRepository.save(new WikiPageContribution(
                pageId, operationId, null, revision, result.contributionKey(), now));
        contributionRepository.flush();
        int contributionCount = (int) contributionRepository.countByIdPageIdAndActiveTrue(pageId);
        WikiPageVersion version = versionRepository.save(new WikiPageVersion(
                pageId, revision, contributionCount, markdown, result.markdownKey(), result.contentHash(),
                operationId, userId, now));

        Long beforeRevision = previous.map(WikiPageVersion::getRevision).orElse(null);
        LineCounter.LineCount lines = lineCounter.count(pageId, beforeRevision,
                previous.map(WikiPageVersion::getMarkdown).orElse(null), revision, markdown);
        operationChangeRepository.save(new OperationChange(
                operationId, ResourceType.wiki_page, pageId, page.title(), beforeRevision, revision,
                ChangeType.updated, null, lines.additions(), lines.deletions()));
        // 위키를 바꿨으므로 wiki/graph의 ETag 기준 버전을 올린다(#68).
        workspaceVersionRepository.bump(workspaceId);
        return response(version);
    }

    /** ai가 이 작업의 본문(`ops/{operation_id}.md`)을 현재 본문으로 가리키면 이미 저장한 작업이다. */
    private static boolean aiHoldsEdit(WikiPageDetailResponse page, String workspaceId, String pageId,
                                       String operationId) {
        String uri = page.markdownUri();
        return uri != null && uri.endsWith("/wiki/" + workspaceId + "/pages/" + pageId + "/ops/" + operationId + ".md");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String validate(WikiPageContentSaveRequest request) {
        if (request.markdown() == null) {
            throw new InvalidMarkdownContentException("Markdown 본문은 null일 수 없습니다.");
        }
        if (request.markdown().getBytes(StandardCharsets.UTF_8).length > MAX_MARKDOWN_BYTES) {
            throw new MarkdownContentTooLargeException("Markdown 본문은 UTF-8 기준 5MB 이하여야 합니다.");
        }
        if (request.baseRevision() == null || request.baseRevision() < 0) {
            throw new InvalidMarkdownContentException("base_revision은 0 이상이어야 합니다.");
        }
        String writeId = request.revisionWriteId();
        if (writeId == null || writeId.isBlank() || writeId.length() > 255) {
            throw new InvalidIdempotencyKeyException("revision_write_id는 1자 이상 255자 이하여야 합니다.");
        }
        return request.markdown();
    }

    /** 같은 write ID 재전송은 처음 결과를 돌려준다. 다른 요청에 같은 write ID를 쓰면 거절한다. */
    private WikiPageContentSaveResponse replay(WikiPageVersion written, long baseRevision, String markdown) {
        if (written.getRevision() != baseRevision + 1 || !written.getMarkdown().equals(markdown)) {
            throw new IdempotencyConflictException("같은 revision_write_id를 다른 저장 요청에 사용할 수 없습니다.");
        }
        return response(written);
    }

    /**
     * write ID에서 작업 ID를 정한다. 저장 뒤 응답을 못 받은 재시도가 같은 작업 ID로 ai에 가므로
     * ai는 같은 수동 기여를 돌려주고, document 기록이 실패했던 경우도 이 재시도로 채워진다.
     */
    private static String operationId(String workspaceId, String pageId, String revisionWriteId) {
        String key = workspaceId + "\n" + pageId + "\n" + revisionWriteId;
        return "op_" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private static WikiPageContentSaveResponse response(WikiPageVersion version) {
        return new WikiPageContentSaveResponse(
                version.getPageId(), version.getRevision(), version.getCreatedAt(), version.getMarkdown());
    }
}
