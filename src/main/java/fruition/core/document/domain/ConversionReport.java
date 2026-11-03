package fruition.core.document.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * PDF 변환 batch 결과가 알려 준 복원 요약과 그림 설명 집계. 변환 문서({@code documents.conversion_report})에 쌓는다.
 * 본문은 담지 않고 쪽 번호·사유 코드·개수만 담는다. batch마다 합치며 순서가 바뀌어 와도 같은 결과가 된다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversionReport(
        @JsonProperty("restoration_summary") RestorationSummary restorationSummary,
        @JsonProperty("figure_descriptions") FigureDescriptions figureDescriptions
) {

    /** AI가 알려 주는 원문 대체 사유. 모르는 값은 {@link #UNKNOWN_REASON}으로 바꿔 화면이 일반 문구를 쓰게 한다. */
    public static final Set<String> FALLBACK_REASONS = Set.of(
            "font_mapping", "replacement_character", "token_mismatch", "extract_failed", "repair_rejected", "provider_error");
    public static final String UNKNOWN_REASON = "unknown";
    /** 쪽마다 하나라 PDF 최대 쪽수보다 많을 수 없다. 잘못된 결과가 행을 키우지 않게 막는다. */
    static final int MAX_FALLBACK_PAGES = 5000;

    public record RestorationSummary(
            @JsonProperty("broken_pages") int brokenPages,
            @JsonProperty("repaired_pages") int repairedPages,
            @JsonProperty("fallback_pages") List<FallbackPage> fallbackPages
    ) {}

    public record FallbackPage(int page, String reason) {}

    public record FigureDescriptions(int requested, int described, int failed, int skipped) {}

    /** batch 결과 payload에서 두 객체를 읽는다. 없거나 모양이 틀린 객체는 없는 것으로 본다(선택 필드). */
    public static ConversionReport fromBatchPayload(JsonNode payload) {
        if (payload == null) return null;
        RestorationSummary restoration = parseRestoration(payload.path("restoration_summary"));
        FigureDescriptions figures = parseFigures(payload.path("figure_descriptions"));
        return restoration == null && figures == null ? null : new ConversionReport(restoration, figures);
    }

    /** 두 집계를 더한 새 값을 돌려준다. 같은 쪽이 두 번 오면 앞의 사유를 남긴다. */
    public static ConversionReport merge(ConversionReport current, ConversionReport next) {
        if (next == null) return current;
        if (current == null) return next;
        return new ConversionReport(
                mergeRestoration(current.restorationSummary(), next.restorationSummary()),
                mergeFigures(current.figureDescriptions(), next.figureDescriptions()));
    }

    /** 원문으로 대신한 쪽 수. 요약이 없으면 0이다. */
    public int fallbackPageCount() {
        return restorationSummary == null ? 0 : restorationSummary.fallbackPages().size();
    }

    /** 운영 로그용 사유별 쪽 수. 본문은 담지 않는다. */
    public Map<String, Long> fallbackReasonCounts() {
        if (restorationSummary == null) return Map.of();
        return restorationSummary.fallbackPages().stream()
                .collect(Collectors.groupingBy(FallbackPage::reason, TreeMap::new, Collectors.counting()));
    }

    private static RestorationSummary parseRestoration(JsonNode node) {
        if (!node.isObject()) return null;
        List<FallbackPage> pages = new ArrayList<>();
        for (JsonNode item : node.path("fallback_pages")) {
            int page = item.path("page").asInt(0);
            if (page < 1 || pages.size() >= MAX_FALLBACK_PAGES) continue;
            String reason = item.path("reason").asText("");
            pages.add(new FallbackPage(page, FALLBACK_REASONS.contains(reason) ? reason : UNKNOWN_REASON));
        }
        return new RestorationSummary(count(node, "broken_pages"), count(node, "repaired_pages"), dedupe(pages));
    }

    private static FigureDescriptions parseFigures(JsonNode node) {
        if (!node.isObject()) return null;
        return new FigureDescriptions(count(node, "requested"), count(node, "described"),
                count(node, "failed"), count(node, "skipped"));
    }

    private static int count(JsonNode node, String field) {
        return Math.max(0, node.path(field).asInt(0));
    }

    private static RestorationSummary mergeRestoration(RestorationSummary a, RestorationSummary b) {
        if (a == null) return b;
        if (b == null) return a;
        List<FallbackPage> pages = new ArrayList<>(a.fallbackPages());
        pages.addAll(b.fallbackPages());
        return new RestorationSummary(a.brokenPages() + b.brokenPages(), a.repairedPages() + b.repairedPages(),
                dedupe(pages));
    }

    private static FigureDescriptions mergeFigures(FigureDescriptions a, FigureDescriptions b) {
        if (a == null) return b;
        if (b == null) return a;
        return new FigureDescriptions(a.requested() + b.requested(), a.described() + b.described(),
                a.failed() + b.failed(), a.skipped() + b.skipped());
    }

    private static List<FallbackPage> dedupe(List<FallbackPage> pages) {
        Map<Integer, FallbackPage> byPage = new TreeMap<>();
        pages.forEach(page -> byPage.putIfAbsent(page.page(), page));
        return byPage.values().stream().sorted(Comparator.comparingInt(FallbackPage::page))
                .limit(MAX_FALLBACK_PAGES).toList();
    }
}
