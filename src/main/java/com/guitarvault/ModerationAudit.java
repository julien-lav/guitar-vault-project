package com.guitarvault;

import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(name = "moderation_audit", indexes = @Index(name = "idx_moderation_audit_report", columnList = "report_id,id"))
public class ModerationAudit {
    public enum Action { REVIEW, DISMISS, HIDE, RESTORE, SUSPEND, REACTIVATE }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "report_id", nullable = false, updatable = false) private ContentReport report;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "actor_id", nullable = false, updatable = false) private User actor;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false) private Action action;
    @Column(nullable = false, length = 2000, updatable = false) private String note;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    protected ModerationAudit() { }
    ModerationAudit(ContentReport report, User actor, Action action, String note) { this.report = report; this.actor = actor; this.action = action; this.note = note; }
    public User getActor() { return actor; }
    public Action getAction() { return action; }
    public String getNote() { return note; }
    public LocalDateTime getCreatedDateTime() { return LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC); }
}
