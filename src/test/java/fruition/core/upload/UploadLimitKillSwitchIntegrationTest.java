package fruition.core.upload;

import fruition.TestcontainersConfiguration;
import fruition.core.authz.WorkspaceAccessGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * UPLOAD_LIMITS_ENABLED=false면 용량·하루 개수 검사를 건너뛴다. 기록은 계속 남기고 PDF 크기 상한은 유지한다.
 * 별도 Spring context(컨테이너 추가)를 띄우지 않도록 꺼진 서비스를 실제 DB에 직접 연결해 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UploadLimitKillSwitchIntegrationTest {
    static final long QUOTA = 5L * 1024 * 1024 * 1024;

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;

    @Test
    void disabledLimits_allowUploadOverQuotaAndDailyLimit_butStillRecord_andReportNullLimit() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String userId = "user_off_" + suffix;
        String workspaceId = "ws_off_" + suffix;
        jdbc.update("""
                INSERT INTO document_assets (id, workspace_id, uploaded_by, original_filename, content_type, byte_size,
                                             width, height, content_hash, storage_key, created_at)
                VALUES (?, ?, ?, 'seed.png', 'image/png', ?, 1, 1, ?, ?, now())
                """, UUID.randomUUID(), workspaceId, userId, QUOTA + 1, "0".repeat(64), "seed/" + suffix);
        jdbc.update("""
                INSERT INTO user_upload_events (user_id, workspace_id, kind, byte_size)
                SELECT ?, ?, 'document', 1 FROM generate_series(1, 200)
                """, userId, workspaceId);
        var enabled = new UploadLimitService(jdbc, true, 100L * 1024 * 1024, QUOTA, 200);
        var disabled = new UploadLimitService(jdbc, false, 100L * 1024 * 1024, QUOTA, 200);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> enabled.enforce(userId, 1, 1)))
                .isInstanceOf(UploadLimitException.class);
        transaction.executeWithoutResult(status -> {
            disabled.enforce(userId, 1, 1);
            disabled.record(userId, workspaceId, UploadLimitService.KIND_DOCUMENT, 1);
        });
        disabled.precheck(userId, Long.MAX_VALUE / 2, 1000);

        assertThat(disabled.uploadsToday(userId)).isEqualTo(201);
        assertThatThrownBy(() -> disabled.requirePdfSize(100L * 1024 * 1024 + 1))
                .isInstanceOfSatisfying(UploadLimitException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FILE_TOO_LARGE"));
        var usage = new StorageUsageController(disabled, mock(WorkspaceAccessGuard.class)).usage(workspaceId, userId);
        assertThat(usage).isEqualTo(new StorageUsageController.StorageUsageResponse(QUOTA + 1, null, "user"));
    }
}
