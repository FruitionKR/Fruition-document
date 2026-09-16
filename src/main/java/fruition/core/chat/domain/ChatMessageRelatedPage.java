package fruition.core.chat.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "chat_message_related_pages")
public class ChatMessageRelatedPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_message_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ChatMessage chatMessage;

    @Column(name = "wiki_page_id")
    private String wikiPageId;

    @Column(name = "page_type")
    private String pageType;

    @Column
    private String title;

    @Column
    private String slug;

    @Column(name = "relevance_score")
    private Double relevanceScore;

    @Column
    private String role;

    @Column
    private Integer depth;

    @Column
    private Integer rank;

    protected ChatMessageRelatedPage() {}

    public ChatMessageRelatedPage(ChatMessage chatMessage, String wikiPageId, String pageType,
                                   String title, String slug, Double relevanceScore,
                                   String role, Integer depth, Integer rank) {
        this.chatMessage = chatMessage;
        this.wikiPageId = wikiPageId;
        this.pageType = pageType;
        this.title = title;
        this.slug = slug;
        this.relevanceScore = relevanceScore;
        this.role = role;
        this.depth = depth;
        this.rank = rank;
    }

    public Long getId() { return id; }
    public String getChatMessageId() { return chatMessage.getId(); }
    public String getWikiPageId() { return wikiPageId; }
    public String getPageType() { return pageType; }
    public String getTitle() { return title; }
    public String getSlug() { return slug; }
    public Double getRelevanceScore() { return relevanceScore; }
    public String getRole() { return role; }
    public Integer getDepth() { return depth; }
    public Integer getRank() { return rank; }
}
