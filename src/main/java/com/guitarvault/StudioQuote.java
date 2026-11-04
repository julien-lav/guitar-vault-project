package com.guitarvault;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Entity
@Table(name = "studio_quotes", indexes = {
        @Index(name = "idx_quote_requester", columnList = "requester_id"),
        @Index(name = "idx_quote_studio", columnList = "studio_id")})
public class StudioQuote {
    public enum Status { PENDING, ANSWERED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    private long version;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "studio_id", nullable = false)
    private Studio studio;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_id", nullable = false)
    private User requester;
    @Column(nullable = false, length = 150)
    private String title;
    @Column(nullable = false, length = 5000)
    private String description;
    private LocalDate startDate;
    private LocalDate endDate;
    @Column(precision = 8, scale = 2)
    private BigDecimal budget;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;
    @Column(length = 5000)
    private String reply;
    @Column(precision = 8, scale = 2)
    private BigDecimal proposedAmount;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    private Instant answeredAt;

    protected StudioQuote() { }
    public StudioQuote(Studio studio, User requester, String title, String description,
                       LocalDate startDate, LocalDate endDate, BigDecimal budget) {
        this.studio = studio;
        this.requester = requester;
        this.title = title;
        this.description = description;
        this.startDate = startDate;
        this.endDate = endDate;
        this.budget = budget;
    }
    public void answer(String reply, BigDecimal proposedAmount) {
        this.reply = reply;
        this.proposedAmount = proposedAmount;
        this.status = Status.ANSWERED;
        this.answeredAt = Instant.now();
    }
    public Long getId() { return id; }
    public Studio getStudio() { return studio; }
    public User getRequester() { return requester; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public BigDecimal getBudget() { return budget; }
    public Status getStatus() { return status; }
    public String getReply() { return reply; }
    public BigDecimal getProposedAmount() { return proposedAmount; }
    public Instant getCreatedAt() { return createdAt; }
    public LocalDate getCreatedDate() { return createdAt.atZone(ZoneOffset.UTC).toLocalDate(); }
    public Instant getAnsweredAt() { return answeredAt; }
}
