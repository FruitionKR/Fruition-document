package fruition.core.chat.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ChatSession session;

    @Column(name = "pair_id", nullable = false)
    private String pairId;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "error_message", length = 255)
    private String errorMessage;

    @Column(name = "ai_provider")
    private String aiProvider;

    @Column(name = "ai_model")
    private String aiModel;

    @Column(name = "web_search_enabled")
    private Boolean webSearchEnabled;

    /** Agent turn이 만든 메시지의 run ID. 승인 상태와 미리보기 본문은 이 run에서 읽는다. */
    @Column(name = "run_id")
    private String runId;

    /** AI가 고른 갈래. 화면이 무엇을 그릴지 판단한다. 결과가 와야 정해지므로 선저장 시점에는 비어 있다. */
    @Column(name = "action")
    private String action;

    protected ChatMessage() {}

    public ChatMessage(String id, ChatSession session, String pairId, String role, String content,
                       String status, Instant createdAt, String errorMessage) {
        this(id, session, pairId, role, content, status, createdAt, errorMessage, null, null);
    }

    public ChatMessage(String id, ChatSession session, String pairId, String role, String content,
                       String status, Instant createdAt, String errorMessage,
                       String aiProvider, String aiModel) {
        this(id, session, pairId, role, content, status, createdAt, errorMessage,
                aiProvider, aiModel, false);
    }

    public ChatMessage(String id, ChatSession session, String pairId, String role, String content,
                       String status, Instant createdAt, String errorMessage,
                       String aiProvider, String aiModel, boolean webSearchEnabled) {
        this.id = id;
        this.session = session;
        this.pairId = pairId;
        this.role = role;
        this.content = content;
        this.status = status;
        this.createdAt = createdAt;
        this.errorMessage = errorMessage;
        this.aiProvider = aiProvider;
        this.aiModel = aiModel;
        this.webSearchEnabled = webSearchEnabled;
    }

    /** Agent turn 요청 시점에 어느 run이 이 메시지를 채울지 새겨 둔다. */
    public void assignAgentRun(String runId) { this.runId = runId; }

    /** 결과가 도착해 AI가 고른 갈래가 정해졌을 때 기록한다. */
    public void completeAgentTurn(String action, String content) {
        this.action = action;
        complete(content);
    }

    public void complete(String content) {
        this.content = content;
        this.status = "completed";
        this.errorMessage = null;
    }

    /** error_message는 varchar(255)다. pipeline 오류는 그보다 길 수 있어 잘라 담는다. */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 255;

    public void fail(String errorMessage) {
        this.status = "failed";
        this.errorMessage = errorMessage == null || errorMessage.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? errorMessage
                : errorMessage.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    public String getId() { return id; }
    public String getSessionId() { return session.getId(); }
    public ChatSession getSession() { return session; }
    public String getPairId() { return pairId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getRunId() { return runId; }
    public String getAction() { return action; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getErrorMessage() { return errorMessage; }
    public String getAiProvider() { return aiProvider; }
    public String getAiModel() { return aiModel; }
    public Boolean getWebSearchEnabled() { return webSearchEnabled; }
}
