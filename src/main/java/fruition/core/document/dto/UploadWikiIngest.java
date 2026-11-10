package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 업로드 직후 자동 위키 편입 결과. 업로드는 성공했지만 편입이 시작되지 않았음을 화면이 알릴 수 있게 응답에 싣는다.
 * 편입 자체의 성공 여부가 아니라 "편입 요청을 등록했는가"다. 등록 뒤 진행은 문서 status로 본다.
 */
@Schema(description = "업로드 직후 자동 위키 편입 판정 결과. 편입 등록 여부만 나타내며, 등록 뒤 진행·성공은 문서 status로 본다.")
public record UploadWikiIngest(
        @Schema(description = "QUEUED=편입을 등록함, SKIPPED=자동 편입 대상이었지만 등록하지 못함(문서는 uploaded로 남아 수동 편입 가능), "
                + "NOT_APPLICABLE=자동 편입 대상이 아님(PDF·스킬 참고 문서·설정 꺼짐·wiki.ingest 권한 없음)",
                example = "SKIPPED")
        Status status,

        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(description = "SKIPPED·NOT_APPLICABLE의 이유. QUEUED이거나 PDF·스킬 참고 문서처럼 처음부터 대상이 아니면 null이다. "
                + "SKIPPED: INSUFFICIENT_CREDIT(크레딧 부족), AI_USAGE_LIMIT_EXCEEDED(AI 사용 한도 초과), ERROR(설정·권한 조회나 등록 실패). "
                + "NOT_APPLICABLE: DISABLED(워크스페이스 업로드 자동 편입 꺼짐), PERMISSION(업로더에게 wiki.ingest 없음).",
                nullable = true, example = "INSUFFICIENT_CREDIT")
        Reason reason
) {
    public enum Status { QUEUED, SKIPPED, NOT_APPLICABLE }

    public enum Reason { INSUFFICIENT_CREDIT, AI_USAGE_LIMIT_EXCEEDED, ERROR, DISABLED, PERMISSION }

    public static final UploadWikiIngest QUEUED = new UploadWikiIngest(Status.QUEUED, null);
    /** PDF·스킬 참고 문서처럼 자동 편입 판정 자체를 하지 않는 업로드. */
    public static final UploadWikiIngest NOT_APPLICABLE = new UploadWikiIngest(Status.NOT_APPLICABLE, null);

    public static UploadWikiIngest skipped(Reason reason) {
        return new UploadWikiIngest(Status.SKIPPED, reason);
    }

    public static UploadWikiIngest notApplicable(Reason reason) {
        return new UploadWikiIngest(Status.NOT_APPLICABLE, reason);
    }
}
