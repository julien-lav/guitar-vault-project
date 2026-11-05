package com.guitarvault;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "studio_quote_messages", indexes = @Index(name = "idx_quote_message_quote", columnList = "quote_id,id"))
public class StudioQuoteMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false, updatable = false)
    private StudioQuote quote;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false, updatable = false)
    private User author;
    @Column(nullable = false, length = 5000, updatable = false)
    private String body;
    @Column(precision = 8, scale = 2, updatable = false)
    private BigDecimal proposedAmount;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected StudioQuoteMessage() { }

    public StudioQuoteMessage(StudioQuote quote, User author, String body, BigDecimal proposedAmount) {
        this(quote, author, body, proposedAmount, Instant.now());
    }

    StudioQuoteMessage(StudioQuote quote, User author, String body, BigDecimal proposedAmount, Instant createdAt) {
        this.quote = quote;
        this.author = author;
        this.body = body;
        this.proposedAmount = proposedAmount;
        this.createdAt = createdAt;
    }

    static StudioQuoteMessage legacyReply(StudioQuote quote) {
        return new StudioQuoteMessage(quote, quote.getStudio().getCreator(), quote.getReply(),
                quote.getProposedAmount(), quote.getAnsweredAt() != null ? quote.getAnsweredAt() : quote.getCreatedAt());
    }

    public Long getId() { return id; }
    public User getAuthor() { return author; }
    public String getBody() { return body; }
    public BigDecimal getProposedAmount() { return proposedAmount; }
    public Instant getCreatedAt() { return createdAt; }
    public LocalDateTime getCreatedDateTime() { return LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC); }
    public boolean isFromStudio() { return author.getId().equals(quote.getStudio().getCreator().getId()); }
}
