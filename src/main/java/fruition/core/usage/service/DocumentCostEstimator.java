package fruition.core.usage.service;

import fruition.core.authz.WorkspaceAiModelClient.AiModelSelection;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** Uses the same prices, FX and markup as settlement; historical completed runs calibrate the token forecast. */
@Component
public class DocumentCostEstimator {
    private final JdbcTemplate jdbc;
    private final RestClient client;
    private final String endpoint;
    public DocumentCostEstimator(JdbcTemplate jdbc, PipelineClientFactory factory,
            @Value("${app.agent.status-endpoint}") String agentEndpoint) {
        this.jdbc = jdbc;
        this.client = factory.restClient(60);
        this.endpoint = java.net.URI.create(agentEndpoint).resolve("/internal/documents/pdf-cost-features").toString();
    }
    public record PdfFeatures(long pages, long text_bytes, long images, long scanned_pages) {
        public PdfFeatures {
            if (pages <= 0 || text_bytes < 0 || images < 0 || scanned_pages < 0 || scanned_pages > pages)
                throw new IllegalArgumentException("Invalid PDF cost features");
        }
        public long markdownBytes() { return Math.addExact(text_bytes, Math.multiplyExact(scanned_pages, 6000)); }
    }
    public record Quote(long base, long amount, String kind, String provider, String model) {}

    /** 암호화·손상 등으로 구조를 읽을 수 없는 PDF. 다시 시도해도 같아 변환 실패(input_rejected)로 기록한다. */
    public static class UnreadablePdfException extends RuntimeException {
        public UnreadablePdfException() { super("PDF structure could not be read"); }
    }

    /** 저장소·AI 오류는 그대로 오류로 둔다. 0원 견적으로 바꾸지 않는다. */
    public PdfFeatures inspect(String sourceKey) {
        PdfFeatures result;
        try {
            result = client.post().uri(endpoint).body(Map.of("source_key", sourceKey)).retrieve().body(PdfFeatures.class);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 422) throw new UnreadablePdfException();
            if (e.getStatusCode().value() == 413)
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "PDF가 너무 커서 비용을 확인할 수 없습니다.");
            if (e.getStatusCode().value() == 503)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PDF 확인 요청이 몰려 있습니다. 잠시 후 다시 시도해 주세요.");
            throw e;
        }
        if (result == null) throw new IllegalStateException("PDF cost inspection returned no result");
        return result;
    }

    public Quote document(long bytes, AiModelSelection model) { return rate("document", model).document(bytes); }
    public Quote conversion(PdfFeatures pdf, boolean figures, AiModelSelection model) {
        return rate("convert", model).conversion(pdf, figures);
    }

    /** 한 kind·모델의 단가·환율·정책·보정 계수. 여러 문서를 견적할 때 한 번만 읽는다. */
    public Rate rate(String kind, AiModelSelection model) {
        var prices = jdbc.query("SELECT * FROM ai_model_prices WHERE provider=? AND model=? AND effective_from<=now() "
                + "ORDER BY effective_from DESC LIMIT 1", (rs, n) -> UsagePricing.Price.from(rs), model.provider(), model.model());
        if (prices.isEmpty()) throw new IllegalStateException("Cannot estimate a model without a price");
        var fx = jdbc.queryForObject("SELECT krw_per_usd FROM fx_rates WHERE effective_from<=now() ORDER BY effective_from DESC LIMIT 1", BigDecimal.class);
        var policy = jdbc.queryForMap("SELECT * FROM pricing_policies WHERE effective_from<=now() ORDER BY effective_from DESC LIMIT 1");
        var samples = jdbc.queryForList("""
                SELECT sum(c.charge_krw_milli)::numeric / r.base AS ratio
                FROM (SELECT id, (cost_quote->>'base')::numeric AS base, cost_quote->>'post_run' AS post_run
                      FROM ai_task_runs WHERE status='completed' AND updated_at < now() - interval '1 day' AND kind=?
                      AND cost_quote->>'provider'=? AND cost_quote->>'model'=?
                      AND (cost_quote->>'base')::numeric > 0 ORDER BY updated_at DESC LIMIT 100) r
                JOIN usage_charges c ON c.run_id=r.id OR c.run_id=r.post_run
                GROUP BY r.id, r.base
                HAVING bool_and(c.status='charged') AND sum(c.charge_krw_milli)>0
                """, BigDecimal.class, kind, model.provider(), model.model());
        BigDecimal factor = new BigDecimal("1.25");
        if (samples.size() >= 5) {
            samples.sort(BigDecimal::compareTo);
            factor = samples.get((int)Math.ceil(samples.size() * 0.8) - 1).multiply(new BigDecimal("1.15"));
        }
        return new Rate(kind, model, prices.getFirst(), fx, ((Number)policy.get("fx_buffer_bp")).intValue(),
                ((Number)policy.get("margin_bp")).intValue(), ((Number)policy.get("vat_bp")).intValue(), factor);
    }

    public record Rate(String kind, AiModelSelection model, UsagePricing.Price price, BigDecimal fx,
                       int fxBufferBp, int marginBp, int vatBp, BigDecimal factor) {
        public Quote document(long bytes) {
            if (bytes < 0) throw new IllegalArgumentException("Invalid document size");
            // shortcut: cold-start token forecast; completed runs replace this bias once five samples exist.
            return quote(Math.addExact(6000, Math.multiplyExact(bytes, 3)), Math.addExact(1000, bytes));
        }
        public Quote conversion(PdfFeatures pdf, boolean figures) {
            long imageUnits = Math.addExact(pdf.scanned_pages(), figures ? pdf.images() : 0);
            long input = Math.addExact(Math.multiplyExact(pdf.pages(), 1000),
                    Math.addExact(pdf.text_bytes(), Math.multiplyExact(imageUnits, 2000)));
            long output = Math.addExact(pdf.markdownBytes() / 3, Math.multiplyExact(figures ? pdf.images() : 0, 400));
            return quote(input, output);
        }
        private Quote quote(long input, long output) {
            long base = Math.max(1, UsagePricing.chargeKrwMilli(UsagePricing.tokenCostUsd(price, input, 0, 0, output), fx,
                    fxBufferBp, marginBp, vatBp));
            long amount = BigDecimal.valueOf(base).multiply(factor).setScale(0, RoundingMode.CEILING).longValueExact();
            return new Quote(base, Math.max(1, amount), kind, model.provider(), model.model());
        }
    }
}
