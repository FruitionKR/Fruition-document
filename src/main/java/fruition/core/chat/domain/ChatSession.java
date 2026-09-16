package fruition.core.chat.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "chat_sessions")
public class ChatSession {

    @Id
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    private String title;

    @Column(name = "context_summary", columnDefinition = "TEXT")
    private String contextSummary;

    @Column(name = "context_summary_updated_at")
    private Instant contextSummaryUpdatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    /** 제목을 주지 않고 만든 세션의 기본 제목. 화면이 쓰던 표시값과 같게 맞춘다. */
    public static final String DEFAULT_TITLE = "새 채팅";

    protected ChatSession() {}

    public ChatSession(String id, String workspaceId, String userId, String title) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.userId = userId;
        // 제목이 비면 여기서 채운다. 비운 채 두면 세션 ID가 파생 이름(위키화 문서명 등)으로 새어 나간다.
        this.title = (title == null || title.isBlank()) ? DEFAULT_TITLE : title;
        this.createdAt = Instant.now();
        this.lastMessageAt = this.createdAt;
    }

    /** 제목만 바꾼다. 목록 정렬 기준인 lastMessageAt은 건드리지 않는다. */
    public void rename(String title) {
        this.title = title;
    }

    public void touchLastMessageAt(Instant now) {
        this.lastMessageAt = now;
    }

    /**
     * pipeline이 턴마다 갱신해 돌려준 누적 대화 요약을 남긴다.
     * 다음 턴은 원문 대신 이 요약을 맥락으로 읽어, 대화가 길어져도 앞부분이 잘리지 않는다.
     */
    public void updateContextSummary(String summary, Instant now) {
        this.contextSummary = summary;
        this.contextSummaryUpdatedAt = now;
    }

    public String getId() { return id; }
    public String getWorkspaceId() { return workspaceId; }
    public String getUserId() { return userId; }
    public String getTitle() { return title; }
    public String getContextSummary() { return contextSummary; }
    public Instant getContextSummaryUpdatedAt() { return contextSummaryUpdatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastMessageAt() { return lastMessageAt; }
}
