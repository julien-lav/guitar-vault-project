package com.guitarvault;

import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(name = "shop_messages", indexes = @Index(name = "idx_shop_message", columnList = "conversation_id,id"))
public class ShopMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false, updatable = false) private ShopConversation conversation;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false, updatable = false) private User author;
    @Column(nullable = false, updatable = false, length = 20) private String kind;
    @Column(nullable = false, updatable = false, length = 2000) private String body;
    @Column(updatable = false) private Instant proposedAt;
    @Column(updatable = false, length = 80) private String timeZone;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    protected ShopMessage() { }
    ShopMessage(ShopConversation conversation, User author, String kind, String body) {
        this.conversation = conversation; this.author = author; this.kind = kind; this.body = body;
        if (kind.equals("PROPOSAL")) { proposedAt = conversation.getProposedAt(); timeZone = conversation.getTimeZone(); }
    }
    public Long getId() { return id; }
    public User getAuthor() { return author; }
    public String getKind() { return kind; }
    public String getBody() { return body; }
    public Instant getProposedAt() { return proposedAt; }
    public String getTimeZone() { return timeZone; }
    public LocalDateTime getProposedLocal() { return proposedAt == null ? null : LocalDateTime.ofInstant(proposedAt, ZoneId.of(timeZone)); }
    public Instant getCreatedAt() { return createdAt; }
    public LocalDateTime getCreatedDateTime() { return LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC); }
}
