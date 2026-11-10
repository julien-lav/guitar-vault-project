package com.guitarvault;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "notifications", indexes = @Index(name = "idx_notification_recipient_read", columnList = "recipient_id,read_at,id"))
public class Notification {
    public enum Kind { BAND_INVITATION, BAND_CONNECTION_REQUEST, CONCERT_INVITATION, QUOTE_REQUEST, QUOTE_MESSAGE, DATE_PROPOSAL, DATE_PROPOSAL_RESPONSE, INSTRUMENT_TRANSFER, INSTRUMENT_TRANSFER_RESPONSE, LUTHIER_APPOINTMENT }
    /** A varchar keeps existing H2 schemas compatible when new notification kinds are added. */
    @Converter
    public static class KindConverter implements AttributeConverter<Kind, String> {
        @Override public String convertToDatabaseColumn(Kind kind) { return kind == null ? null : kind.name(); }
        @Override public Kind convertToEntityAttribute(String value) { return value == null ? null : Kind.valueOf(value); }
    }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false, updatable = false) private User recipient;
    @Convert(converter = KindConverter.class) @Column(nullable = false, length = 40, updatable = false) private Kind kind;
    @Column(nullable = false, length = 200, updatable = false) private String subject;
    @Column(nullable = false, length = 200, updatable = false) private String targetUrl;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "read_at") private Instant readAt;
    protected Notification() { }
    Notification(User recipient, Kind kind, String subject, String targetUrl) {
        this.recipient = recipient; this.kind = kind; this.subject = subject; this.targetUrl = targetUrl;
    }
    public Long getId() { return id; }
    public User getRecipient() { return recipient; }
    public Kind getKind() { return kind; }
    public String getSubject() { return subject; }
    public String getTargetUrl() { return targetUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public LocalDateTime getCreatedDateTime() { return LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC); }
    public Instant getReadAt() { return readAt; }
    public boolean isUnread() { return readAt == null; }
}
