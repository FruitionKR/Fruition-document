package fruition.core.usage.service;

import fruition.core.authz.WorkspaceAiModelClient.AiModelSelection;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
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
    public PdfFeatures inspect(String sourceKey) {
        var result = client.post().uri(endpoint).body(Map.of("source_key", sourceKey)).retrieve().body(PdfFeatures.class);
        if (result == null) throw new IllegalStateException("PDF cost inspection returned no result");
        return result;
    }
    public Quote document(long bytes, AiModelSelection model) {
        if (bytes < 0) throw new IllegalArgumentException("Invalid document size");
        // shortcut: cold-start token forecast; completed runs replace this bias once five samples exist.
        return quote("document", model, Math.addExact(6000, Math.multiplyExact(bytes, 3)), Math.addExact(1000, bytes));
    }
    public Quote conversion(PdfFeatures pdf, boolean figures, AiModelSelection model) {
        long imageUnits = Math.addExact(pdf.scanned_pages(), figures ? pdf.images() : 0);
        long input = Math.addExact(Math.multiplyExact(pdf.pages(), 1000),
                Math.addExact(pdf.text_bytes(), Math.multiplyExact(imageUnits, 2000)));
        long output = Math.addExact(pdf.markdownBytes() / 3, Math.multiplyExact(figures ? pdf.images() : 0, 400));
        return quote("convert", model, input, output);
    }
    private Quote quote(String kind, AiModelSelection model, long input, long output) {
        var prices = jdbc.query("SELECT * FROM ai_model_prices WHERE provider=? AND model=? AND effective_from<=now() "
                + "ORDER BY effective_from DESC LIMIT 1", (rs, n) -> UsagePricing.Price.from(rs), model.provider(), model.model());
        if (prices.isEmpty()) throw new IllegalStateException("Cannot estimate a model without a price");
        var fx = jdbc.queryForObject("SELECT krw_per_usd FROM fx_rates WHERE effective_from<=now() ORDER BY effective_from DESC LIMIT 1", BigDecimal.class);
        var policy = jdbc.queryForMap("SELECT * FROM pricing_policies WHERE effective_from<=now() ORDER BY effective_from DESC LIMIT 1");
        long base = Math.max(1, UsagePricing.chargeKrwMilli(UsagePricing.tokenCostUsd(prices.getFirst(), input, 0, 0, output), fx,
                ((Number)policy.get("fx_buffer_bp")).intValue(), ((Number)policy.get("margin_bp")).intValue(),
                ((Number)policy.get("vat_bp")).intValue()));
        var samples = jdbc.queryForList("""
                SELECT sum(c.charge_krw_milli)::numeric / r.base AS ratio
                FROM (SELECT id, (command->>'_cost_base')::numeric AS base, command->>'_cost_post_run' AS post_run
                      FROM ai_task_runs WHERE status='completed' AND updated_at < now() - interval '1 day' AND kind=?
                      AND command->>'_cost_provider'=? AND command->>'_cost_model'=?
                      AND (command->>'_cost_base')::numeric > 0 ORDER BY updated_at DESC LIMIT 100) r
                JOIN usage_charges c ON c.run_id=r.id OR c.run_id=r.post_run
                GROUP BY r.id, r.base
                HAVING bool_and(c.status='charged') AND sum(c.charge_krw_milli)>0
                """, BigDecimal.class, kind, model.provider(), model.model());
        BigDecimal factor = new BigDecimal("1.25");
        if (samples.size() >= 5) {
            samples.sort(BigDecimal::compareTo);
            factor = samples.get((int)Math.ceil(samples.size() * 0.8) - 1).multiply(new BigDecimal("1.15"));
        }
        long amount = BigDecimal.valueOf(base).multiply(factor).setScale(0, RoundingMode.CEILING).longValueExact();
        return new Quote(base, Math.max(1, amount), kind, model.provider(), model.model());
    }
}
