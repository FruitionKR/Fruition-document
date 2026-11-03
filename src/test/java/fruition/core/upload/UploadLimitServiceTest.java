package fruition.core.upload;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class UploadLimitServiceTest {
    static final long MB = 1024L * 1024;

    @Test
    void pdfCapAllowsBoundaryAndRejectsOneByteOver() {
        var limits = new UploadLimitService(mock(JdbcTemplate.class), true, 100 * MB, 5 * 1024 * MB);
        limits.requirePdfSize(100 * MB);
        assertThatThrownBy(() -> limits.requirePdfSize(100 * MB + 1))
                .isInstanceOfSatisfying(UploadLimitException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(e.getCode()).isEqualTo("FILE_TOO_LARGE");
                    assertThat(e.getMessage()).isEqualTo("PDF 파일은 100MB 이하로 올려 주세요.");
                });
    }

    @Test
    void disabledLimitsSkipDatabaseChecks() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new UploadLimitService(jdbc, false, 100 * MB, 1).precheck("user", Long.MAX_VALUE);
        verifyNoInteractions(jdbc);
    }

    @Test
    void formatsConfiguredSizesForMessages() {
        assertThat(UploadLimitService.formatBytes(5L * 1024 * MB)).isEqualTo("5GB");
        assertThat(UploadLimitService.formatBytes(100 * MB)).isEqualTo("100MB");
        assertThat(UploadLimitService.formatBytes(1536 * 1024)).isEqualTo("1.5MB");
        assertThat(UploadLimitService.formatBytes(10)).isEqualTo("10B");
    }
}
