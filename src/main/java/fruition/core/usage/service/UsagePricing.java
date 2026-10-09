package fruition.core.usage.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;

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

    /** 호출 원가(USD). 오디오·TTS 사용량이 있는데 그 단가가 없으면 계산할 수 없어 null이다. */
    public static BigDecimal callCostUsd(Price price, long input, long cached, long creation, long output,
                                         BigDecimal audioSeconds, long ttsCharacters) {
        BigDecimal cost = tokenCostUsd(price, input, cached, creation, output);
        if (audioSeconds != null && audioSeconds.signum() > 0) {
            if (price.audioPerMinute() == null) return null;
            cost = cost.add(audioSeconds.multiply(price.audioPerMinute()).divide(BigDecimal.valueOf(60), 12, RoundingMode.CEILING));
        }
        if (ttsCharacters > 0) {
            if (price.ttsPerMchar() == null) return null;
            cost = cost.add(BigDecimal.valueOf(ttsCharacters).multiply(price.ttsPerMchar()).movePointLeft(6));
        }
        return cost;
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
