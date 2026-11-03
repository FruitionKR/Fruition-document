package fruition.core.document.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversionReportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ConversionReport parse(String payload) throws Exception {
        return ConversionReport.fromBatchPayload(mapper.readTree(payload));
    }

    @Test
    @DisplayName("두 선택 객체가 없으면 null이다")
    void absentFieldsAreNull() throws Exception {
        assertThat(parse("{\"page_start\":1}")).isNull();
        assertThat(parse("{\"restoration_summary\":null,\"figure_descriptions\":\"x\"}")).isNull();
    }

    @Test
    @DisplayName("복원 요약을 읽고 모르는 사유·잘못된 쪽은 정리한다")
    void parsesRestorationSummary() throws Exception {
        ConversionReport report = parse("""
                {"restoration_summary":{"broken_pages":3,"repaired_pages":1,"fallback_pages":[
                  {"page":7,"reason":"repair_rejected","causes":["font_mapping","repair_rejected","odd"]},{"page":2,"reason":"font_mapping"},
                  {"page":0,"reason":"font_mapping"},{"page":9,"reason":"surprise"}]}}
                """);

        assertThat(report.figureDescriptions()).isNull();
        assertThat(report.restorationSummary().brokenPages()).isEqualTo(3);
        assertThat(report.restorationSummary().repairedPages()).isEqualTo(1);
        assertThat(report.restorationSummary().fallbackPages()).containsExactly(
                new ConversionReport.FallbackPage(2, "font_mapping"),
                new ConversionReport.FallbackPage(7, "repair_rejected", List.of("font_mapping", "repair_rejected", "unknown")),
                new ConversionReport.FallbackPage(9, "unknown"));
    }

    @Test
    @DisplayName("batch 집계는 순서와 상관없이 더하고 같은 쪽은 한 번만 남긴다")
    void mergesBatches() throws Exception {
        ConversionReport first = parse("""
                {"restoration_summary":{"broken_pages":2,"repaired_pages":1,"fallback_pages":[{"page":3,"reason":"extract_failed"}]},
                 "figure_descriptions":{"requested":true,"described":3,"failed":1,"skipped":0}}
                """);
        ConversionReport second = parse("""
                {"restoration_summary":{"broken_pages":1,"repaired_pages":0,"fallback_pages":[{"page":12,"reason":"provider_error"},{"page":3,"reason":"repair_rejected"}]},
                 "figure_descriptions":{"requested":true,"described":1,"failed":0,"skipped":1}}
                """);

        ConversionReport merged = ConversionReport.merge(ConversionReport.merge(null, second), first);

        assertThat(merged.restorationSummary().brokenPages()).isEqualTo(3);
        assertThat(merged.restorationSummary().fallbackPages()).extracting(ConversionReport.FallbackPage::page)
                .containsExactly(3, 12);
        assertThat(merged.figureDescriptions()).isEqualTo(new ConversionReport.FigureDescriptions(true, 4, 1, 1));
        assertThat(merged.fallbackPageCount()).isEqualTo(2);
        assertThat(merged.fallbackReasonCounts()).containsOnlyKeys("provider_error", "repair_rejected");
        assertThat(ConversionReport.merge(merged, null)).isSameAs(merged);
    }

    @Test
    @DisplayName("직렬화는 snake_case이며 없는 객체는 키를 뺀다")
    @SuppressWarnings("unchecked")
    void serializesSnakeCase() throws Exception {
        ConversionReport report = new ConversionReport(new ConversionReport.RestorationSummary(1, 0,
                List.of(new ConversionReport.FallbackPage(4, "font_mapping"))), null);

        Map<String, Object> json = mapper.convertValue(report, new com.fasterxml.jackson.core.type.TypeReference<>() {});

        assertThat(json.keySet()).containsExactly("restoration_summary");
        assertThat(((Map<String, ?>) json.get("restoration_summary")).keySet())
                .containsExactlyInAnyOrder("broken_pages", "repaired_pages", "fallback_pages");
        assertThat(mapper.readValue(mapper.writeValueAsString(report), ConversionReport.class)).isEqualTo(report);
    }
}
