package fruition.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * AI 에이전트 계획 자동 승인 설정. 사용자 단위이며 기본값은 모두 꺼짐(매번 승인)이다.
 * 켜도 승인 확인만 건너뛸 뿐, 대상 문서·폴더 권한 검사는 그대로다.
 */
@Schema(description = "AI 에이전트 계획 자동 승인 설정(사용자 단위, 모든 워크스페이스에 적용). 정의되지 않은 필드가 있으면 거절된다.")
public record AgentAutoApprovalSettings(
        @JsonProperty("content_edit") @NotNull
        @Schema(description = "문서 편집·생성(apply_document_edit, create_document) 계획을 자동 승인한다. 편집은 버전 기록에서 되돌릴 수 있다.",
                example = "false")
        Boolean contentEdit,

        @JsonProperty("organize") @NotNull
        @Schema(description = "폴더 생성·문서/폴더 이동·이름 변경(create_folder, move_document, move_folder, "
                + "rename_document, rename_folder) 계획을 자동 승인한다.", example = "false")
        Boolean organize
) {
    public static final AgentAutoApprovalSettings DISABLED = new AgentAutoApprovalSettings(false, false);

    public boolean anyEnabled() {
        return Boolean.TRUE.equals(contentEdit) || Boolean.TRUE.equals(organize);
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown property: " + name);
    }
}
