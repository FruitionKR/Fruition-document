package fruition.core.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "채팅 메시지 한 건. 질문(user)과 답변(assistant)이 pair_id로 묶인다.")
public record ChatMessageResponse(
        @Schema(description = "메시지 ID")
        String id,

        @JsonProperty("pair_id")
        @Schema(description = "질문·답변을 묶는 ID. Wiki export에서 선택 단위로 쓴다.")
        String pairId,

        @Schema(description = "발화 주체", allowableValues = {"user", "assistant"}, example = "assistant")
        String role,

        @Schema(description = "메시지 본문")
        String content,

        @Schema(description = "처리 상태. 비동기 질의는 완료 전 pending 상태로 먼저 나타난다.",
                example = "completed")
        String status,

        @JsonProperty("created_at")
        @Schema(description = "생성 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant createdAt,

        @JsonProperty("related_pages")
        @Schema(description = "답변과 관련된 Wiki 페이지 목록")
        List<ChatMessageRelatedPageResponse> relatedPages,

        @Schema(description = "답변의 근거가 된 원문 참조 목록")
        List<ChatMessageReference> references,

        @JsonProperty("partial_wiki_page_ids")
        @Schema(description = "이 문답을 내보내 만들어진 Wiki 페이지 ID 목록")
        List<String> partialWikiPageIds,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("error_message")
        @Schema(description = "실패했을 때의 사유. 성공이면 키가 빠진다.")
        String errorMessage,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "이 답변을 생성한 LLM provider. 실행 시점 값이 snapshot으로 남는다.",
                example = "openai")
        String provider,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "이 답변을 생성한 모델명", example = "gpt-5-nano")
        String model,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("web_search_enabled")
        @Schema(description = "이 질의에 웹 검색이 허용됐는지. 실행 당시 값이 snapshot으로 남는다.",
                example = "false")
        Boolean webSearchEnabled,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("run_id")
        @Schema(description = "Agent turn이 만든 메시지의 run ID. 승인 상태와 미리보기 본문을 이 run에서 읽는다. "
                + "질의 메시지는 키가 빠진다.",
                example = "agent_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String runId,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "AI가 고른 갈래. 화면이 이 값으로 편집 미리보기와 일반 답변을 나눈다. "
                + "질의 메시지는 키가 빠진다.",
                allowableValues = {"chat_answer", "conversation_reply", "markdown_edit", "markdown_create", "clarify", "reject",
                        "folder_organize", "workspace_workflow", "skill_authoring", "skill_draft_proposal"},
                example = "markdown_edit")
        String action
) {}
