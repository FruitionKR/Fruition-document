package fruition.core.usage.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * 모델 단가로 AI 사용 금액을 계산하는 순수 함수(#78). 기간 정산(#59)과 호출 단위 청구가 같은 식을 쓴다.
 *
 * <ul>
 *   <li>입력 토큰은 캐시 읽기·생성분을 포함한 합계라(LangChain usage_metadata) 일반 입력은 그 둘을 뺀다.</li>
 *   <li>reasoning 토큰은 출력에 포함돼 따로 과금하지 않는다.</li>
 *   <li>사용자 금액 = 원가 × 환율 × (1 + 마진) × (1 + 부가세), milli-KRW로 올림한다.</li>
 * </ul>
 */
public final class UsagePricing {

    private UsagePricing() {
    }

    /** {@code ai_model_prices} 한 행. 토큰은 USD / 1M tokens, 오디오는 USD / 분, TTS는 USD / 1M 글자다. */
    public record Price(BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheWrite,
                        BigDecimal audioPerMinute, BigDecimal ttsPerMchar) {
        static Price from(ResultSet rs) throws SQLException {
            return new Price(rs.getBigDecimal("input_usd_per_mtok"), rs.getBigDecimal("output_usd_per_mtok"),
                    rs.getBigDecimal("cache_read_usd_per_mtok"), rs.getBigDecimal("cache_write_usd_per_mtok"),
                    rs.getBigDecimal("audio_usd_per_minute"), rs.getBigDecimal("tts_usd_per_mchar"));
        }
    }

    /** 토큰 원가(USD). 반올림하지 않는다. */
    public static BigDecimal tokenCostUsd(Price price, long input, long cached, long creation, long output) {
        long plainInput = Math.max(0, input - cached - creation);
        return BigDecimal.valueOf(plainInput).multiply(price.input())
                .add(BigDecimal.valueOf(cached).multiply(price.cacheRead()))
                .add(BigDecimal.valueOf(creation).multiply(price.cacheWrite()))
                .add(BigDecimal.valueOf(output).multiply(price.output()))
                .movePointLeft(6);
    }

    /**
     * 호출 원가(USD). 토큰 우선 규칙(#93): 입력·출력 토큰 중 하나라도 있으면 토큰 단가로만 계산하고 오디오 길이·TTS 글자 수는
     * 무시한다(저장만 한다). ai는 실시간 전사·TTS에 토큰과 오디오 길이·글자 수를 함께 남기므로 더하면 이중 청구가 된다.
     * 토큰이 없을 때만 오디오 분당·TTS 100만 글자당 단가를 쓰고, 그 단가가 없으면 계산할 수 없어 null이다.
     */
    public static BigDecimal callCostUsd(Price price, Long input, Long cached, Long creation, Long output,
                                         BigDecimal audioSeconds, Long ttsCharacters) {
        if (input != null || output != null) {
            return tokenCostUsd(price, zero(input), zero(cached), zero(creation), zero(output));
        }
        BigDecimal cost = BigDecimal.ZERO;
        if (audioSeconds != null && audioSeconds.signum() > 0) {
            if (price.audioPerMinute() == null) return null;
            cost = cost.add(audioSeconds.multiply(price.audioPerMinute()).divide(BigDecimal.valueOf(60), 12, RoundingMode.CEILING));
        }
        if (ttsCharacters != null && ttsCharacters > 0) {
            if (price.ttsPerMchar() == null) return null;
            cost = cost.add(BigDecimal.valueOf(ttsCharacters).multiply(price.ttsPerMchar()).movePointLeft(6));
        }
        return cost;
    }

    private static long zero(Long value) {
        return value == null ? 0 : value;
    }

    /**
     * 공급사가 붙이는 스냅샷·버전 접미사. 2026-10-10 개발 DB ai 원장에서 확인한 실제 값은 OpenAI 날짜
     * ({@code gpt-5-nano} → {@code gpt-5-nano-2025-08-07})뿐이다. 나머지는 공급사 모델 이름 관례다.
     * Anthropic {@code -20251001}, Gemini {@code -001}, 별칭 {@code -latest}.
     */
    private static final Pattern SNAPSHOT_SUFFIX = Pattern.compile("\\d{4}-\\d{2}-\\d{2}|\\d{8}|\\d{3}|latest");

    /**
     * 요청 모델과 실제 응답 모델의 관계(#99). 같으면 {@code same}, 요청 모델에 스냅샷 접미사만 붙었으면 {@code snapshot},
     * 그 밖은 공급사가 다른 모델로 보낸 것으로 보고 {@code routed}다. 둘 중 하나를 모르면 null이다.
     */
    public static String routing(String requested, String actual) {
        if (requested == null || actual == null) return null;
        if (actual.equals(requested)) return "same";
        if (actual.startsWith(requested + "-")
                && SNAPSHOT_SUFFIX.matcher(actual.substring(requested.length() + 1)).matches()) return "snapshot";
        return "routed";
    }

    /** 원가를 micro-USD 정수로 올림한다. */
    public static long costUsdMicro(BigDecimal costUsd) {
        return costUsd.movePointRight(6).setScale(0, RoundingMode.CEILING).longValueExact();
    }

    /** 사용자 금액(milli-KRW). 반올림 오차가 쌓이지 않게 올림 전 원가로 한 번에 계산한다. */
    public static long chargeKrwMilli(BigDecimal costUsd, BigDecimal krwPerUsd, int marginBp, int vatBp) {
        return costUsd.multiply(krwPerUsd)
                .multiply(BigDecimal.valueOf(10_000L + marginBp))
                .multiply(BigDecimal.valueOf(10_000L + vatBp))
                .movePointLeft(8 - 3)
                .setScale(0, RoundingMode.CEILING).longValueExact();
    }
}
