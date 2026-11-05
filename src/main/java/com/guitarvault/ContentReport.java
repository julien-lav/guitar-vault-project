package com.guitarvault;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "content_reports", indexes = {@Index(name = "idx_report_status", columnList = "status,id"), @Index(name = "idx_report_author", columnList = "reporter_id,id")})
public class ContentReport {
    public enum TargetType { PROFILE, BAND, STUDIO, CONCERT, ARTICLE }
    public enum Reason { SPAM, ABUSE, IMPERSONATION, SCAM, OTHER }
    public enum Status { OPEN, IN_REVIEW, CLOSED }
    public enum Decision { NONE, DISMISSED, HIDDEN, RESTORED, ACCOUNT_SUSPENDED, ACCOUNT_REACTIVATED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "reporter_id", nullable = false) private User reporter;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "owner_id", nullable = false) private User owner;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private TargetType targetType;
    @Column(nullable = false) private Long targetId;
    @Column(nullable = false, length = 200) private String targetLabel;
    @Column(nullable = false, length = 200) private String targetUrl;
    @Column(nullable = false, length = 60000) private String snapshot;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Reason reason;
    @Column(nullable = false, length = 2000) private String explanation;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status = Status.OPEN;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private Decision decision = Decision.NONE;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    protected ContentReport() { }
    ContentReport(User reporter, ReportTargetResolver.Target target, TargetType type, Long targetId, Reason reason, String explanation) {
        this.reporter = reporter; this.owner = target.owner(); this.targetType = type; this.targetId = targetId;
        this.targetLabel = target.label(); this.targetUrl = target.url(); this.snapshot = target.text(); this.reason = reason; this.explanation = explanation;
    }
    void review() { status = Status.IN_REVIEW; }
    void close(Decision decision) { status = Status.CLOSED; this.decision = decision; }
    public Long getId() { return id; }
    public User getReporter() { return reporter; }
    public User getOwner() { return owner; }
    public TargetType getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public String getTargetLabel() { return targetLabel; }
    public String getTargetUrl() { return targetUrl; }
    public String getSnapshot() { return snapshot; }
    public Reason getReason() { return reason; }
    public String getExplanation() { return explanation; }
    public Status getStatus() { return status; }
    public Decision getDecision() { return decision; }
    public LocalDateTime getCreatedDateTime() { return LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC); }
}
