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

    /** 실패 코드. 응답 때 요청 언어로 문구를 다시 만든다. 코드 없이 실패했거나 이전에 저장된 행은 null이다. */
    @Column(name = "error_code", length = 64)
    private String errorCode;

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

    /** 되묻기의 이유 코드. 화면이 문장 대신 이 값으로 갈래를 정한다. 코드가 없거나 되묻기가 아니면 비어 있다. */
    @Column(name = "clarify_reason")
    private String clarifyReason;

    /** 요청 때 보기 권한만 있던 열린 문서. 그 문서에서 받은 되묻기를 안내로 바꾸는 데만 쓴다. 권한 판단에 쓰지 않는다. */
    @Column(name = "read_only_document_id")
    private String readOnlyDocumentId;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", insertable = false, updatable = false)
    private java.util.List<com.fasterxml.jackson.databind.JsonNode> progress = java.util.List.of();

    public java.util.List<com.fasterxml.jackson.databind.JsonNode> getProgress() { return progress; }

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
    public void assignRun(String runId) { this.runId = runId; }

    /** 서버가 보기 권한만 있다고 확인한 문서를 요청 시점에 새겨 둔다. */
    public void markReadOnlyDocument(String documentId) { this.readOnlyDocumentId = documentId; }

    /** 결과가 도착해 AI가 고른 갈래가 정해졌을 때 기록한다. */
    public void completeAgentTurn(String action, String content, String clarifyReason) {
        this.action = action;
        this.clarifyReason = clarifyReason;
        complete(content);
    }

    public void complete(String content) {
        this.content = content;
        this.status = "completed";
        this.errorMessage = null;
        this.errorCode = null;
    }

    /** error_message는 varchar(255)다. pipeline 오류는 그보다 길 수 있어 잘라 담는다. */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 255;

    public void fail(String errorCode, String errorMessage) {
        this.status = "failed";
        this.errorCode = errorCode;
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
    public String getClarifyReason() { return clarifyReason; }
    public String getReadOnlyDocumentId() { return readOnlyDocumentId; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getErrorMessage() { return errorMessage; }
    public String getErrorCode() { return errorCode; }
    public String getAiProvider() { return aiProvider; }
    public String getAiModel() { return aiModel; }
    public Boolean getWebSearchEnabled() { return webSearchEnabled; }
}
