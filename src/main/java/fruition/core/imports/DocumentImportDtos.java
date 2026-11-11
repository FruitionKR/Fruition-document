package fruition.core.imports;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 가져오기 API 요청·응답. JSON 필드는 snake_case다. */
public final class DocumentImportDtos {
    private DocumentImportDtos() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "DocumentImportCreateRequest")
    public record CreateRequest(
            @Schema(description = "가져올 원본 서비스. 지금은 notion만 받는다.", example = "notion") String source,
            @Schema(description = "가져올 항목(문서+이미지) 수(기록용, 0 이상). 상한은 없다", example = "120") Integer itemCount,
            @Schema(description = "가져올 파일 바이트 합. 저장 용량을 넘으면 413 STORAGE_QUOTA_EXCEEDED", example = "52428800") Long totalBytes) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "DocumentImportQuota")
    public record Quota(
            @Schema(description = "지금 쓰고 있는 저장 공간(바이트)") long usedBytes,
            @Schema(description = "저장 공간 한도(바이트). 한도가 꺼져 있으면 null", nullable = true) Long limitBytes) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "DocumentImportCreateResponse")
    public record CreateResponse(
            UUID importId,
            @Schema(description = "항상 open", example = "open") String status,
            @Schema(description = "세션 만료 시각(생성 24시간 뒤). 그 뒤 이 세션으로 쓰면 400 IMPORT_INVALID") Instant expiresAt,
            @Schema(description = "세션 항목 상한. 상한이 없어 항상 null(계약 안정성을 위해 필드는 유지)", nullable = true) Integer maxItems,
            Quota quota) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "DocumentImportCompleteRequest")
    public record CompleteRequest(
            @Schema(description = "클라이언트가 만든 항목 수(기록용)") Integer created,
            @Schema(description = "클라이언트가 실패한 항목 수(기록용)") Integer failed,
            @Schema(description = "클라이언트가 건너뛴 항목 수(기록용)") Integer skipped) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "DocumentImportCompleteResponse")
    public record CompleteResponse(
            UUID importId,
            @Schema(example = "completed") String status,
            @Schema(description = "서버가 센, 이 세션에서 실제로 만든 문서 수") int documentCount) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "DocumentImportResponse")
    public record ImportResponse(
            UUID importId,
            @Schema(example = "notion") String source,
            @Schema(description = "open | completed | expired(열린 채 만료)", example = "completed") String status,
            Instant createdAt,
            Instant expiresAt,
            @Schema(nullable = true) Instant completedAt,
            @Schema(description = "세션 항목 상한. 상한이 없어 항상 null", nullable = true) Integer maxItems,
            @Schema(description = "세션 생성 때 알린 항목 수") int itemCount,
            @Schema(description = "지금까지 센 항목 수(문서+이미지)") int itemsUsed,
            @Schema(description = "이 세션에서 만든 문서 수(휴지통 포함)") int documentCount,
            @Schema(description = "이 세션에서 만든 문서 ID(휴지통 포함), 만든 순서") List<String> documentIds,
            @Schema(description = "완료 때 클라이언트가 알린 만든 수. 완료 전 null", nullable = true) Integer created,
            @Schema(description = "완료 때 클라이언트가 알린 실패 수. 완료 전 null", nullable = true) Integer failed,
            @Schema(description = "완료 때 클라이언트가 알린 건너뛴 수. 완료 전 null", nullable = true) Integer skipped) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "DocumentImportIngestEstimateResponse")
    public record IngestEstimateResponse(
            @Schema(description = "아직 편입하지 않은(편입 요청한 적 없는) 휴지통 밖 가져온 Markdown 문서 수. PDF 원본은 빼고, PDF 변환본은 변환이 끝난 것만 센다") int documentCount,
            @Schema(description = "예상 비용(USD) = 본문 길이별 예약액 합계를 최신 환율로 환산. 환율을 못 읽으면 null", nullable = true)
            BigDecimal estimatedCostUsd,
            @Schema(description = "예상 크레딧(milli-KRW, /users/me/credits의 *_krw_milli와 같은 단위) = 각 Markdown 본문의 UTF-8 바이트 길이별 예약액 합계",
                    nullable = true)
            Long estimatedCredits,
            @Schema(description = "아직 Markdown 변환 중이라 document_count에서 빠진 가져온 PDF 변환본 수. 변환이 끝나면 편입 대상에 들어간다")
            int convertingCount,
            @Schema(description = "아직 변환하지 않은(변환본이 없거나 변환에 실패한) 가져온 PDF 수. POST .../convert로 변환한다")
            int pdfUnconvertedCount,
            @Schema(description = "PDF 변환 예상 비용(USD) = 변환 1건 예상 비용 × pdf_unconverted_count. 환율을 못 읽으면 null", nullable = true)
            BigDecimal convertEstimatedCostUsd,
            @Schema(description = "PDF 변환 예상 크레딧(milli-KRW) = app.billing.estimate.convert(없으면 기본값) × pdf_unconverted_count")
            Long convertEstimatedCredits) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "DocumentImportIngestRequest")
    public record IngestRequest(
            @Schema(description = "편입할 문서 ID(최대 20개, 모두 이 세션 문서여야 함). 생략하면 아직 편입하지 않은 가져온 문서 중 앞 20개")
            List<String> documentIds) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "DocumentImportIngestResponse")
    public record IngestResponse(
            @Schema(description = "이번 요청에서 편입 큐에 넣은 문서 수") int queued,
            @Schema(description = "아직 편입하지 않은 가져온 문서 수. 0이 될 때까지 document_ids 없이 다시 부르면 된다")
            int remaining) {}
}
