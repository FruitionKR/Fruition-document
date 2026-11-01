package fruition.core.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 재편입이 이전 편입 기여를 대체해 끈 개념 페이지를 ai에 남은 활성 기여로 다시 만들게 한다.
 *
 * <p>ai는 편입할 때 원장의 활성 기여를 모르므로 그 문서 근거가 빠진 페이지를 스스로 고치지 않는다. 재편입이 다시 쓴
 * 페이지도 대상이다. ai는 그 페이지를 현재 상태 위에 새 편입을 더해 쓰므로, 재편입이 더는 뽑지 않은 이전 편입의
 * 하위 항목·근거가 남는다. 소스 페이지는 재편입이 문서 전체로 다시 쓰고 재조립 대상이 아니라 뺀다. 복구 실행과
 * 같은 경로를 쓴다. 대상 작업이 없는 {@code restore} 작업 로그(restored_from 없음)를 만들고
 * {@code kind: restore_rebuild} 명령을 보낸다. 결과는 다른 복구처럼 받아 새 버전으로 기록하고, 원장은
 * 바꾸지 않는다(제외 작업이 없다).
 */
@Component
public class ReingestRebuildStarter {

    static final String KIND = "restore_rebuild";

    private final WikiPageContributionRepository contributionRepository;
    private final OperationLogRepository operationLogRepository;
    private final AiCommandOutboxWriter outboxWriter;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final String commandTopic;

    public ReingestRebuildStarter(WikiPageContributionRepository contributionRepository,
                                  OperationLogRepository operationLogRepository,
                                  AiCommandOutboxWriter outboxWriter,
                                  JdbcTemplate jdbcTemplate,
                                  ObjectMapper objectMapper,
                                  @Value("${app.maintenance.command-topic}") String commandTopic) {
        this.contributionRepository = contributionRepository;
        this.operationLogRepository = operationLogRepository;
        this.outboxWriter = outboxWriter;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.commandTopic = commandTopic;
    }

    /** 결과 반영 트랜잭션 안에서 부른다. 다시 만들 페이지가 없으면 아무것도 하지 않는다. */
    public void start(OperationLog reingest, Collection<String> sourcePageIds, Instant now) {
        List<String> pageIds = contributionRepository.findPageIdsDeactivatedBy(reingest.getOperationId()).stream()
                .filter(pageId -> !sourcePageIds.contains(pageId))
                .sorted()
                .toList();
        if (pageIds.isEmpty()) {
            return;
        }
        Map<String, List<WikiPageContribution>> rows = new LinkedHashMap<>();
        pageIds.forEach(pageId -> rows.put(pageId, new ArrayList<>()));
        contributionRepository.findByPageIds(pageIds)
                .forEach(row -> rows.get(row.getPageId()).add(row));
        List<PageRestorePlan> pages = new ArrayList<>();
        rows.forEach((pageId, pageRows) -> {
            List<PageRestorePlan.Kept> kept = pageRows.stream()
                    .filter(WikiPageContribution::isActive)
                    .sorted(Comparator.comparingLong(WikiPageContribution::getSequenceRevision))
                    .map(row -> new PageRestorePlan.Kept(
                            row.getIngestOperationId(), row.getSourceDocumentId(), row.getObjectKey()))
                    .toList();
            pages.add(kept.isEmpty() ? PageRestorePlan.delete(pageId) : PageRestorePlan.rebuild(pageId, kept));
        });
        RestorePlan plan = new RestorePlan(pages);
        Map<String, List<String>> expected = RestoreExecuteService.contributionSignatures(rows);

        // 이 결과 반영 트랜잭션은 원래 편입 실행에 묶여 있다. 새 실행을 등록하는 동안만 바꾸고 되돌린다.
        String previousRun = jdbcTemplate.queryForObject(
                "SELECT current_setting('app.ai_task_run_id', true)", String.class);
        String runId = UUID.randomUUID().toString();
        String restoreId = "op_" + UUID.randomUUID().toString().replace("-", "");
        outboxWriter.begin(runId, reingest.getWorkspaceId(), reingest.getUserId(), KIND);
        operationLogRepository.insertRestoreIfAbsent(restoreId, reingest.getWorkspaceId(), reingest.getUserId(),
                reingest.getTargetDocumentId(), null,
                json(new RestoreExecuteService.RestoreManifest(plan, Set.of(), expected)), null, now);
        outboxWriter.enqueue(runId, commandTopic, reingest.getWorkspaceId(), new RestoreExecuteService.RestoreCommand(
                runId, KIND, reingest.getWorkspaceId(), reingest.getUserId(), restoreId,
                reingest.getOperationId(), null, null, null,
                plan.byAction(fruition.core.aihistory.domain.RestoreAction.rebuild).stream()
                        .map(RestoreExecuteService.RebuildPage::from).toList(),
                plan.byAction(fruition.core.aihistory.domain.RestoreAction.delete).stream()
                        .map(PageRestorePlan::pageId).toList(),
                expected));
        jdbcTemplate.queryForObject("SELECT set_config('app.ai_task_run_id', ?, true)", String.class,
                previousRun == null ? "" : previousRun);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("재편입 재조립 지시서를 직렬화하지 못했습니다.", e);
        }
    }
}
