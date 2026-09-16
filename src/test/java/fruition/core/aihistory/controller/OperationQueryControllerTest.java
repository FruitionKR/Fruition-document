package fruition.core.aihistory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.dto.DocumentRestorePlan;
import fruition.core.aihistory.dto.OperationLogDetailResponse;
import fruition.core.aihistory.dto.OperationLogListResponse;
import fruition.core.aihistory.dto.RestoreExecuteResponse;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.dto.RestorePreviewResponse;
import fruition.core.aihistory.exception.OperationNotFoundException;
import fruition.core.aihistory.exception.RestorePreviewStaleException;
import fruition.core.aihistory.service.OperationQueryService;
import fruition.core.aihistory.service.RestoreExecuteService;
import fruition.core.aihistory.service.RestorePreviewService;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import fruition.core.config.SecurityConfig;
import fruition.core.CoreExceptionHandler;
import fruition.core.authz.WorkspaceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 작업 로그 조회·복구 엔드포인트. 인증과 응답 직렬화를 확인한다.
 */
@WebMvcTest(OperationQueryController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class OperationQueryControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";
    private static final String OPERATION_ID = "op_a2";
    private static final String BASE = "/api/workspaces/" + WORKSPACE_ID + "/ai-operation-logs";
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean OperationQueryService queryService;
    @MockBean RestorePreviewService previewService;
    @MockBean RestoreExecuteService executeService;

    @Test
    @DisplayName("목록은 snake_case로 내려가고 커서를 그대로 전달한다")
    void listReturnsSnakeCaseAndPassesCursor() throws Exception {
        when(queryService.list(eq(WORKSPACE_ID), eq(USER_ID), eq("ingest"), isNull(),
                eq("2026-08-01T00:00:00Z"), eq(50)))
                .thenReturn(new OperationLogListResponse(List.of(
                        new OperationLogListResponse.Item(OPERATION_ID, "ingest", "succeeded",
                                "doc_A", "설계 문서", "요약", 3, null, NOW, NOW)),
                        "2026-07-31T00:00:00Z"));

        mockMvc.perform(get(BASE)
                        .header("Authorization", bearer())
                        .param("type", "ingest")
                        .param("cursor", "2026-08-01T00:00:00Z")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[0].operation_id").value(OPERATION_ID))
                .andExpect(jsonPath("$.logs[0].operation_type").value("ingest"))
                .andExpect(jsonPath("$.logs[0].target_display_name").value("설계 문서"))
                .andExpect(jsonPath("$.logs[0].changed_resource_count").value(3))
                .andExpect(jsonPath("$.next_cursor").value("2026-07-31T00:00:00Z"));
    }

    @Test
    @DisplayName("lint 목록은 문서 대상 없이 저장된 변경 개수를 반환한다")
    void listReturnsLintLog() throws Exception {
        when(queryService.list(eq(WORKSPACE_ID), eq(USER_ID), eq("lint"), isNull(),
                isNull(), eq(20)))
                .thenReturn(new OperationLogListResponse(List.of(
                        new OperationLogListResponse.Item(OPERATION_ID, "lint", "succeeded",
                                null, "위키 다듬기", "Wiki lint로 페이지 2개를 변경했습니다.",
                                2, null, NOW, NOW)), null));

        mockMvc.perform(get(BASE)
                        .header("Authorization", bearer())
                        .param("type", "lint")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[0].operation_type").value("lint"))
                .andExpect(jsonPath("$.logs[0].target_document_id").doesNotExist())
                .andExpect(jsonPath("$.logs[0].changed_resource_count").value(2));
    }

    @Test
    @DisplayName("필터를 안 주면 null로 넘어간다")
    void listWithoutFiltersPassesNulls() throws Exception {
        when(queryService.list(any(), any(), any(), any(), any(), any()))
                .thenReturn(new OperationLogListResponse(List.of(), null));

        mockMvc.perform(get(BASE).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next_cursor").doesNotExist());

        verify(queryService).list(WORKSPACE_ID, USER_ID, null, null, null, null);
    }

    @Test
    @DisplayName("Wiki 작업 상세는 유형과 제목을 전달하고 변경분을 생략한다")
    void detailIncludesWikiTitles() throws Exception {
        when(queryService.detail(WORKSPACE_ID, USER_ID, OPERATION_ID))
                .thenReturn(new OperationLogDetailResponse(OPERATION_ID, "ingest", "succeeded",
                        "doc_A", "요약", 1, null, NOW, NOW,
                        List.of(new OperationLogDetailResponse.Change(1L, "wiki_page", "wp_C3",
                                "개념 C3", null, null, "created", null, null, null, null, null, "concept"))));

        mockMvc.perform(get(BASE + "/" + OPERATION_ID).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].resource_id").value("wp_C3"))
                .andExpect(jsonPath("$.changes[0].resource_display_name").value("개념 C3"))
                .andExpect(jsonPath("$.changes[0].page_type").value("concept"))
                .andExpect(jsonPath("$.changes[0].before_revision").doesNotExist())
                .andExpect(jsonPath("$.changes[0].change_type").value("created"))
                .andExpect(jsonPath("$.changes[0].hunks").doesNotExist())
                // 값이 없으면 응답에서 생략한다.
                .andExpect(jsonPath("$.changes[0].diff_too_large").doesNotExist())
                .andExpect(jsonPath("$.restore").doesNotExist());
    }

    @Test
    @DisplayName("복구 상세는 계획·결과 요약과 변경 리소스를 함께 직렬화한다")
    void detailIncludesSanitizedRestoreSummary() throws Exception {
        OperationLogDetailResponse.RestoreSummary restore =
                new OperationLogDetailResponse.RestoreSummary(
                        new OperationLogDetailResponse.RestorePlanSummary(
                                1, 0, 1,
                                List.of(new OperationLogDetailResponse.PlanPage(
                                        "wp_delete", "delete", 0))),
                        new OperationLogDetailResponse.RestoreResult(1, 0, 0, 0, 1, 0));
        when(queryService.detail(WORKSPACE_ID, USER_ID, OPERATION_ID))
                .thenReturn(new OperationLogDetailResponse(OPERATION_ID, "restore", "rebuilding",
                        "doc_A", null, "복구 중", 2, "op_a1", NOW, null,
                        List.of(new OperationLogDetailResponse.Change(1L, "wiki_page", "wp_delete",
                                null, 3L, null, "deleted", null, null, null, null, null, null)), restore));

        mockMvc.perform(get(BASE + "/" + OPERATION_ID).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restore.plan.delete_count").value(1))
                .andExpect(jsonPath("$.restore.plan.pages[0].page_id").value("wp_delete"))
                .andExpect(jsonPath("$.restore.result.deleted_count").value(1))
                .andExpect(jsonPath("$.restore.result.removed_link_count").value(1))
                .andExpect(jsonPath("$.restore.plan.callbackUrl").doesNotExist())
                .andExpect(jsonPath("$.restore.previewToken").doesNotExist());
    }

    @Test
    @DisplayName("없는 작업은 404")
    void detailNotFound() throws Exception {
        when(queryService.detail(any(), any(), any()))
                .thenThrow(new OperationNotFoundException(OPERATION_ID));

        mockMvc.perform(get(BASE + "/" + OPERATION_ID).header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("AI_OPERATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("다른 워크스페이스 로그는 404")
    void detailOfForeignWorkspaceIsNotFound() throws Exception {
        when(queryService.detail(any(), any(), any()))
                .thenThrow(new WorkspaceNotFoundException(WORKSPACE_ID));

        mockMvc.perform(get(BASE + "/" + OPERATION_ID).header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Wiki 미리보기는 pages가 차고 document는 생략된다")
    void previewForWikiOmitsDocument() throws Exception {
        when(previewService.preview(WORKSPACE_ID, USER_ID, OPERATION_ID))
                .thenReturn(new RestorePreviewResponse(OPERATION_ID, 1, 1, 1,
                        List.of(new RestorePreviewResponse.Page("wp_C3", "rebuild", null, 2)),
                        null, "token-abc"));

        mockMvc.perform(get(BASE + "/" + OPERATION_ID + "/restore-preview")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages[0].page_id").value("wp_C3"))
                .andExpect(jsonPath("$.pages[0].contribution_count").value(2))
                .andExpect(jsonPath("$.preview_token").value("token-abc"))
                .andExpect(jsonPath("$.document").doesNotExist());
    }

    @Test
    @DisplayName("문서 편집 미리보기는 document가 차고 pages는 빈 배열이다")
    void previewForDocumentIncludesDocumentPlan() throws Exception {
        when(previewService.preview(WORKSPACE_ID, USER_ID, OPERATION_ID))
                .thenReturn(RestorePreviewResponse.from(OPERATION_ID,
                        new DocumentRestorePlan("doc_A", 6, 5), "token-abc"));

        mockMvc.perform(get(BASE + "/" + OPERATION_ID + "/restore-preview")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.document_id").value("doc_A"))
                .andExpect(jsonPath("$.document.from_version").value(6))
                .andExpect(jsonPath("$.document.to_version").value(5))
                .andExpect(jsonPath("$.pages").isEmpty());
    }

    @Test
    @DisplayName("되돌리기는 preview_token을 그대로 서비스에 넘긴다")
    void restorePassesPreviewToken() throws Exception {
        when(executeService.execute(WORKSPACE_ID, USER_ID, OPERATION_ID, "token-abc"))
                .thenReturn(RestoreExecuteResponse.from(OPERATION_ID, "op_a2",
                        new RestorePlan(List.of()), true));

        mockMvc.perform(post(BASE + "/" + OPERATION_ID + "/restore")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preview_token\":\"token-abc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rebuilding"))
                .andExpect(jsonPath("$.rebuilding").value(true));

        verify(executeService).execute(WORKSPACE_ID, USER_ID, OPERATION_ID, "token-abc");
    }

    @Test
    @DisplayName("preview_token이 비면 400")
    void restoreWithoutTokenIsBadRequest() throws Exception {
        mockMvc.perform(post(BASE + "/" + OPERATION_ID + "/restore")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preview_token\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("미리보기 이후 대상이 바뀌면 409")
    void restoreWithStalePreviewIsConflict() throws Exception {
        when(executeService.execute(any(), any(), any(), any()))
                .thenThrow(new RestorePreviewStaleException());

        mockMvc.perform(post(BASE + "/" + OPERATION_ID + "/restore")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preview_token\":\"token-abc\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESTORE_PREVIEW_STALE"));
    }

    @Test
    @DisplayName("인증 없이는 목록도 되돌리기도 401")
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/" + OPERATION_ID + "/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preview_token\":\"token-abc\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }
}
