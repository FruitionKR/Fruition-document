package fruition.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Schema(description = "Agent turn 요청. 접수되면 202로 돌아오고 결과는 비동기로 만들어진다. "
        + "문서를 열지 않은 상태에서도 보낼 수 있으며, 그때는 편집 대신 답변·되물음만 나온다. "
        + "skill_* 필드는 snake_case이고 나머지는 camelCase다.")
public record AgentTurnRequest(
        @JsonProperty("session_id") @NotBlank
        @Schema(description = "이 턴을 남길 채팅 세션 ID. 질의와 Agent 요청이 한 세션에 함께 쌓인다.",
                example = "session_0ff8564ea24047cd8144d3f48badfe3f")
        String sessionId,

        @Schema(description = "편집 대상 문서 ID. 문서를 열지 않았으면 생략한다. "
                + "생략하면 baseVersion·editorSnapshot도 함께 생략해야 한다.",
                example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83", nullable = true)
        String documentId,

        @Min(0)
        @Schema(description = "편집 기준으로 삼을 문서 버전. documentId와 함께 생략한다.",
                minimum = "0", example = "3", nullable = true)
        Long baseVersion,

        @NotBlank
        @Schema(description = "사용자 지시문", example = "이 문단을 표로 정리해줘")
        String message,

        @Schema(description = "LLM provider. model과 짝을 이뤄야 하며 생략하면 설정값을 쓴다.",
                allowableValues = {"openai", "gemini", "claude"}, example = "openai")
        String provider,

        @Schema(description = "모델명. provider와 짝을 이뤄야 한다.", example = "gpt-5-nano")
        String model,

        @JsonProperty("allow_web_search")
        @Schema(description = "AI가 질의로 판정했을 때 웹 검색을 허용할지. 생략하면 내부 문서만 사용한다. "
                + "편집·Skill 갈래에는 영향이 없다.", nullable = true)
        Boolean allowWebSearch,

        @JsonProperty("skill_mode") @Pattern(regexp = "auto|explicit|off")
        @Schema(description = "Skill 적용 방식. explicit이면 skill_id가 필수이고, auto·off이면 skill_id를 넣을 수 없다.",
                allowableValues = {"auto", "explicit", "off"}, defaultValue = "auto", example = "auto")
        String skillMode,

        @JsonProperty("skill_id") @Size(max = 128)
        @Schema(description = "explicit 모드에서 적용할 Skill ID(128자 이하)", maxLength = 128)
        String skillId,

        @Valid
        @Schema(description = "이전 대화 맥락. 있으면 편집 판단에 함께 쓴다.")
        ConversationContext conversationContext,

        @JsonProperty("skill_draft_sources") @Size(max = 3)
        @Schema(description = "Skill 초안을 뽑아낼 이전 run 목록(최대 3개)")
        List<@Valid SkillDraftSourceSelector> skillDraftSources,

        @JsonProperty("skill_draft_user_directives")
        @Schema(description = "Skill 초안에 반영할 사용자 지시 목록")
        List<String> skillDraftUserDirectives,

        @JsonProperty("skill_draft_excluded_literals")
        @Schema(description = "Skill 초안에서 빼야 하는 리터럴 목록")
        List<String> skillDraftExcludedLiterals,

        @JsonProperty("skill_scope_type")
        @Schema(description = "Skill을 적용할 범위 종류")
        String skillScopeType,

        @Valid
        @Schema(description = "편집 시작 시점의 에디터 상태. documentId와 함께 생략한다.", nullable = true)
        EditorSnapshot editorSnapshot
) {
    private static final int MAX_SKILL_ID_LENGTH = 128;
    /**
     * 고른 문답 ID 개수 상한. 이 목록은 조회의 IN 절로 그대로 들어가 클라이언트가 SQL 크기를 정하게 된다.
     * 서버가 최근 구간에서 읽어 오는 양(문답 20개)과 같은 선까지만 받는다.
     */
    private static final int MAX_SELECTED_PAIR_IDS = 20;
    private static final Set<String> SKILL_MODES = Set.of("auto", "explicit", "off");

    /** 편집 대상이 정해진 요청인지. 셋은 함께 있거나 함께 없다. */
    public boolean hasDocumentContext() {
        return documentId != null && !documentId.isBlank();
    }

    public AgentTurnRequest(String sessionId, String documentId, Long baseVersion, String message,
                            String provider, String model,
                            ConversationContext conversationContext, EditorSnapshot editorSnapshot) {
        this(sessionId, documentId, baseVersion, message, provider, model, null, "auto", null, conversationContext,
                List.of(), List.of(), List.of(), null, editorSnapshot);
    }

    public AgentTurnRequest(String sessionId, String documentId, Long baseVersion, String message,
                            String provider, String model,
                            ConversationContext conversationContext,
                            List<SkillDraftSourceSelector> skillDraftSources,
                            List<String> skillDraftUserDirectives,
                            List<String> skillDraftExcludedLiterals,
                            String skillScopeType,
                            EditorSnapshot editorSnapshot) {
        this(sessionId, documentId, baseVersion, message, provider, model, null, "auto", null, conversationContext,
                skillDraftSources, skillDraftUserDirectives, skillDraftExcludedLiterals, skillScopeType,
                editorSnapshot);
    }

    public AgentTurnRequest {
        // 문서 셋은 함께 있거나 함께 없다. 하나만 오면 적용 경로가 반쯤 성립해 뒤에서 NPE로 터진다.
        boolean hasDocumentId = documentId != null && !documentId.isBlank();
        if (hasDocumentId != (baseVersion != null) || hasDocumentId != (editorSnapshot != null)) {
            throw new IllegalArgumentException(
                    "documentId, baseVersion, editorSnapshot must be provided together or omitted together");
        }
        skillMode = skillMode == null ? "auto" : skillMode;
        if (!SKILL_MODES.contains(skillMode)) {
            throw new IllegalArgumentException("skill_mode must be auto, explicit, or off");
        }
        if (skillId != null && (skillId.isBlank() || skillId.length() > MAX_SKILL_ID_LENGTH)) {
            throw new IllegalArgumentException("skill_id must be nonblank and at most "
                    + MAX_SKILL_ID_LENGTH + " characters");
        }
        if ("explicit".equals(skillMode) && skillId == null) {
            throw new IllegalArgumentException("explicit skill_mode requires skill_id");
        }
        if ("auto".equals(skillMode) && skillId != null) {
            throw new IllegalArgumentException("auto skill_mode must not include skill_id");
        }
        if ("off".equals(skillMode) && skillId != null) {
            throw new IllegalArgumentException("off skill_mode must not include skill_id");
        }
        skillDraftSources = skillDraftSources == null ? List.of() : List.copyOf(skillDraftSources);
        skillDraftUserDirectives = skillDraftUserDirectives == null ? List.of() : List.copyOf(skillDraftUserDirectives);
        skillDraftExcludedLiterals = skillDraftExcludedLiterals == null ? List.of() : List.copyOf(skillDraftExcludedLiterals);
    }

    @Schema(description = "이전 대화 맥락. 대화 내용 자체는 서버가 세션에서 읽어 조립한다.")
    public record ConversationContext(
            @JsonProperty("selected_pair_ids") @Size(max = MAX_SELECTED_PAIR_IDS)
            @Schema(description = "맥락으로 쓸 문답(pair) ID 목록. 비우면 세션의 최근 완결 문답을 쓴다. "
                    + "이 세션에 속하지 않은 ID는 무시한다.")
            List<String> selectedPairIds,

            @Schema(description = "참조 문맥. 키·값 형태의 자유 구조다.")
            Map<String, Object> referenceContext,

            @Valid
            @Schema(description = "사용자 확인을 기다리는 Skill 제안")
            PendingSkillProposal pendingSkillProposal
    ) {
        @Schema(description = "사용자 확인 대기 중인 Skill 제안")
        public record PendingSkillProposal(
                @JsonProperty("scope_type")
                @Schema(description = "제안된 Skill의 적용 범위 종류")
                String scopeType,

                @Schema(description = "제안된 Skill 이름")
                String name,

                @Schema(description = "제안된 Skill 설명")
                String description,

                @JsonProperty("instructions_markdown")
                @Schema(description = "제안된 Skill의 지침 Markdown")
                String instructionsMarkdown,

                @NotNull
                @Size(min = 1)
                @Schema(description = "검토된 Skill capability")
                List<String> capabilities,

                @JsonProperty("allowed_tools")
                @NotNull
                @Size(min = 1)
                @Schema(description = "검토된 Tool 허용 목록")
                List<String> allowedTools
        ) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Skill 초안의 출처가 되는 이전 run")
    public record SkillDraftSourceSelector(
            @JsonProperty("run_id") @NotBlank
            @Schema(description = "초안 근거로 쓸 Agent run ID")
            String runId
    ) {}

    @Schema(description = "편집 시작 시점의 에디터 상태")
    public record EditorSnapshot(
            @NotNull
            @Schema(description = "에디터에 열려 있는 전체 Markdown 본문")
            String markdown,

            @Valid
            @Schema(description = "편집 대상 범위. 생략하면 문서 전체가 대상이다.")
            Target target
    ) {}

    @Schema(description = "편집 대상 범위(1-based 줄 번호)")
    public record Target(
            @NotBlank
            @Schema(description = "범위 종류", example = "selection")
            String type,

            @Min(1)
            @Schema(description = "시작 줄 번호(1부터)", minimum = "1", example = "10")
            int startLine,

            @Min(1)
            @Schema(description = "끝 줄 번호(1부터, 포함)", minimum = "1", example = "24")
            int endLine
    ) {}
}
