package fruition.core.usage.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class UsagePricingTest {

    private static final UsagePricing.Price PRICE = new UsagePricing.Price(new BigDecimal("1"), new BigDecimal("2"),
            new BigDecimal("0.1"), new BigDecimal("1.25"), null, null);

    @Test
    void cacheTokensUseOwnPricesAndReasoningIsNotChargedTwice() {
        // 입력 1,000 = 일반 500 + 캐시 읽기 400 + 캐시 생성 100. 출력 500에는 reasoning이 들어 있어 따로 받지 않는다.
        BigDecimal cost = UsagePricing.tokenCostUsd(PRICE, 1000, 400, 100, 500);

        // 500×1 + 400×0.1 + 100×1.25 + 500×2 = 1,665 / 1M
        assertThat(cost).isEqualByComparingTo("0.001665");
        assertThat(UsagePricing.costUsdMicro(cost)).isEqualTo(1665);
    }

    @Test
    void chargeAppliesFxMarginAndVatAndRoundsUp() {
        // 0.000001 USD × 1,400 KRW × 1.3 × 1.1 = 0.002002 KRW = 2.002 milli-KRW → 3
        assertThat(UsagePricing.chargeKrwMilli(new BigDecimal("0.000001"), new BigDecimal("1400"), 3000, 1000))
                .isEqualTo(3);
        // 0.001665 USD × 1,400 × 1.3 × 1.1 = 3.333330 KRW
        assertThat(UsagePricing.chargeKrwMilli(new BigDecimal("0.001665"), new BigDecimal("1400"), 3000, 1000))
                .isEqualTo(3334);
        assertThat(UsagePricing.chargeKrwMilli(BigDecimal.ZERO, new BigDecimal("1400"), 3000, 1000)).isZero();
    }

    /** 분당 과금 모델: 토큰 단가 열은 NOT NULL이라 0이다. */
    private static final UsagePricing.Price PER_MINUTE = new UsagePricing.Price(BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.006"), null);
    private static final UsagePricing.Price PER_MCHAR = new UsagePricing.Price(new BigDecimal("1"), new BigDecimal("2"),
            new BigDecimal("0.1"), new BigDecimal("1.25"), null, new BigDecimal("15"));

    @Test
    void priceRowWithoutAudioOrTtsUnitChargesTokensOnly() {
        // 토큰과 TTS 글자 수가 함께 있고 글자 단가가 없으면 토큰 비용만. unpriced가 아니다.
        assertThat(UsagePricing.callCostUsd(PRICE, 1000L, null, null, 0L, null, 10_000L)).isEqualByComparingTo("0.001");
        // 토큰과 오디오 길이가 함께 있고 분당 단가가 없으면 토큰 비용만.
        assertThat(UsagePricing.callCostUsd(PRICE, 0L, null, null, 500L, new BigDecimal("30"), null))
                .isEqualByComparingTo("0.001");
    }

    @Test
    void priceRowUnitWinsOverTokensWithoutAddingThem() {
        // 토큰 단가 0 + 분당 단가, 토큰과 오디오 길이가 함께 기록됨 → 분당 비용만(0원이 아니다). 30초 × 0.006 / 60
        assertThat(UsagePricing.callCostUsd(PER_MINUTE, 1000L, null, null, 500L, new BigDecimal("30"), null))
                .isEqualByComparingTo("0.003");
        // 글자 단가가 있는 행은 토큰이 함께 있어도 글자 비용만. 1,000자 × 15 / 1M
        assertThat(UsagePricing.callCostUsd(PER_MCHAR, 1000L, null, null, 500L, null, 1000L))
                .isEqualByComparingTo("0.015");
    }

    @Test
    void withoutTokensAudioAndTtsNeedTheirOwnPrices() {
        assertThat(UsagePricing.callCostUsd(PER_MINUTE, null, null, null, null, new BigDecimal("30"), null))
                .isEqualByComparingTo("0.003");
        assertThat(UsagePricing.callCostUsd(PER_MCHAR, null, null, null, null, null, 1000L)).isEqualByComparingTo("0.015");
        // 그 단가가 없으면 계산할 수 없다.
        assertThat(UsagePricing.callCostUsd(PRICE, null, null, null, null, new BigDecimal("30"), null)).isNull();
        assertThat(UsagePricing.callCostUsd(PRICE, null, null, null, null, null, 10L)).isNull();
    }

    @Test
    void routingSeparatesSnapshotsFromOtherModels() {
        assertThat(UsagePricing.routing("gpt-5-nano", "gpt-5-nano")).isEqualTo("same");
        assertThat(UsagePricing.routing("gpt-5-nano", "gpt-5-nano-2025-08-07")).isEqualTo("snapshot");
        assertThat(UsagePricing.routing("claude-haiku-4-5", "claude-haiku-4-5-20251001")).isEqualTo("snapshot");
        assertThat(UsagePricing.routing("gemini-2.0-flash", "gemini-2.0-flash-001")).isEqualTo("snapshot");
        assertThat(UsagePricing.routing("gemini-3.8-flash", "gemini-3.8-flash-latest")).isEqualTo("snapshot");
        assertThat(UsagePricing.routing("gpt-5.4", "gpt-5.4-mini")).isEqualTo("routed");
        assertThat(UsagePricing.routing("gemini-3.7-flash", "gemini-3.8-flash")).isEqualTo("routed");
        assertThat(UsagePricing.routing(null, "gpt-5.4")).isNull();
    }
}
