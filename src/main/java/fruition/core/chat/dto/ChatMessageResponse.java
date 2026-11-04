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
        @Schema(description = "이 답변을 생성한 모델명", example = "gpt-6-luna")
        String model,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("web_search_enabled")
        @Schema(description = "이 질의에 웹 검색이 허용됐는지. 실행 당시 값이 snapshot으로 남는다.",
                example = "false")
        Boolean webSearchEnabled,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("run_id")
        @Schema(description = "Agent 또는 질의 메시지를 생성한 run ID. 진행 상태를 이 run과 연결한다.",
                example = "agent_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String runId,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "AI가 고른 갈래. 화면이 이 값으로 편집 미리보기와 일반 답변을 나눈다. "
                + "질의 메시지는 키가 빠진다.",
                allowableValues = {"chat_answer", "conversation_reply", "markdown_edit", "markdown_create", "clarify", "reject",
                        "folder_organize", "folder_lookup", "workspace_workflow", "skill_authoring", "skill_draft_proposal"},
                example = "markdown_edit")
        String action,

        @JsonProperty("clarify_reason")
        @Schema(description = "action이 clarify일 때 AI가 되물은 이유 코드. 코드가 없는 되묻기와 다른 메시지는 null이다. "
                + "화면이 갈래를 정하는 데만 쓰고 사용자 문구로 쓰지 않는다. 값은 늘어날 수 있어 모르는 값은 다른 이유의 되묻기로 본다(문장 비교로 돌아가지 않는다).",
                example = "markdown_document_required")
        String clarifyReason,

        @JsonProperty("read_only_document_id")
        @Schema(description = "요청 때 사용자가 읽을 수 있고 편집은 못 한다고 서버가 확인한 열린 문서 ID. "
                + "assistant 메시지에만 있고 그 밖에는 null이다. 안내 문구를 고르는 데만 쓰고 권한 판단에 쓰지 않는다. "
                + "문서가 지워지면 남은 ID를 가리키는 문서는 없다.",
                example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String readOnlyDocumentId,

        @Schema(description = "실제 실행 순서대로 저장된 진행 단계")
        List<com.fasterxml.jackson.databind.JsonNode> progress
) {}
