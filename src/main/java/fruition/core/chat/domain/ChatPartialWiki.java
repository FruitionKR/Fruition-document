package fruition.core.chat.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * partial 발췌 export의 "문답(pair) ↔ 위키 페이지" 멤버십.
 *
 * 한 문답이 서로 다른 발췌 페이지에 여러 번 포함될 수 있으므로(1:N), full 전용인 {@code chat_messages.wiki_page_id}(1:1)와
 * 별도로 여기에 기록한다. full 편입은 여기에 넣지 않는다(= 다음 full export의 "이미 편입됨" 제외 필터를 오염시키지 않기 위함).
 */
@Entity
@Table(name = "chat_partial_wiki",
        uniqueConstraints = @UniqueConstraint(columnNames = {"pair_id", "wiki_page_id"}))
public class ChatPartialWiki {

    @Id
    private String id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private String workspaceId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "pair_id", nullable = false)
    private String pairId;

    @Column(name = "wiki_page_id", nullable = false)
    private String wikiPageId;

    @Column(name = "document_id", nullable = false)
    private String documentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChatPartialWiki() {}

    public ChatPartialWiki(String id, String workspaceId, String sessionId, String pairId,
                           String wikiPageId, String documentId, Instant createdAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.sessionId = sessionId;
        this.pairId = pairId;
        this.wikiPageId = wikiPageId;
        this.documentId = documentId;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getWorkspaceId() { return workspaceId; }
    public String getSessionId() { return sessionId; }
    public String getPairId() { return pairId; }
    public String getWikiPageId() { return wikiPageId; }
    public String getDocumentId() { return documentId; }
    public Instant getCreatedAt() { return createdAt; }
}
