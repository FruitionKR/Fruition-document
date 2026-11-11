package fruition.core.usage.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DocumentCreditEstimateTest {
    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("app.billing.estimate.document", "70000");
    private final CreditService credits = new CreditService(mock(JdbcTemplate.class),
            mock(PlatformTransactionManager.class), environment, true, 500_000, 48);

    @ParameterizedTest
    @CsvSource({"0,1", "1,13", "5537,70000", "11074,140000", "55370,700000"})
    void scalesByBytesWithoutReservingAWholeReferenceDocument(long bytes, long milliKrw) {
        assertThat(credits.estimateDocument(bytes)).isEqualTo(milliKrw);
    }

    @Test
    void rejectsInvalidSizesRatesAndOverflowInsteadOfWrappingMoney() {
        assertThatThrownBy(() -> credits.estimateDocument(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> credits.estimateDocument(Long.MAX_VALUE)).isInstanceOf(ArithmeticException.class);
        environment.setProperty("app.billing.document-reference-bytes", "0");
        assertThatThrownBy(() -> credits.estimateDocument(5537)).isInstanceOf(IllegalArgumentException.class);
    }
}
