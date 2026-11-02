package fruition.core.upload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자별 업로드 한도: 원본 PDF 크기, 저장 용량 합계, 하루(Asia/Seoul) 업로드 개수.
 *
 * <p>저장 용량은 사용자가 올린 문서({@code documents.user_id})·이미지({@code document_assets.uploaded_by})·
 * 회의 녹음({@code meetings.created_by})의 바이트 합이다. 휴지통 문서는 영구 삭제(보관 기간 뒤 purge)될 때까지
 * object storage를 차지하므로 포함한다.
 *
 * <p>동시성: {@link #enforce}는 사용자별 트랜잭션 advisory lock을 잡은 뒤 사용량을 읽는다. 호출자는 같은 트랜잭션에서
 * 행을 쓰고 커밋하므로, 같은 사용자의 병렬 업로드는 이 지점에서 직렬화되어 함께 한도를 넘지 못한다.
 * 직접 업로드 시작 때·원본 저장 전의 {@link #precheck}는 잠금 없이 일찍 거절하기 위한 것이고, {@link #enforce}가 최종 판단한다.
 * {@link #enforce}는 object storage 쓰기가 끝난 뒤 행을 쓰기 직전에 불러, 큰 파일을 쓰는 동안 잠금을 쥐지 않게 한다.
 * 거절되면 호출자가 방금 저장한 객체를 지운다.
 */
@Service
public class UploadLimitService {
    public static final String KIND_DOCUMENT = "document";
    public static final String KIND_DOCUMENT_ASSET = "document_asset";
    public static final String KIND_MEETING_RECORDING = "meeting_recording";

    private static final String TODAY_START_SQL =
            "(date_trunc('day', now() AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul')";

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final long maxPdfBytes;
    private final long userQuotaBytes;
    private final int dailyFileLimit;

    public UploadLimitService(JdbcTemplate jdbc,
                              @Value("${app.upload.limits-enabled:true}") boolean enabled,
                              @Value("${app.upload.max-pdf-bytes:104857600}") long maxPdfBytes,
                              @Value("${app.upload.user-quota-bytes:5368709120}") long userQuotaBytes,
                              @Value("${app.upload.daily-file-limit:200}") int dailyFileLimit) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.maxPdfBytes = maxPdfBytes;
        this.userQuotaBytes = userQuotaBytes;
        this.dailyFileLimit = dailyFileLimit;
    }

    /** 원본 PDF 한 개의 크기 상한. 한도 스위치와 무관하게 항상 검사한다. */
    public void requirePdfSize(long size) {
        if (size > maxPdfBytes) {
            throw new UploadLimitException(HttpStatus.PAYLOAD_TOO_LARGE, UploadLimitException.FILE_TOO_LARGE,
                    "PDF 파일은 " + formatBytes(maxPdfBytes) + " 이하로 올려 주세요.");
        }
    }

    /** 잠금 없이 미리 확인한다. 큰 파일을 S3에 다 올린 뒤에야 거절되지 않게 직접 업로드 시작 때 쓴다. */
    public void precheck(String userId, long additionalBytes, int files) {
        if (!enabled) return;
        check(userId, additionalBytes, files);
    }

    /**
     * 사용자 잠금을 잡고 한도를 확인한다. 반드시 업로드 행을 쓰는 트랜잭션 안에서 불러야 한다.
     *
     * @param additionalBytes 아직 기록되지 않은 새 바이트. 이미 같은 트랜잭션에서 기록했으면 0
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enforce(String userId, long additionalBytes, int files) {
        if (!enabled) return;
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "upload-limit:" + userId);
        check(userId, additionalBytes, files);
    }

    /** 하루 개수 계산용 기록을 남긴다. 한도 스위치가 꺼져 있어도 기록해 다시 켰을 때 개수가 맞게 한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String userId, String workspaceId, String kind, long bytes) {
        jdbc.update("INSERT INTO user_upload_events (user_id, workspace_id, kind, byte_size) VALUES (?, ?, ?, ?)",
                userId, workspaceId, kind, bytes);
        // 오늘 개수만 필요하므로 오래된 기록은 그 사용자가 다시 올릴 때 지운다(인덱스 범위 삭제).
        jdbc.update("DELETE FROM user_upload_events WHERE user_id = ? AND created_at < now() - interval '2 days'",
                userId);
    }

    /** 사용자 저장 용량 한도. 한도 스위치가 꺼져 있으면 null(제한 없음). */
    public Long limitBytes() {
        return enabled ? userQuotaBytes : null;
    }

    /** 사용자가 올린 문서·이미지·회의 녹음의 바이트 합. 한도 검사와 사용량 표시가 같은 계산을 쓴다. */
    public long usedBytes(String userId) {
        Long used = jdbc.queryForObject("""
                SELECT (SELECT COALESCE(SUM(byte_size), 0) FROM documents WHERE user_id = ?)
                     + (SELECT COALESCE(SUM(byte_size), 0) FROM document_assets WHERE uploaded_by = ?)
                     + (SELECT COALESCE(SUM(recording_bytes), 0) FROM meetings
                        WHERE created_by = ? AND recording_key IS NOT NULL)
                """, Long.class, userId, userId, userId);
        return used == null ? 0 : used;
    }

    /** 오늘(Asia/Seoul) 올린 파일 수. */
    public int uploadsToday(String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM user_upload_events WHERE user_id = ? AND created_at >= " + TODAY_START_SQL,
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    private void check(String userId, long additionalBytes, int files) {
        if (files > 0 && uploadsToday(userId) + files > dailyFileLimit) {
            throw new UploadLimitException(HttpStatus.TOO_MANY_REQUESTS, UploadLimitException.UPLOAD_DAILY_LIMIT_EXCEEDED,
                    "하루에 올릴 수 있는 파일 수(" + dailyFileLimit + "개)를 모두 사용했습니다. 내일 다시 시도해 주세요.");
        }
        if (usedBytes(userId) + additionalBytes > userQuotaBytes) {
            throw new UploadLimitException(HttpStatus.PAYLOAD_TOO_LARGE, UploadLimitException.STORAGE_QUOTA_EXCEEDED,
                    "내 저장 공간(" + formatBytes(userQuotaBytes) + ")이 부족합니다. 문서나 녹음을 지운 뒤 다시 시도해 주세요. "
                            + "휴지통 문서는 영구 삭제될 때까지 용량에 포함됩니다.");
        }
    }

    static String formatBytes(long bytes) {
        long mib = 1024L * 1024;
        long gib = mib * 1024;
        if (bytes >= gib && bytes % gib == 0) return bytes / gib + "GB";
        if (bytes >= mib && bytes % mib == 0) return bytes / mib + "MB";
        if (bytes >= mib) return String.format("%.1fMB", bytes / (double) mib);
        return bytes + "B";
    }
}
