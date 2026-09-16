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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ingest 결과 콜백 수신. llmPipeline이 아직 보내지 않으므로 mock payload로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OperationIngestServiceTest {

    private static final String OPERATION_ID = "op_ingest_1";
    private static final String WORKSPACE_ID = "ws_1";
    private static final String USER_ID = "user_1";
    private static final String DOCUMENT_ID = "doc_A";
    private static final String PAGE_ID = "C1";

    @Mock OperationLogRepository operationLogRepository;
    @Mock WikiObjectReader objectReader;
    @Mock OperationApplier applier;
    @Mock LintOperationApplier lintApplier;
    @Mock RestoreRebuildApplier rebuildApplier;

    private OperationIngestService service;

    @BeforeEach
    void setUp() {
        service = new OperationIngestService(operationLogRepository, objectReader, applier, lintApplier,
                rebuildApplier, new ObjectMapper());
    }

    @Test
    @DisplayName("정상 콜백은 읽은 결과를 적재로 넘긴다")
    void acceptsValidCallback() {
        givenProcessingOperation();
        givenObject("# 개념 C1", "sha256:aaa");
        when(applier.apply(anyString(), any(), anyList(), anyString(), any()))
                .thenReturn(new OperationResultResponse(OPERATION_ID, "succeeded", 1));

        OperationResultResponse response = service.accept(OPERATION_ID, request("sha256:aaa"));

        assertThat(response.recordedChanges()).isEqualTo(1);
        verify(applier).apply(anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    @DisplayName("본문 해시가 다르면 422로 거절하고 적재하지 않는다")
    void rejectsHashMismatch() {
        givenProcessingOperation();
        givenObject("# 개념 C1", "sha256:actual");

        assertThatThrownBy(() -> service.accept(OPERATION_ID, request("sha256:reported")))
                .isInstanceOf(InvalidCallbackPayloadException.class);

        verify(applier, never()).apply(anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    @DisplayName("다른 작업의 기여 key는 거절하고 적재하지 않는다")
    void rejectsForeignContributionKey() {
        givenProcessingOperation();
        givenObject("# 개념 C1", "sha256:aaa");
        when(objectReader.validateContributionKey(
                eq("wiki/ws_1/pages/C1/ops/op_other.json"),
                anyString(), anyString(), anyString()))
                .thenThrow(new InvalidCallbackPayloadException("허용되지 않은 기여 경로입니다"));
        OperationResultRequest forged = new OperationResultRequest(
                OPERATION_ID, "ingest", "succeeded", WORKSPACE_ID, USER_ID, DOCUMENT_ID,
                "요약", List.of(new OperationResultRequest.ChangedPage(
                        PAGE_ID, "concept",
                        "wiki/ws_1/pages/C1/ops/" + OPERATION_ID + ".md",
                        "wiki/ws_1/pages/C1/ops/op_other.json",
                        "sha256:aaa", true)), null);

        assertThatThrownBy(() -> service.accept(OPERATION_ID, forged))
                .isInstanceOf(InvalidCallbackPayloadException.class);

        verify(applier, never()).apply(anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    @DisplayName("lint 결과는 기여를 만드는 ingest 적재와 분리한다")
    void routesLintOperationToLintApplier() {
        OperationLog lint = OperationLog.processing(
                "op_lint_1", WORKSPACE_ID, USER_ID, OperationType.lint, null, Instant.now());
        when(operationLogRepository.findById("op_lint_1")).thenReturn(Optional.of(lint));
        givenObject("# 정리된 개념", "sha256:lint");
        when(lintApplier.apply(anyString(), any(), anyList(), anyString(), any()))
                .thenReturn(new OperationResultResponse("op_lint_1", "succeeded", 1));
        OperationResultRequest request = new OperationResultRequest(
                "op_lint_1", "lint", "succeeded", WORKSPACE_ID, USER_ID, null,
                "Wiki lint를 실행했습니다.",
                List.of(new OperationResultRequest.ChangedPage(
                        PAGE_ID, "concept", "wiki/ws_1/pages/C1/ops/op_lint_1.md",
                        "wiki/ws_1/pages/C1/ops/op_lint_1.json", "sha256:lint", false)),
                List.of());

        service.accept("op_lint_1", request);

        verify(lintApplier).apply(anyString(), any(), anyList(), anyString(), any());
        verify(applier, never()).apply(anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    @DisplayName("완료된 lint에 같은 결과가 다시 오면 기존 결과를 반환한다")
    void lintResendReturnsExistingResult() {
        OperationResultRequest request = lintRequest("sha256:lint");
        OperationLog done = OperationLog.processing(
                "op_lint_1", WORKSPACE_ID, USER_ID, OperationType.lint, null, Instant.now());
        done.complete(OperationStatus.succeeded, "요약", 2, payloadHashOf(request), Instant.now());
        when(operationLogRepository.findById("op_lint_1")).thenReturn(Optional.of(done));

        assertThat(service.accept("op_lint_1", request).recordedChanges()).isEqualTo(2);
        verify(lintApplier, never()).apply(anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    @DisplayName("완료된 lint에 다른 결과가 오면 충돌로 거절한다")
    void differentPayloadOnFinishedLintConflicts() {
        OperationLog done = OperationLog.processing(
                "op_lint_1", WORKSPACE_ID, USER_ID, OperationType.lint, null, Instant.now());
        done.complete(OperationStatus.succeeded, "요약", 2, "다른해시", Instant.now());
        when(operationLogRepository.findById("op_lint_1")).thenReturn(Optional.of(done));

        assertThatThrownBy(() -> service.accept("op_lint_1", lintRequest("sha256:lint")))
                .isInstanceOf(OperationPayloadConflictException.class);
        verify(lintApplier, never()).apply(anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    @DisplayName("등록되지 않은 작업은 404")
    void rejectsUnknownOperation() {
        when(operationLogRepository.findById(OPERATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept(OPERATION_ID, request("sha256:aaa")))
                .isInstanceOf(OperationNotFoundException.class);
    }

    @Test
    @DisplayName("경로와 본문의 operation_id가 다르면 거절한다")
    void rejectsMismatchedPathId() {
        assertThatThrownBy(() -> service.accept("op_other", request("sha256:aaa")))
                .isInstanceOf(InvalidCallbackPayloadException.class);
    }

    @Test
    @DisplayName("콜백이 보낸 워크스페이스가 등록값과 다르면 거절한다")
    void rejectsForeignWorkspace() {
        givenProcessingOperation();
        OperationResultRequest forged = new OperationResultRequest(
                OPERATION_ID, "ingest", "succeeded", "ws_other", USER_ID, DOCUMENT_ID,
                "요약", List.of(changedPage("sha256:aaa")), null);

        assertThatThrownBy(() -> service.accept(OPERATION_ID, forged))
                .isInstanceOf(InvalidCallbackPayloadException.class);

        verify(applier, never()).apply(anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    @DisplayName("같은 payload 재전송은 기존 결과를 그대로 돌려준다")
    void resendReturnsExistingResult() {
        OperationResultRequest request = request("sha256:aaa");
        OperationLog done = processingOperation();
        done.complete(OperationStatus.succeeded, "요약", 3, payloadHashOf(request), Instant.now());
        when(operationLogRepository.findById(OPERATION_ID)).thenReturn(Optional.of(done));

        assertThat(service.accept(OPERATION_ID, request).recordedChanges()).isEqualTo(3);
        verify(applier, never()).apply(anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    @DisplayName("같은 작업에 다른 payload가 오면 409")
    void differentPayloadOnFinishedOperationConflicts() {
        OperationLog done = processingOperation();
        done.complete(OperationStatus.succeeded, "요약", 3, "다른해시", Instant.now());
        when(operationLogRepository.findById(OPERATION_ID)).thenReturn(Optional.of(done));

        assertThatThrownBy(() -> service.accept(OPERATION_ID, request("sha256:aaa")))
                .isInstanceOf(OperationPayloadConflictException.class);
    }

    @Test
    @DisplayName("복구 작업의 결과는 재조립 분기로 간다")
    void routesRestoreOperationToRebuild() {
        when(operationLogRepository.findById(OPERATION_ID)).thenReturn(Optional.of(rebuildingRestore()));
        givenObject("# 다시 만든 C1", "sha256:new");
        when(rebuildApplier.apply(anyString(), any(), anyList(), anyString(), any()))
                .thenReturn(new OperationResultResponse(OPERATION_ID, "succeeded", 2));

        OperationResultResponse response = service.accept(OPERATION_ID, restoreRequest("sha256:new"));

        assertThat(response.status()).isEqualTo("succeeded");
        verify(rebuildApplier).apply(anyString(), any(), anyList(), anyString(), any());
        verify(applier, never()).apply(anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    @DisplayName("재조립을 기다리는 상태가 아니면 거절한다")
    void rejectsRebuildOnUnexpectedStatus() {
        OperationLog restore = rebuildingRestore();
        restore.moveTo(OperationStatus.applying);
        when(operationLogRepository.findById(OPERATION_ID)).thenReturn(Optional.of(restore));

        assertThatThrownBy(() -> service.accept(OPERATION_ID, restoreRequest("sha256:new")))
                .isInstanceOf(InvalidCallbackPayloadException.class);

        verify(rebuildApplier, never()).apply(anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    @DisplayName("재조립 본문 해시가 다르면 거절하고 반영하지 않는다")
    void rejectsRebuildHashMismatch() {
        when(operationLogRepository.findById(OPERATION_ID)).thenReturn(Optional.of(rebuildingRestore()));
        givenObject("# 다시 만든 C1", "sha256:actual");

        assertThatThrownBy(() -> service.accept(OPERATION_ID, restoreRequest("sha256:reported")))
                .isInstanceOf(InvalidCallbackPayloadException.class);

        verify(rebuildApplier, never()).apply(anyString(), any(), anyList(), anyString(), any());
    }

    // --- helpers ---

    private OperationLog rebuildingRestore() {
        OperationLog restore = OperationLog.applying(OPERATION_ID, WORKSPACE_ID, USER_ID,
                DOCUMENT_ID, "op_a2", "{}", Instant.parse("2026-07-31T00:00:00Z"));
        restore.moveTo(OperationStatus.rebuilding);
        return restore;
    }

    private OperationResultRequest restoreRequest(String reportedHash) {
        return new OperationResultRequest(
                OPERATION_ID, "restore", "succeeded", WORKSPACE_ID, USER_ID, DOCUMENT_ID,
                "재조립 1건", List.of(changedPage(reportedHash)), List.of());
    }

    private void givenProcessingOperation() {
        when(operationLogRepository.findById(OPERATION_ID)).thenReturn(Optional.of(processingOperation()));
    }

    private OperationLog processingOperation() {
        return OperationLog.processing(OPERATION_ID, WORKSPACE_ID, USER_ID,
                OperationType.ingest, DOCUMENT_ID, Instant.parse("2026-07-31T00:00:00Z"));
    }

    private void givenObject(String markdown, String hash) {
        when(objectReader.read(anyString(), anyString(), anyString(), anyString())).thenReturn(markdown);
        when(objectReader.sha256(markdown)).thenReturn(hash);
        when(objectReader.contributionKey(anyString(), anyString(), anyString()))
                .thenReturn("wiki/ws_1/pages/C1/ops/" + OPERATION_ID + ".json");
        when(objectReader.validateContributionKey(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private OperationResultRequest request(String reportedHash) {
        return new OperationResultRequest(
                OPERATION_ID, "ingest", "succeeded", WORKSPACE_ID, USER_ID, DOCUMENT_ID,
                "위키 페이지 1개를 갱신했습니다.", List.of(changedPage(reportedHash)), null);
    }

    private OperationResultRequest lintRequest(String reportedHash) {
        return new OperationResultRequest(
                "op_lint_1", "lint", "succeeded", WORKSPACE_ID, USER_ID, null,
                "요약", List.of(new OperationResultRequest.ChangedPage(
                        PAGE_ID, "concept", "wiki/ws_1/pages/C1/ops/op_lint_1.md",
                        "wiki/ws_1/pages/C1/ops/op_lint_1.json", reportedHash, false)),
                List.of());
    }

    private OperationResultRequest.ChangedPage changedPage(String contentHash) {
        return new OperationResultRequest.ChangedPage(
                PAGE_ID, "concept",
                "wiki/ws_1/pages/C1/ops/" + OPERATION_ID + ".md",
                "wiki/ws_1/pages/C1/ops/" + OPERATION_ID + ".json",
                contentHash, true);
    }

    /** 서비스와 같은 방식으로 계산해야 재전송 판정이 맞는지 확인할 수 있다. */
    private String payloadHashOf(OperationResultRequest request) {
        try {
            String canonical = new ObjectMapper().writeValueAsString(request);
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
