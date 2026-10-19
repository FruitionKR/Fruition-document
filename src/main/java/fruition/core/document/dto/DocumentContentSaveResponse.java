package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record DocumentContentSaveResponse(
        @JsonProperty("document_id")
        @Schema(description = "저장된 문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String documentId,

        @JsonProperty("current_version")
        @Schema(description = "저장 후 버전. 다음 저장의 base_version으로 쓴다.", example = "4")
        long currentVersion,

        @JsonProperty("content_hash")
        @Schema(description = "저장된 본문의 해시. 같은 내용을 다시 저장했는지 판별에 쓴다.")
        String contentHash,

        @JsonProperty("updated_at")
        @Schema(description = "저장 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant updatedAt,

        @Schema(description = "실제로 본문이 바뀌었는지 여부. 같은 내용이면 false다.", example = "true")
        boolean changed,

        @Schema(description = "서버가 정규화한 뒤의 Markdown 본문")
        String markdown,

        @Schema(description = "본문과 함께 저장된 이미지 asset 목록")
        List<DocumentAttachmentSaveResponse> attachments,

        @Schema(description = "기준 버전이 지나간 저장을 서버가 어떻게 반영했는지. status가 merged·overwritten이면 "
                + "markdown에 저장된 최종 본문이 담긴다.")
        Merge merge
) {
    public DocumentContentSaveResponse(
            String documentId, long currentVersion, String contentHash, Instant updatedAt, boolean changed
    ) {
        this(documentId, currentVersion, contentHash, updatedAt, changed, null, List.of(), Merge.NONE);
    }

    public DocumentContentSaveResponse(
            String documentId, long currentVersion, String contentHash, Instant updatedAt, boolean changed,
            String markdown, List<DocumentAttachmentSaveResponse> attachments
    ) {
        this(documentId, currentVersion, contentHash, updatedAt, changed, markdown, attachments, Merge.NONE);
    }

    /** 이 응답에 다른 저장과의 병합 결과를 붙인다. */
    public DocumentContentSaveResponse withMerge(String finalMarkdown, Merge value) {
        return new DocumentContentSaveResponse(documentId, currentVersion, contentHash, updatedAt, changed,
                finalMarkdown, attachments, value);
    }

    @Schema(name = "DocumentContentSaveMerge", description = "자동 병합 결과")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Merge(
            @Schema(description = "none: 기준 버전이 현재 버전이라 그대로 저장. merged: 다른 저장과 겹치지 않아 줄 단위로 합쳐 저장. "
                    + "overwritten: 겹쳐서(또는 기준 버전 스냅샷이 없어) 요청 본으로 저장하고 직전 서버 본은 overwritten_version에 남김",
                    allowableValues = {STATUS_NONE, STATUS_MERGED, STATUS_OVERWRITTEN}, example = "merged")
            String status,

            @JsonProperty("base_version")
            @Schema(description = "요청이 기준으로 삼은 버전(base_revision). status가 none이면 생략", example = "10", nullable = true)
            Long baseVersion,

            @JsonProperty("server_version")
            @Schema(description = "병합 직전 서버 버전. status가 none이면 생략", example = "12", nullable = true)
            Long serverVersion,

            @JsonProperty("overwritten_version")
            @Schema(description = "overwritten일 때 덮어쓰기 전 서버 본이 남은 버전 번호. 버전 복원 API로 되살릴 수 있다. 그 밖에는 생략",
                    example = "12", nullable = true)
            Long overwrittenVersion
    ) {
        public static final String STATUS_NONE = "none";
        public static final String STATUS_MERGED = "merged";
        public static final String STATUS_OVERWRITTEN = "overwritten";
        public static final Merge NONE = new Merge(STATUS_NONE, null, null, null);

        public boolean applied() {
            return !STATUS_NONE.equals(status);
        }
    }
}
