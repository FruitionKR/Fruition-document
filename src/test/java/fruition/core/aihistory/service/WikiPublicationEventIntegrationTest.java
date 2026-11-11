package fruition.core.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.dto.OperationResultRequest;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.dto.WikiPublicationEvent;
import fruition.core.aihistory.exception.InvalidCallbackPayloadException;
import fruition.core.aihistory.exception.OperationNotFoundException;
import fruition.core.aihistory.exception.WikiPublicationConflictException;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.aihistory.repository.WikiPublicationInboxRepository;
import fruition.core.aitask.service.AiTaskResultApplier;
import fruition.core.purge.DataPurgeService;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiWorkspaceVersionRepository;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WikiPublicationEventIntegrationTest {

    @Autowired WikiPublicationEventApplier publications;
    @SpyBean OperationIngestService legacyResults;
    @Autowired AiTaskResultApplier taskResults;
    @SpyBean WikiWorkspaceVersionRepository workspaceVersions;
    @Autowired ObjectMapper objectMapper;
    @Autowired OperationLogRepository operations;
    @Autowired JdbcTemplate jdbc;
    @Autowired MinioClient minio;
    @Autowired StorageProperties storage;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WikiPublicationPurgeFence purgeFence;
    @Autowired DataPurgeService dataPurge;
    @SpyBean WikiPublicationInboxRepository inbox;
    @SpyBean RestoreApplier restoreApplier;
    @MockBean PipelineWikiStateRequester wikiState;

    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().replace("-", "");
        when(wikiState.lookup(any(), anyString())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") List<String> ids = invocation.getArgument(0);
            String workspace = invocation.getArgument(1);
            return ids.stream().map(id -> new PipelineWikiStateRequester.WikiPageSnapshot(
                    id, "concept", "Page " + id, id, workspace, "active")).toList();
        });
    }

    @Test
    void legacyHttpRestoreEnvelopeThenPublicationRecordsReceiptWithoutRepeatingEffects() {
        RestoreFixture fixture = restoreFixture("http-envelope", false);
        var raw = objectMapper.valueToTree(fixture.result);
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw).put("operation_type", "ingest_restore");
        var legacy = objectMapper.convertValue(raw, OperationResultRequest.class);
        new TransactionTemplate(transactionManager).execute(status -> taskResults.applySuccessfulRestore(
                operations.findByIdForUpdate(fixture.operationId).orElseThrow(), legacy));
        String committedHash = legacyResults.payloadHash(legacy);
        long versionsBefore = count("wiki_page_versions", "operation_id", fixture.operationId);
        long changesBefore = count("ai_operation_changes", "operation_id", fixture.operationId);
        var publication = event("http-envelope-event", fixture.publicationId, fixture.operationId,
                fixture.workspaceId, fixture.userId, WikiPublicationEvent.Kind.RESTORE, fixture.result);

        assertThat(publications.apply(publication).disposition())
                .isEqualTo(WikiPublicationInboxRepository.Disposition.ACCEPTED);
        assertThat(publications.apply(publication).duplicate()).isTrue();
        assertThat(count("wiki_publication_inbox", "operation_id", fixture.operationId)).isOne();
        assertThat(count("wiki_page_versions", "operation_id", fixture.operationId)).isEqualTo(versionsBefore);
        assertThat(count("ai_operation_changes", "operation_id", fixture.operationId)).isEqualTo(changesBefore);
        assertThat(jdbc.queryForObject("SELECT payload_hash FROM ai_operation_logs WHERE operation_id = ?",
                String.class, fixture.operationId)).isEqualTo(committedHash);
    }

    @Test
    void goldenLintFixtureMatchesTheExactLegacyEnvelopeAndHash() throws Exception {
        WikiPublicationEvent event;
        try (InputStream fixture = getClass().getResourceAsStream("/fixtures/wiki-publication-lint-v1.json")) {
            event = objectMapper.readValue(fixture, WikiPublicationEvent.class);
        }
        OperationResultRequest expected = new OperationResultRequest(
                "op-lint-fixture-001", "lint", "succeeded", "workspace-fixture-001",
                "user-fixture-001", null, "Wiki lint로 페이지 1개를 변경했습니다.",
                List.of(new OperationResultRequest.ChangedPage(
                        "page-fixture-001", "concept",
                        "wiki/workspace-fixture-001/pages/page-fixture-001/ops/op-lint-fixture-001.md",
                        "wiki/workspace-fixture-001/pages/page-fixture-001/ops/op-lint-fixture-001.json",
                        "sha256:" + "a".repeat(64), false)),
                List.of(), List.of(), null, null,
                List.of(new OperationResultRequest.LedgerContribution(
                        "page-fixture-001", "op-lint-fixture-001", null,
                        "wiki/workspace-fixture-001/pages/page-fixture-001/ops/op-lint-fixture-001.json", null)));
        String canonical;
        try (InputStream fixture = getClass().getResourceAsStream(
                "/fixtures/wiki-publication-lint-result-canonical-v1.json")) {
            canonical = new String(fixture.readAllBytes(), StandardCharsets.UTF_8).strip();
        }

        assertThat(event.result()).isEqualTo(expected);
        assertThat(objectMapper.writeValueAsString(expected)).isEqualTo(canonical);
        assertThat(legacyResults.payloadHash(event.result()))
                .isEqualTo("0f1a3e22db4b09ab4ee73f63a14e3ae8f35aca082af86eb21d3c9f2ba474424c");
        assertThat(event.publishedReferenceHash())
                .isEqualTo("sha256:" + legacyResults.payloadHash(expected));
    }

    @Test
    void duplicateDomainAndEitherArrivalOrderProduceOneExactRevision() throws Exception {
        Fixture eventFirst = lintFixture("event-first");
        var first = publications.apply(fixtureEvent(eventFirst, "event-1"));
        assertThat(first.wikiChanged()).isTrue();
        assertThat(publications.apply(fixtureEvent(eventFirst, "event-1")).duplicate()).isTrue();
        assertThat(publications.apply(fixtureEvent(eventFirst, "event-2")).duplicate()).isTrue();
        assertThat(count("wiki_publication_event_aliases", "operation_id", eventFirst.operationId)).isEqualTo(2);
        Fixture reusedAlias = lintFixture("reused-alias");
        assertThatThrownBy(() -> publications.apply(event(
                "event-2", reusedAlias.publicationId, reusedAlias.operationId,
                reusedAlias.workspaceId, reusedAlias.userId, WikiPublicationEvent.Kind.LINT,
                WikiPublicationEvent.Outcome.PUBLISHED, reusedAlias.result)))
                .isInstanceOf(WikiPublicationConflictException.class);
        assertThat(count("wiki_publication_inbox", "operation_id", reusedAlias.operationId)).isZero();
        inTransaction(() -> legacyResults.accept(eventFirst.operationId, eventFirst.result));

        Fixture legacyFirst = lintFixture("legacy-first");
        inTransaction(() -> legacyResults.accept(legacyFirst.operationId, legacyFirst.result));
        assertThat(publications.apply(fixtureEvent(legacyFirst, "event-3")).wikiChanged()).isTrue();

        assertOneApplied(eventFirst);
        assertOneApplied(legacyFirst);
        assertThat(payloadHash(eventFirst.operationId)).isEqualTo(legacyResults.payloadHash(eventFirst.result));
        assertThat(payloadHash(legacyFirst.operationId)).isEqualTo(legacyResults.payloadHash(legacyFirst.result));
    }

    @Test
    void readOnlyReceiptLookupBindsEveryScopeAndDurablyOwnedAlternateAlias() throws Exception {
        Fixture fixture = lintFixture("receipt-lookup");
        WikiPublicationEvent first = fixtureEvent(fixture, "receipt-event-1");
        WikiPublicationEvent alias = fixtureEvent(fixture, "receipt-event-2");
        publications.apply(first);
        publications.apply(alias);

        var receipt = inbox.findReceipt(
                fixture.workspaceId, fixture.userId, fixture.publicationId,
                fixture.operationId, alias.eventId()).orElseThrow();
        assertThat(receipt.eventId()).isEqualTo(alias.eventId());
        assertThat(receipt.disposition()).isEqualTo(WikiPublicationInboxRepository.Disposition.ACCEPTED);
        assertThat(inbox.findReceipt(
                fixture.workspaceId, id("wrong-user"), fixture.publicationId,
                fixture.operationId, alias.eventId())).isEmpty();
        assertThat(inbox.findReceipt(
                fixture.workspaceId, fixture.userId, fixture.publicationId,
                fixture.operationId, id("unowned-event"))).isEmpty();
    }

    @Test
    void outcomeMustMatchTheCommittedWholeOperationEffect() throws Exception {
        Fixture changed = lintFixture("no-change-with-page");
        assertThatThrownBy(() -> publications.apply(event(
                "no-change-with-page", changed.publicationId, changed.operationId,
                changed.workspaceId, changed.userId, WikiPublicationEvent.Kind.LINT,
                WikiPublicationEvent.Outcome.NO_CHANGE, changed.result)))
                .isInstanceOf(InvalidCallbackPayloadException.class);
        assertThat(count("wiki_page_versions", "operation_id", changed.operationId)).isZero();
        assertThat(count("wiki_publication_inbox", "operation_id", changed.operationId)).isZero();

        Fixture empty = emptyLintFixture("empty-result");
        assertThatThrownBy(() -> publications.apply(event(
                "published-empty", empty.publicationId, empty.operationId,
                empty.workspaceId, empty.userId, WikiPublicationEvent.Kind.LINT,
                WikiPublicationEvent.Outcome.PUBLISHED, empty.result)))
                .isInstanceOf(InvalidCallbackPayloadException.class);
        assertThat(publications.apply(event(
                "no-change-empty", empty.publicationId, empty.operationId,
                empty.workspaceId, empty.userId, WikiPublicationEvent.Kind.LINT,
                WikiPublicationEvent.Outcome.NO_CHANGE, empty.result)).wikiChanged()).isFalse();
        assertThat(workspaceVersions.current(empty.workspaceId)).isZero();

        Fixture partialFixture = lintFixture("partial-with-effect");
        OperationResultRequest partial = new OperationResultRequest(
                partialFixture.operationId, "lint", "partially_succeeded",
                partialFixture.workspaceId, partialFixture.userId, null, "one failed",
                partialFixture.result.changedPages(), List.of(), List.of(), null,
                List.of(new OperationResultRequest.FailedAction(
                        "rebuild", partialFixture.pageId, "assembly_failed")),
                partialFixture.result.ledgerContributions());
        assertThat(publications.apply(event(
                "partial-published", partialFixture.publicationId, partialFixture.operationId,
                partialFixture.workspaceId, partialFixture.userId, WikiPublicationEvent.Kind.LINT,
                WikiPublicationEvent.Outcome.PUBLISHED, partial)).wikiChanged()).isTrue();

        Fixture failedWithEffectFixture = lintFixture("failed-with-effect");
        OperationResultRequest failedWithEffect = new OperationResultRequest(
                failedWithEffectFixture.operationId, "lint", "failed",
                failedWithEffectFixture.workspaceId, failedWithEffectFixture.userId, null, "failed",
                failedWithEffectFixture.result.changedPages(), List.of(), List.of(), null,
                List.of(new OperationResultRequest.FailedAction(
                        "rebuild", failedWithEffectFixture.pageId, "assembly_failed")),
                failedWithEffectFixture.result.ledgerContributions());
        assertThat(publications.apply(event(
                "failed-published", failedWithEffectFixture.publicationId,
                failedWithEffectFixture.operationId, failedWithEffectFixture.workspaceId,
                failedWithEffectFixture.userId, WikiPublicationEvent.Kind.LINT,
                WikiPublicationEvent.Outcome.PUBLISHED, failedWithEffect)).wikiChanged()).isTrue();

        Fixture failedFixture = emptyLintFixture("failed-without-effect");
        OperationResultRequest failed = new OperationResultRequest(
                failedFixture.operationId, "lint", "failed",
                failedFixture.workspaceId, failedFixture.userId, null, "failed",
                List.of(), List.of(), List.of(), null,
                List.of(new OperationResultRequest.FailedAction(
                        "rebuild", failedFixture.pageId, "assembly_failed")), List.of());
        assertThat(publications.apply(event(
                "failed-no-change", failedFixture.publicationId, failedFixture.operationId,
                failedFixture.workspaceId, failedFixture.userId, WikiPublicationEvent.Kind.LINT,
                WikiPublicationEvent.Outcome.NO_CHANGE, failed)).wikiChanged()).isFalse();

        Fixture partialFixtureWithoutEffect = emptyLintFixture("partial-without-effect");
        OperationResultRequest partialWithoutEffect = new OperationResultRequest(
                partialFixtureWithoutEffect.operationId, "lint", "partially_succeeded",
                partialFixtureWithoutEffect.workspaceId, partialFixtureWithoutEffect.userId,
                null, "partial", List.of(), List.of(), List.of(), null,
                List.of(new OperationResultRequest.FailedAction(
                        "rebuild", partialFixtureWithoutEffect.pageId, "assembly_failed")), List.of());
        assertThat(publications.apply(event(
                "partial-no-change", partialFixtureWithoutEffect.publicationId,
                partialFixtureWithoutEffect.operationId, partialFixtureWithoutEffect.workspaceId,
                partialFixtureWithoutEffect.userId, WikiPublicationEvent.Kind.LINT,
                WikiPublicationEvent.Outcome.NO_CHANGE, partialWithoutEffect)).wikiChanged()).isFalse();
    }

    @Test
    void legacyCommittedDeleteLinkAndLedgerArtifactsCountAsWholeOperationEffects() {
        String workspace = id("artifact-ws");
        String user = id("artifact-user");

        String deleteOperation = id("delete-op");
        String deletePage = id("delete-page");
        OperationResultRequest deleted = new OperationResultRequest(
                deleteOperation, "lint", "succeeded", workspace, user, null, "deleted",
                List.of(), List.of(), List.of(deletePage), null, null, List.of());
        registerCompleted(deleteOperation, workspace, user, OperationType.lint, deleted);
        jdbc.update("""
                INSERT INTO ai_operation_changes(operation_id, resource_type, resource_id, change_type)
                VALUES (?, 'wiki_page', ?, 'deleted')
                """, deleteOperation, deletePage);
        assertThat(publications.apply(event(
                "delete-effect", id("delete-publication"), deleteOperation, workspace, user,
                WikiPublicationEvent.Kind.LINT, WikiPublicationEvent.Outcome.PUBLISHED, deleted))
                .wikiChanged()).isTrue();

        String linkOperation = id("link-op");
        OperationResultRequest linked = new OperationResultRequest(
                linkOperation, "restore", "succeeded", workspace, user, null, "linked",
                List.of(), List.of(), List.of(),
                new OperationResultRequest.LinkChanges(
                        List.of(new OperationResultRequest.Link("source", "target", "references")),
                        List.of()), null, List.of());
        registerCompleted(linkOperation, workspace, user, OperationType.restore, linked);
        jdbc.update("""
                INSERT INTO ai_operation_changes(operation_id, resource_type, resource_id, change_type)
                VALUES (?, 'relation_link', ?, 'link_removed')
                """, linkOperation, "source|references|target");
        assertThat(publications.apply(event(
                "link-effect", id("link-publication"), linkOperation, workspace, user,
                WikiPublicationEvent.Kind.RECOVERY, WikiPublicationEvent.Outcome.PUBLISHED, linked))
                .wikiChanged()).isTrue();

        String ledgerOperation = id("ledger-op");
        String ledgerPage = id("ledger-page");
        String contributionKey = key(workspace, ledgerPage, ledgerOperation, ".json");
        OperationResultRequest ledger = new OperationResultRequest(
                ledgerOperation, "lint", "succeeded", workspace, user, null, "ledger",
                List.of(), List.of(), List.of(), null, null,
                List.of(new OperationResultRequest.LedgerContribution(
                        ledgerPage, ledgerOperation, null, contributionKey, null)));
        registerCompleted(ledgerOperation, workspace, user, OperationType.lint, ledger);
        jdbc.update("""
                INSERT INTO wiki_page_contributions(page_id, ingest_operation_id, source_document_id,
                    sequence_revision, object_key, active, created_at)
                VALUES (?, ?, NULL, 1, ?, true, now())
                """, ledgerPage, ledgerOperation, contributionKey);
        assertThat(publications.apply(event(
                "ledger-effect", id("ledger-publication"), ledgerOperation, workspace, user,
                WikiPublicationEvent.Kind.LINT, WikiPublicationEvent.Outcome.PUBLISHED, ledger))
                .wikiChanged()).isTrue();
    }

    @Test
    void ingestKindUsesTheRegisteredIngestPathAndPublishesItsEffect() throws Exception {
        Fixture fixture = ingestFixture("ingest-kind");

        var applied = publications.apply(event(
                "ingest-event", fixture.publicationId, fixture.operationId,
                fixture.workspaceId, fixture.userId, WikiPublicationEvent.Kind.INGEST,
                WikiPublicationEvent.Outcome.PUBLISHED, fixture.result));

        assertThat(applied.disposition())
                .isEqualTo(WikiPublicationInboxRepository.Disposition.ACCEPTED);
        assertOneApplied(fixture);
        assertThat(workspaceVersions.current(fixture.workspaceId)).isOne();
    }

    @Test
    void changedPayloadIdentityKeyAndHashConflictWithoutFalseReceipt() throws Exception {
        Fixture fixture = lintFixture("conflicts");
        publications.apply(fixtureEvent(fixture, "event-1"));

        OperationResultRequest changed = new OperationResultRequest(
                fixture.operationId, "lint", "succeeded", fixture.workspaceId, fixture.userId, null,
                "different summary", fixture.result.changedPages(), List.of(), List.of(), null, null,
                fixture.result.ledgerContributions());
        assertThatThrownBy(() -> publications.apply(event(
                "event-2", fixture.publicationId, fixture.operationId,
                fixture.workspaceId, fixture.userId, WikiPublicationEvent.Kind.LINT, changed)))
                .isInstanceOf(WikiPublicationConflictException.class);

        assertThatThrownBy(() -> publications.apply(event(
                "event-3", fixture.publicationId, fixture.operationId,
                "foreign-workspace", fixture.userId, WikiPublicationEvent.Kind.LINT, fixture.result)))
                .isInstanceOf(InvalidCallbackPayloadException.class);

        Fixture identityCollision = lintFixture("identity-collision");
        assertThatThrownBy(() -> publications.apply(fixtureEvent(identityCollision, "event-1")))
                .isInstanceOf(WikiPublicationConflictException.class);
        assertThatThrownBy(() -> publications.apply(event(
                "event-new", fixture.publicationId, identityCollision.operationId,
                identityCollision.workspaceId, identityCollision.userId,
                WikiPublicationEvent.Kind.LINT, identityCollision.result)))
                .isInstanceOf(WikiPublicationConflictException.class);
        assertThat(count("wiki_publication_inbox", "operation_id", identityCollision.operationId)).isZero();

        Fixture invalid = lintFixture("wrong-key");
        OperationResultRequest wrongKey = new OperationResultRequest(
                invalid.operationId, "lint", "succeeded", invalid.workspaceId, invalid.userId, null,
                "done", List.of(new OperationResultRequest.ChangedPage(
                        invalid.pageId, "concept", "wiki/other/pages/x/ops/y.md",
                        invalid.contributionKey, invalid.result.changedPages().getFirst().contentHash(), false)),
                List.of(), List.of(), null, null, invalid.result.ledgerContributions());
        assertThatThrownBy(() -> publications.apply(event(
                "event-4", invalid.publicationId, invalid.operationId,
                invalid.workspaceId, invalid.userId, WikiPublicationEvent.Kind.LINT, wrongKey)))
                .isInstanceOf(InvalidCallbackPayloadException.class);
        assertThat(count("wiki_publication_inbox", "operation_id", invalid.operationId)).isZero();
        assertThat(count("wiki_page_versions", "operation_id", invalid.operationId)).isZero();
    }

    @Test
    void inboxFailureRollsBackDomainVersionContributionAndOperation() throws Exception {
        Fixture fixture = lintFixture("rollback");
        doThrow(new IllegalStateException("injected inbox failure")).when(inbox)
                .insert(any(), anyString(), anyString(), any(), any());

        assertThatThrownBy(() -> publications.apply(fixtureEvent(fixture, "event-rollback")))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("injected inbox failure");

        assertThat(count("wiki_publication_inbox", "operation_id", fixture.operationId)).isZero();
        assertThat(count("wiki_page_versions", "operation_id", fixture.operationId)).isZero();
        assertThat(count("wiki_page_contributions", "ingest_operation_id", fixture.operationId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_operation_logs WHERE operation_id = ?",
                String.class, fixture.operationId)).isEqualTo("processing");
    }

    @Test
    void concurrentDeliveryCommitsOneInboxAndOneDomainApply() throws Exception {
        Fixture fixture = lintFixture("concurrent");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return publications.apply(fixtureEvent(fixture, "event-concurrent")); });
            var b = pool.submit(() -> { start.await(); return publications.apply(fixtureEvent(fixture, "event-concurrent")); });
            start.countDown();
            List<WikiPublicationEventApplier.ApplyResult> results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertThat(results).filteredOn(WikiPublicationEventApplier.ApplyResult::applied).hasSize(1);
            assertThat(results).filteredOn(WikiPublicationEventApplier.ApplyResult::duplicate).hasSize(1);
        }
        assertOneApplied(fixture);
    }

    @Test
    void concurrentEventAliasCollisionCommitsOnlyOneIdentityAndDomainApply() throws Exception {
        Fixture first = lintFixture("alias-race-first");
        Fixture second = lintFixture("alias-race-second");
        var start = new CountDownLatch(1);
        int succeeded = 0;
        int conflicted = 0;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                start.await();
                return publications.apply(fixtureEvent(first, "shared-alias-race"));
            });
            var b = pool.submit(() -> {
                start.await();
                return publications.apply(fixtureEvent(second, "shared-alias-race"));
            });
            start.countDown();
            for (var future : List.of(a, b)) {
                try {
                    future.get(20, TimeUnit.SECONDS);
                    succeeded++;
                } catch (java.util.concurrent.ExecutionException failure) {
                    assertThat(failure.getCause()).isInstanceOf(WikiPublicationConflictException.class);
                    conflicted++;
                }
            }
        }
        assertThat(succeeded).isOne();
        assertThat(conflicted).isOne();
        assertThat(count("wiki_publication_event_aliases", "event_id", "shared-alias-race-" + suffix)).isOne();
        assertThat(count("wiki_publication_inbox", "operation_id", first.operationId)
                + count("wiki_publication_inbox", "operation_id", second.operationId)).isOne();
        assertThat(count("wiki_page_versions", "operation_id", first.operationId)
                + count("wiki_page_versions", "operation_id", second.operationId)).isOne();
    }

    @Test
    void eitherLegacyPublicationOrderInvalidatesAndBumpFailureRollsBackForRetry() throws Exception {
        Fixture publicationFirst = lintFixture("bump-publication-first");
        publications.apply(fixtureEvent(publicationFirst, "publication-first"));
        inTransaction(() -> legacyResults.accept(publicationFirst.operationId, publicationFirst.result));
        workspaceVersions.bump(publicationFirst.workspaceId);
        assertThat(workspaceVersions.current(publicationFirst.workspaceId)).isEqualTo(2);

        Fixture legacyFirst = lintFixture("bump-legacy-first");
        inTransaction(() -> legacyResults.accept(legacyFirst.operationId, legacyFirst.result));
        publications.apply(fixtureEvent(legacyFirst, "legacy-first"));
        workspaceVersions.bump(legacyFirst.workspaceId);
        assertThat(workspaceVersions.current(legacyFirst.workspaceId)).isEqualTo(2);

        Fixture retry = lintFixture("bump-rollback");
        org.mockito.Mockito.doThrow(new IllegalStateException("injected bump failure"))
                .doCallRealMethod().when(workspaceVersions).bump(retry.workspaceId);
        assertThatThrownBy(() -> publications.apply(fixtureEvent(retry, "bump-failure")))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("injected bump failure");
        assertThat(count("wiki_publication_inbox", "operation_id", retry.operationId)).isZero();
        assertThat(count("wiki_page_versions", "operation_id", retry.operationId)).isZero();
        assertThat(workspaceVersions.current(retry.workspaceId)).isZero();

        assertThat(publications.apply(fixtureEvent(retry, "bump-failure")).wikiChanged()).isTrue();
        assertThat(workspaceVersions.current(retry.workspaceId)).isOne();
    }

    @Test
    void manualEventWaitsForCommittedRegistrationAndValidatesExactLedger() {
        String workspace = id("manual-ws");
        String user = id("manual-user");
        String page = id("manual-page");
        String operation = id("manual-op");
        String publication = id("manual-publication");
        String markdownKey = key(workspace, page, operation, ".md");
        String contributionKey = key(workspace, page, operation, ".json");
        OperationResultRequest result = manualResult(operation, workspace, user, page, markdownKey,
                contributionKey, new WikiObjectReader(minio, storage).sha256("# manual"));
        WikiPublicationEvent event = event("manual-event", publication, operation, workspace, user,
                WikiPublicationEvent.Kind.MANUAL_EDIT, result);

        assertThatThrownBy(() -> publications.apply(event)).isInstanceOf(OperationNotFoundException.class);
        assertThat(count("wiki_publication_inbox", "operation_id", operation)).isZero();

        operations.saveAndFlush(OperationLog.completed(operation, workspace, user,
                OperationType.wiki_edit, null, "Wiki 페이지 본문을 직접 수정했습니다.", 1, Instant.now()));
        jdbc.update("""
                INSERT INTO wiki_page_versions(page_id, revision, contribution_count, markdown, markdown_key,
                    content_hash, operation_id, created_by, created_at)
                VALUES (?, 1, 1, '# manual', ?, ?, ?, ?, now())
                """, page, markdownKey, result.changedPages().getFirst().contentHash(), operation, user);
        jdbc.update("""
                INSERT INTO wiki_page_contributions(page_id, ingest_operation_id, source_document_id,
                    sequence_revision, object_key, active, created_at)
                VALUES (?, ?, NULL, 1, ?, true, now())
                """, page, operation, contributionKey);

        OperationResultRequest wrongSummary = new OperationResultRequest(
                operation, "wiki_edit", "succeeded", workspace, user, null, "different summary",
                result.changedPages(), List.of(), List.of(), null, null, result.ledgerContributions());
        assertThatThrownBy(() -> publications.apply(event(
                "manual-wrong-summary", publication, operation, workspace, user,
                WikiPublicationEvent.Kind.MANUAL_EDIT, wrongSummary)))
                .isInstanceOf(InvalidCallbackPayloadException.class);
        OperationResultRequest wrongLedger = new OperationResultRequest(
                operation, "wiki_edit", "succeeded", workspace, user, null, result.summary(),
                result.changedPages(), List.of(), List.of(), null, null,
                List.of(new OperationResultRequest.LedgerContribution(
                        page, operation, "unexpected-document", contributionKey, null)));
        assertThatThrownBy(() -> publications.apply(event(
                "manual-wrong-ledger", publication, operation, workspace, user,
                WikiPublicationEvent.Kind.MANUAL_EDIT, wrongLedger)))
                .isInstanceOf(InvalidCallbackPayloadException.class);
        jdbc.update("UPDATE wiki_page_versions SET markdown = '# corrupted' WHERE operation_id = ?", operation);
        assertThatThrownBy(() -> publications.apply(event))
                .isInstanceOf(InvalidCallbackPayloadException.class);
        jdbc.update("UPDATE wiki_page_versions SET markdown = '# manual' WHERE operation_id = ?", operation);
        assertThat(count("wiki_publication_inbox", "operation_id", operation)).isZero();

        assertThat(publications.apply(event).wikiChanged()).isTrue();
        assertThat(publications.apply(event).duplicate()).isTrue();
        assertThat(count("wiki_page_versions", "operation_id", operation)).isOne();
        assertThat(count("wiki_page_contributions", "ingest_operation_id", operation)).isOne();

        OperationResultRequest mismatch = manualResult(operation, workspace, user, page,
                markdownKey, contributionKey, "sha256:" + "b".repeat(64));
        assertThatThrownBy(() -> publications.apply(event(
                "manual-event-2", publication, operation, workspace, user,
                WikiPublicationEvent.Kind.MANUAL_EDIT, mismatch)))
                .isInstanceOf(WikiPublicationConflictException.class);
    }

    @Test
    void restorePublicationUsesManifestAndPreservesDeactivationLedger() throws Exception {
        String workspace = id("restore-ws");
        String user = id("restore-user");
        String page = id("restore-page");
        String original = id("original-op");
        String cancelled = id("cancelled-op");
        String restore = id("restore-op");
        operations.saveAndFlush(OperationLog.completed(original, workspace, user, OperationType.ingest,
                null, "original", 1, Instant.now().minusSeconds(30)));
        operations.saveAndFlush(OperationLog.completed(cancelled, workspace, user, OperationType.ingest,
                null, "cancelled", 1, Instant.now().minusSeconds(20)));
        String originalKey = key(workspace, page, original, ".json");
        String cancelledKey = key(workspace, page, cancelled, ".json");
        jdbc.update("""
                INSERT INTO wiki_page_contributions(page_id, ingest_operation_id, sequence_revision, object_key, active, created_at)
                VALUES (?, ?, 1, ?, true, now()), (?, ?, 2, ?, true, now())
                """, page, original, originalKey, page, cancelled, cancelledKey);
        jdbc.update("""
                INSERT INTO wiki_page_versions(page_id, revision, contribution_count, markdown, markdown_key,
                    content_hash, operation_id, created_by, created_at)
                VALUES (?, 1, 1, '# original', ?, ?, ?, ?, now()),
                       (?, 2, 2, '# current', ?, ?, ?, ?, now())
                """, page, key(workspace, page, original, ".md"), "sha256:" + "1".repeat(64), original, user,
                page, key(workspace, page, cancelled, ".md"), "sha256:" + "2".repeat(64), cancelled, user);
        var plan = new RestorePlan(List.of(PageRestorePlan.restore(page, 1, original, 1)));
        var manifest = new RestoreExecuteService.RestoreManifest(
                plan, Set.of(cancelled), Map.of(page, List.of(original + ":1:1", cancelled + ":2:1")));
        OperationLog restoreLog = OperationLog.applying(restore, workspace, user, null, cancelled,
                writeJson(manifest), Instant.now());
        operations.saveAndFlush(restoreLog);
        OperationResultRequest result = new OperationResultRequest(
                restore, "restore", "succeeded", workspace, user, null,
                "restored", List.of(), List.of(), List.of(), null, null, List.of());

        publications.apply(event("restore-event", id("restore-publication"), restore,
                workspace, user, WikiPublicationEvent.Kind.RESTORE, result));

        assertThat(jdbc.queryForMap("""
                SELECT active, deactivated_by FROM wiki_page_contributions
                WHERE page_id = ? AND ingest_operation_id = ?
                """, page, cancelled)).containsEntry("active", false).containsEntry("deactivated_by", restore);
        assertThat(jdbc.queryForMap("""
                SELECT revision, markdown_key, operation_id FROM wiki_page_versions
                WHERE page_id = ? ORDER BY revision DESC LIMIT 1
                """, page)).containsEntry("revision", 3L)
                .containsEntry("markdown_key", key(workspace, page, original, ".md"))
                .containsEntry("operation_id", restore);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_operation_logs WHERE operation_id = ?",
                String.class, restore)).isEqualTo("succeeded");
    }

    @Test
    void staleRestoreHasDurableRejectedDispositionWithoutSuccessOrBump() throws Exception {
        RestoreFixture fixture = restoreFixture("stale", true);
        WikiPublicationEvent event = event("stale-restore", fixture.publicationId, fixture.operationId,
                fixture.workspaceId, fixture.userId, WikiPublicationEvent.Kind.RESTORE,
                WikiPublicationEvent.Outcome.PUBLISHED, fixture.result);

        var rejected = publications.apply(event);
        assertThat(rejected.applied()).isFalse();
        assertThat(rejected.disposition())
                .isEqualTo(WikiPublicationInboxRepository.Disposition.REJECTED_CONFLICT);
        assertThat(rejected.rejectionReason()).isEqualTo("STALE_RESTORE");
        assertThat(workspaceVersions.current(fixture.workspaceId)).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT disposition, rejection_reason FROM wiki_publication_inbox WHERE operation_id = ?
                """, fixture.operationId))
                .containsEntry("disposition", "REJECTED_CONFLICT")
                .containsEntry("rejection_reason", "STALE_RESTORE");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ai_operation_logs WHERE operation_id = ?", String.class,
                fixture.operationId)).isEqualTo("conflict");
        assertThat(inbox.findReceipt(
                fixture.workspaceId, fixture.userId, fixture.publicationId,
                fixture.operationId, event.eventId()).orElseThrow().disposition())
                .isEqualTo(WikiPublicationInboxRepository.Disposition.REJECTED_CONFLICT);

        var replay = publications.apply(event);
        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.disposition())
                .isEqualTo(WikiPublicationInboxRepository.Disposition.REJECTED_CONFLICT);
        OperationResultRequest changed = new OperationResultRequest(
                fixture.operationId, "restore", "succeeded", fixture.workspaceId, fixture.userId,
                null, "changed", List.of(), List.of(), List.of(), null, null, List.of());
        assertThatThrownBy(() -> publications.apply(event(
                "stale-restore-2", fixture.publicationId, fixture.operationId,
                fixture.workspaceId, fixture.userId, WikiPublicationEvent.Kind.RESTORE,
                WikiPublicationEvent.Outcome.PUBLISHED, changed)))
                .isInstanceOf(WikiPublicationConflictException.class);
    }

    @Test
    void legacyFirstStaleRestorePersistsAndReplaysDurableRejection() throws Exception {
        RestoreFixture fixture = restoreFixture("legacy-stale", true);
        String runId = id("legacy-stale-run");
        jdbc.update("""
                INSERT INTO ai_task_runs(id, workspace_id, user_id, kind, status)
                VALUES (?, ?, ?, 'restore_ingest', 'running')
                """, runId, fixture.workspaceId, fixture.userId);
        taskResults.applyRestore(legacyRestoreEvent(fixture, runId));

        assertThat(jdbc.queryForMap("""
                SELECT status, payload_hash FROM ai_operation_logs WHERE operation_id = ?
                """, fixture.operationId))
                .containsEntry("status", "conflict").containsEntry("payload_hash", null);
        assertThat(count("wiki_page_versions", "operation_id", fixture.operationId)).isZero();
        assertThat(count("wiki_page_contributions", "ingest_operation_id", fixture.operationId)).isZero();

        WikiPublicationEvent event = event("legacy-stale-restore", fixture.publicationId, fixture.operationId,
                fixture.workspaceId, fixture.userId, WikiPublicationEvent.Kind.RESTORE,
                WikiPublicationEvent.Outcome.PUBLISHED, fixture.result);
        var rejected = publications.apply(event);
        assertThat(rejected.applied()).isFalse();
        assertThat(rejected.disposition()).isEqualTo(WikiPublicationInboxRepository.Disposition.REJECTED_CONFLICT);
        assertThat(rejected.rejectionReason()).isEqualTo("STALE_RESTORE");
        assertThat(workspaceVersions.current(fixture.workspaceId)).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT disposition, rejection_reason FROM wiki_publication_inbox WHERE operation_id = ?
                """, fixture.operationId))
                .containsEntry("disposition", "REJECTED_CONFLICT")
                .containsEntry("rejection_reason", "STALE_RESTORE");

        var replay = publications.apply(event);
        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.disposition())
                .isEqualTo(WikiPublicationInboxRepository.Disposition.REJECTED_CONFLICT);
        assertThat(publications.apply(event("legacy-stale-restore-alias", fixture.publicationId, fixture.operationId,
                fixture.workspaceId, fixture.userId, WikiPublicationEvent.Kind.RESTORE,
                WikiPublicationEvent.Outcome.PUBLISHED, fixture.result)).duplicate()).isTrue();
        assertThat(count("wiki_publication_event_aliases", "operation_id", fixture.operationId)).isEqualTo(2);
        OperationResultRequest changed = new OperationResultRequest(
                fixture.operationId, "restore", "succeeded", fixture.workspaceId, fixture.userId,
                null, "changed", List.of(), List.of(), List.of(), null, null, List.of());
        assertThatThrownBy(() -> publications.apply(event(
                "legacy-stale-restore-changed", fixture.publicationId, fixture.operationId,
                fixture.workspaceId, fixture.userId, WikiPublicationEvent.Kind.RESTORE,
                WikiPublicationEvent.Outcome.PUBLISHED, changed)))
                .isInstanceOf(WikiPublicationConflictException.class);
    }

    @Test
    void legacyAndPublicationRestoreShareOperationBeforePageLockOrder() throws Exception {
        RestoreFixture fixture = restoreFixture("lock-order", false);
        String runId = id("restore-run");
        jdbc.update("""
                INSERT INTO ai_task_runs(id, workspace_id, user_id, kind, status)
                VALUES (?, ?, ?, 'restore_ingest', 'running')
                """, runId, fixture.workspaceId, fixture.userId);
        var enteredRestore = new CountDownLatch(1);
        var releaseRestore = new CountDownLatch(1);
        var calls = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            calls.incrementAndGet();
            enteredRestore.countDown();
            await(releaseRestore);
            return invocation.callRealMethod();
        }).when(restoreApplier).apply(any(), any(), any(), any(), any());

        var legacyEvent = legacyRestoreEvent(fixture, runId);
        WikiPublicationEvent publication = event(
                "publication-restore-event", fixture.publicationId, fixture.operationId,
                fixture.workspaceId, fixture.userId, WikiPublicationEvent.Kind.RECOVERY,
                WikiPublicationEvent.Outcome.PUBLISHED, fixture.result);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var legacy = pool.submit(() -> taskResults.applyRestore(legacyEvent));
            assertThat(enteredRestore.await(10, TimeUnit.SECONDS)).isTrue();
            var durable = pool.submit(() -> publications.apply(publication));
            assertThat(durable.isDone()).isFalse();
            assertThat(calls.get()).isOne();
            releaseRestore.countDown();
            legacy.get(20, TimeUnit.SECONDS);
            assertThat(durable.get(20, TimeUnit.SECONDS).disposition())
                    .isEqualTo(WikiPublicationInboxRepository.Disposition.ACCEPTED);
        }
        assertThat(calls.get()).isOne();
    }

    @Test
    void durableUserAndWorkspaceFencesPreventDelayedResurrection() throws Exception {
        Fixture userPurged = lintFixture("user-purge");
        dataPurge.purgeUser(userPurged.userId);
        assertThatThrownBy(() -> publications.apply(fixtureEvent(userPurged, "late-user-event")))
                .isInstanceOf(WikiPublicationPurgeFence.WikiPublicationPurgedScopeException.class);
        assertThat(count("wiki_page_versions", "operation_id", userPurged.operationId)).isZero();

        Fixture workspacePurged = lintFixture("workspace-purge");
        dataPurge.purgeWorkspace(workspacePurged.workspaceId);
        assertThatThrownBy(() -> publications.apply(fixtureEvent(workspacePurged, "late-workspace-event")))
                .isInstanceOf(OperationNotFoundException.class);
        assertThat(count("wiki_page_versions", "operation_id", workspacePurged.operationId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM wiki_publication_purge_fences
                WHERE (scope_type = 'user' AND scope_id = ?)
                   OR (scope_type = 'workspace' AND scope_id = ?)
                """, Integer.class, userPurged.userId, workspacePurged.workspaceId)).isEqualTo(2);
    }

    @Test
    void purgeFenceSerializesTheAbsentFenceRace() throws Exception {
        String workspace = id("race-ws");
        String user = id("race-user");
        var publicationLocked = new CountDownLatch(1);
        var releasePublication = new CountDownLatch(1);
        var purgeCommitted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var publication = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                purgeFence.lockAndRejectPurged(workspace, user);
                publicationLocked.countDown();
                await(releasePublication);
                return null;
            }));
            assertThat(publicationLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var purge = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                purgeFence.markPurged("workspace", workspace);
                purgeCommitted.countDown();
                return null;
            }));
            assertThat(purgeCommitted.await(300, TimeUnit.MILLISECONDS)).isFalse();
            releasePublication.countDown();
            publication.get(10, TimeUnit.SECONDS);
            purge.get(10, TimeUnit.SECONDS);
        }
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            purgeFence.lockAndRejectPurged(workspace, user);
            return null;
        })).isInstanceOf(WikiPublicationPurgeFence.WikiPublicationPurgedScopeException.class);
    }

    @Test
    void wholePublicationAndWorkspacePurgeSerializeWithoutResurrection() throws Exception {
        Fixture fixture = lintFixture("whole-purge-race");
        var publicationInsideApply = new CountDownLatch(1);
        var releasePublication = new CountDownLatch(1);
        var purgeFinished = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (fixture.operationId.equals(invocation.getArgument(0))) {
                publicationInsideApply.countDown();
                await(releasePublication);
            }
            return invocation.callRealMethod();
        }).when(legacyResults).accept(org.mockito.ArgumentMatchers.eq(fixture.operationId), any());

        try (var pool = Executors.newFixedThreadPool(2)) {
            var publication = pool.submit(() -> publications.apply(
                    fixtureEvent(fixture, "whole-purge-publication")));
            assertThat(publicationInsideApply.await(10, TimeUnit.SECONDS)).isTrue();
            var purge = pool.submit(() -> {
                dataPurge.purgeWorkspace(fixture.workspaceId);
                purgeFinished.countDown();
            });
            assertThat(purgeFinished.await(300, TimeUnit.MILLISECONDS)).isFalse();
            releasePublication.countDown();
            publication.get(20, TimeUnit.SECONDS);
            purge.get(20, TimeUnit.SECONDS);
        }

        assertThat(count("wiki_publication_inbox", "operation_id", fixture.operationId)).isZero();
        assertThat(count("wiki_page_versions", "operation_id", fixture.operationId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM wiki_publication_purge_fences
                WHERE scope_type = 'workspace' AND scope_id = ?
                """, Integer.class, fixture.workspaceId)).isOne();
        assertThatThrownBy(() -> publications.apply(
                fixtureEvent(fixture, "whole-purge-delayed")))
                .isInstanceOf(OperationNotFoundException.class);
    }

    private Fixture lintFixture(String name) throws Exception {
        String workspace = id(name + "-ws");
        String user = id(name + "-user");
        String page = id(name + "-page");
        String operation = id(name + "-op");
        operations.saveAndFlush(OperationLog.processing(
                operation, workspace, user, OperationType.lint, null, Instant.now()));
        String markdown = "# " + name;
        String markdownKey = key(workspace, page, operation, ".md");
        String contributionKey = key(workspace, page, operation, ".json");
        put(markdownKey, markdown);
        String hash = new WikiObjectReader(minio, storage).sha256(markdown);
        OperationResultRequest result = new OperationResultRequest(
                operation, "lint", "succeeded", workspace, user, null, "done",
                List.of(new OperationResultRequest.ChangedPage(
                        page, "concept", markdownKey, contributionKey, hash, false)),
                List.of(), List.of(), null, null,
                List.of(new OperationResultRequest.LedgerContribution(
                        page, operation, null, contributionKey, null)));
        return new Fixture(workspace, user, page, operation, id(name + "-publication"), contributionKey, result);
    }

    private Fixture emptyLintFixture(String name) {
        String workspace = id(name + "-ws");
        String user = id(name + "-user");
        String page = id(name + "-page");
        String operation = id(name + "-op");
        operations.saveAndFlush(OperationLog.processing(
                operation, workspace, user, OperationType.lint, null, Instant.now()));
        OperationResultRequest result = new OperationResultRequest(
                operation, "lint", "succeeded", workspace, user, null, "done",
                List.of(), List.of(), List.of(), null, null, List.of());
        return new Fixture(workspace, user, page, operation, id(name + "-publication"), null, result);
    }

    private Fixture ingestFixture(String name) throws Exception {
        String workspace = id(name + "-ws");
        String user = id(name + "-user");
        String page = id(name + "-page");
        String operation = id(name + "-op");
        operations.saveAndFlush(OperationLog.processing(
                operation, workspace, user, OperationType.ingest, null, Instant.now()));
        String markdown = "# " + name;
        String markdownKey = key(workspace, page, operation, ".md");
        String contributionKey = key(workspace, page, operation, ".json");
        put(markdownKey, markdown);
        OperationResultRequest result = new OperationResultRequest(
                operation, "ingest", "succeeded", workspace, user, null, "done",
                List.of(new OperationResultRequest.ChangedPage(
                        page, "concept", markdownKey, contributionKey,
                        new WikiObjectReader(minio, storage).sha256(markdown), true)),
                List.of(), List.of(), null, null, List.of());
        return new Fixture(workspace, user, page, operation,
                id(name + "-publication"), contributionKey, result);
    }

    private RestoreFixture restoreFixture(String name, boolean stale) {
        String workspace = id(name + "-ws");
        String user = id(name + "-user");
        String page = id(name + "-page");
        String original = id(name + "-original");
        String later = id(name + "-later");
        String restore = id(name + "-restore");
        operations.saveAndFlush(OperationLog.completed(original, workspace, user, OperationType.ingest,
                null, "original", 1, Instant.now().minusSeconds(30)));
        operations.saveAndFlush(OperationLog.completed(later, workspace, user, OperationType.ingest,
                null, "later", 1, Instant.now().minusSeconds(20)));
        jdbc.update("""
                INSERT INTO wiki_page_contributions(page_id, ingest_operation_id, sequence_revision, object_key, active, created_at)
                VALUES (?, ?, 1, ?, true, now()), (?, ?, 2, ?, true, now())
                """, page, original, key(workspace, page, original, ".json"),
                page, later, key(workspace, page, later, ".json"));
        jdbc.update("""
                INSERT INTO wiki_page_versions(page_id, revision, contribution_count, markdown, markdown_key,
                    content_hash, operation_id, created_by, created_at)
                VALUES (?, 1, 1, '# original', ?, ?, ?, ?, now()),
                       (?, 2, 2, '# later', ?, ?, ?, ?, now())
                """, page, key(workspace, page, original, ".md"), "sha256:" + "1".repeat(64), original, user,
                page, key(workspace, page, later, ".md"), "sha256:" + "2".repeat(64), later, user);
        var plan = new RestorePlan(List.of(PageRestorePlan.restore(page, 1, original, 1)));
        List<String> signature = stale
                ? List.of(original + ":1:1")
                : List.of(original + ":1:1", later + ":2:1");
        var manifest = new RestoreExecuteService.RestoreManifest(
                plan, Set.of(later), Map.of(page, signature));
        operations.saveAndFlush(OperationLog.applying(
                restore, workspace, user, null, later, writeJson(manifest), Instant.now()));
        OperationResultRequest result = new OperationResultRequest(
                restore, "restore", "succeeded", workspace, user, null,
                "restored", List.of(), List.of(), List.of(), null, null, List.of());
        return new RestoreFixture(workspace, user, page, restore,
                id(name + "-publication"), result);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode legacyRestoreEvent(RestoreFixture fixture, String runId) {
        var event = objectMapper.createObjectNode();
        event.put("event_id", id("legacy-restore-event"));
        event.put("kind", "restore_ingest");
        event.put("run_id", runId);
        event.put("status", "succeeded");
        event.set("request", objectMapper.valueToTree(Map.of("operation_id", fixture.operationId)));
        event.set("payload", objectMapper.valueToTree(fixture.result));
        return event;
    }

    private void registerCompleted(String operationId, String workspaceId, String userId,
                                   OperationType type, OperationResultRequest result) {
        operations.saveAndFlush(OperationLog.completed(
                operationId, workspaceId, userId, type, null, result.summary(), 0, Instant.now()));
        jdbc.update("UPDATE ai_operation_logs SET payload_hash = ? WHERE operation_id = ?",
                legacyResults.payloadHash(result), operationId);
    }

    private WikiPublicationEvent event(String eventId, String publicationId, String operationId,
                                       String workspaceId, String userId, WikiPublicationEvent.Kind kind,
                                       OperationResultRequest result) {
        return event(eventId, publicationId, operationId, workspaceId, userId, kind,
                WikiPublicationEvent.Outcome.PUBLISHED, result);
    }

    private WikiPublicationEvent event(String eventId, String publicationId, String operationId,
                                       String workspaceId, String userId, WikiPublicationEvent.Kind kind,
                                       WikiPublicationEvent.Outcome outcome,
                                       OperationResultRequest result) {
        return new WikiPublicationEvent(eventId + "-" + suffix, WikiPublicationEvent.TYPE, publicationId, operationId,
                workspaceId, userId, kind, outcome, result,
                "sha256:" + legacyResults.payloadHash(result), Instant.parse("2026-11-08T00:00:00Z"));
    }

    private WikiPublicationEvent fixtureEvent(Fixture fixture, String eventId) {
        return event(eventId, fixture.publicationId, fixture.operationId,
                fixture.workspaceId, fixture.userId, WikiPublicationEvent.Kind.LINT, fixture.result);
    }

    private OperationResultRequest manualResult(String operation, String workspace, String user,
                                                String page, String markdownKey,
                                                String contributionKey, String contentHash) {
        return new OperationResultRequest(
                operation, "wiki_edit", "succeeded", workspace, user, null,
                "Wiki 페이지 본문을 직접 수정했습니다.",
                List.of(new OperationResultRequest.ChangedPage(
                        page, "concept", markdownKey, contributionKey, contentHash, true)),
                List.of(), List.of(), null, null,
                List.of(new OperationResultRequest.LedgerContribution(
                        page, operation, null, contributionKey, null)));
    }

    private void assertOneApplied(Fixture fixture) {
        assertThat(count("wiki_publication_inbox", "operation_id", fixture.operationId)).isOne();
        assertThat(count("wiki_page_versions", "operation_id", fixture.operationId)).isOne();
        assertThat(count("wiki_page_contributions", "ingest_operation_id", fixture.operationId)).isOne();
    }

    private String payloadHash(String operationId) {
        return jdbc.queryForObject("SELECT payload_hash FROM ai_operation_logs WHERE operation_id = ?",
                String.class, operationId);
    }

    private int count(String table, String column, String value) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
    }

    private void put(String key, String markdown) throws Exception {
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        minio.putObject(PutObjectArgs.builder().bucket(storage.getBucket()).object(key)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1).build());
    }

    private String key(String workspace, String page, String operation, String suffix) {
        return "wiki/" + workspace + "/pages/" + page + "/ops/" + operation + suffix;
    }

    private String id(String value) {
        return value + "-" + suffix;
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    private String writeJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private record Fixture(String workspaceId, String userId, String pageId, String operationId,
                           String publicationId, String contributionKey, OperationResultRequest result) {}

    private record RestoreFixture(String workspaceId, String userId, String pageId,
                                  String operationId, String publicationId,
                                  OperationResultRequest result) {}
}
