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

    @Test
    void audioAndTtsNeedTheirOwnPrices() {
        assertThat(UsagePricing.callCostUsd(PRICE, 0, 0, 0, 0, new BigDecimal("30"), 0)).isNull();
        assertThat(UsagePricing.callCostUsd(PRICE, 0, 0, 0, 0, null, 10)).isNull();

        var audio = new UsagePricing.Price(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.006"), new BigDecimal("15"));
        // 30초 × 0.006 / 60 + 1,000자 × 15 / 1M
        assertThat(UsagePricing.callCostUsd(audio, 0, 0, 0, 0, new BigDecimal("30"), 1000))
                .isEqualByComparingTo("0.018");
        // 오디오 사용량이 없으면 오디오 단가가 없어도 토큰만으로 계산한다.
        assertThat(UsagePricing.callCostUsd(PRICE, 1000, 0, 0, 0, BigDecimal.ZERO, 0)).isEqualByComparingTo("0.001");
    }
}
