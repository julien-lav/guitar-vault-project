package com.guitarvault;

import jakarta.persistence.*;
import java.time.Instant;
import java.math.BigDecimal;

@Entity
@Table(name = "workshop_job_events", indexes = @Index(name = "idx_job_event", columnList = "job_id,id"))
public class WorkshopJobEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false, updatable = false) private WorkshopJob job;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false, updatable = false) private User author;
    @Column(nullable = false, updatable = false, length = 30) private String action;
    @Column(nullable = false, updatable = false, length = 3000) private String message;
    @Column(updatable = false, precision = 12, scale = 2) private BigDecimal amount;
    @Column(updatable = false, length = 3) private String currency;
    @Column(updatable = false, length = 3000) private String scope;
    @Column(updatable = false) private java.time.LocalDate completionOn;
    public String getScope() { return scope; }
    public java.time.LocalDate getCompletionOn() { return completionOn; }
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    protected WorkshopJobEvent() { }
    WorkshopJobEvent(WorkshopJob job, User author, String action, String message) {
        this.job = job; this.author = author; this.action = action; this.message = message;
        if (java.util.Set.of("request-change", "decline-change").contains(action)) {
            amount = job.getProposedAmount(); currency = job.getCurrency(); scope = job.getProposedScope(); completionOn = job.getProposedCompletionOn();
        } else if (java.util.Set.of("quote", "approve", "approve-change", "resume-original").contains(action)) {
            amount = job.getAmount(); currency = job.getCurrency(); scope = action.equals("quote") ? null : job.getScope(); completionOn = job.getEstimatedCompletionOn();
        }
    }
    public Long getId() { return id; }
    public User getAuthor() { return author; }
    public String getAction() { return action; }
    public String getMessage() { return message; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public java.time.LocalDateTime getCreatedDateTime() { return java.time.LocalDateTime.ofInstant(createdAt, java.time.ZoneOffset.UTC); }
}
