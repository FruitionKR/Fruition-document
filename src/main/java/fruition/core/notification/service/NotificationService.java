package fruition.core.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.notification.dto.NotificationListResponse;
import fruition.core.notification.exception.NotificationNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 앱 안 알림. 알림을 만드는 쪽의 트랜잭션에서 한 행을 넣고, 사용자는 목록에서 읽음 처리한다.
 *
 * <p>OWNER 대상 알림은 OWNER 목록을 알 수 없어 한 행만 넣고, 조회할 때 요청자가 지금 OWNER인지로 거른다.
 * 그래서 나중에 OWNER가 된 사람도 이전 OWNER 대상 알림을 본다.
 */
@Service
public class NotificationService {

    public static final String TYPE_EDIT_CONFLICT_REGISTERED = "edit_conflict_registered";
    public static final String TYPE_EDIT_CONFLICT_RESOLVED = "edit_conflict_resolved";

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    /** 파라미터: workspace_id, user_id, is_owner */
    private static final String VISIBLE =
            " n.workspace_id = ? AND (n.recipient_user_id = ? OR (n.audience = 'workspace_owners' AND ?))";

    /** 파라미터: user_id + VISIBLE */
    private static final String READ_INSERT =
            "INSERT INTO notification_reads(notification_id, user_id) SELECT n.id, ? FROM notifications n WHERE" + VISIBLE;

    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public NotificationService(WorkspaceAccessGuard workspaceAccessGuard, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 호출한 쪽 트랜잭션에 참여한다. */
    public void notifyUser(String workspaceId, String recipientUserId, String type, Map<String, Object> payload) {
        insert(workspaceId, recipientUserId, "user", type, payload);
    }

    /** 호출한 쪽 트랜잭션에 참여한다. */
    public void notifyWorkspaceOwners(String workspaceId, String type, Map<String, Object> payload) {
        insert(workspaceId, null, "workspace_owners", type, payload);
    }

    @Transactional(readOnly = true)
    public NotificationListResponse list(String workspaceId, String userId, boolean unreadOnly, Integer limit,
                                         Instant before) {
        boolean owner = requireMember(workspaceId, userId);
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        List<Object> args = new ArrayList<>(List.of(userId, workspaceId, userId, owner));
        StringBuilder sql = new StringBuilder("""
                SELECT n.id, n.type, n.payload::text AS payload, n.created_at, r.user_id IS NOT NULL AS read
                FROM notifications n
                LEFT JOIN notification_reads r ON r.notification_id = n.id AND r.user_id = ?
                WHERE""").append(VISIBLE);
        if (unreadOnly) {
            sql.append(" AND r.user_id IS NULL");
        }
        if (before != null) {
            sql.append(" AND n.created_at < ?");
            args.add(Timestamp.from(before));
        }
        sql.append(" ORDER BY n.created_at DESC, n.id DESC LIMIT ?");
        args.add(size);
        return new NotificationListResponse(jdbc.query(sql.toString(), (rs, rowNum) -> {
            try {
                return new NotificationListResponse.Notification(
                        rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        objectMapper.readTree(rs.getString("payload")),
                        rs.getBoolean("read"),
                        rs.getTimestamp("created_at").toInstant());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("알림 payload를 읽을 수 없습니다.", e);
            }
        }, args.toArray()));
    }

    /** 이미 읽었어도 성공한다. 내가 볼 수 없는 알림이면 404. */
    @Transactional
    public void markRead(String workspaceId, String userId, UUID notificationId) {
        boolean owner = requireMember(workspaceId, userId);
        int inserted = jdbc.update(READ_INSERT + " AND n.id = ? ON CONFLICT DO NOTHING",
                userId, workspaceId, userId, owner, notificationId);
        if (inserted == 0 && !visible(workspaceId, userId, owner, notificationId)) {
            throw new NotificationNotFoundException();
        }
    }

    @Transactional
    public void markAllRead(String workspaceId, String userId) {
        boolean owner = requireMember(workspaceId, userId);
        jdbc.update(READ_INSERT + " ON CONFLICT DO NOTHING", userId, workspaceId, userId, owner);
    }

    private boolean visible(String workspaceId, String userId, boolean owner, UUID notificationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM notifications n WHERE n.id = ? AND" + VISIBLE + ")",
                Boolean.class, notificationId, workspaceId, userId, owner));
    }

    private boolean requireMember(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        return workspaceAccessGuard.isOwner(workspaceId, userId);
    }

    private void insert(String workspaceId, String recipientUserId, String audience, String type,
                        Map<String, Object> payload) {
        try {
            jdbc.update("""
                    INSERT INTO notifications(id, workspace_id, recipient_user_id, audience, type, payload)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """, UUID.randomUUID(), workspaceId, recipientUserId, audience, type,
                    objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("알림 payload를 만들 수 없습니다.", e);
        }
    }
}
