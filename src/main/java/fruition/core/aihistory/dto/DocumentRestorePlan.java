package fruition.core.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 문서 편집 revision 되돌리기 계획. 편집 revision은 선형이라 "어느 revision으로 갈지" 하나면 끝난다.
 *
 * @param fromVersion 현재 편집 revision. 미리보기 이후 바뀌면 실행을 거절하는 기준이다
 * @param toVersion   되돌릴 편집 revision. 대상 작업이 손대기 직전 revision이다
 */
@Schema(description = "문서 편집 되돌리기 계획. 편집 revision이 선형이라 목표 revision 하나로 끝난다.")
public record DocumentRestorePlan(
        @JsonProperty("document_id")
        @Schema(description = "되돌릴 문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String documentId,

        @JsonProperty("from_version")
        @Schema(description = "현재 편집 revision. 미리보기 이후 바뀌면 실행이 거절된다.", example = "5")
        long fromVersion,

        @JsonProperty("to_version")
        @Schema(description = "되돌릴 편집 revision. 대상 작업이 손대기 직전 값이다.", example = "3")
        long toVersion
) {}
