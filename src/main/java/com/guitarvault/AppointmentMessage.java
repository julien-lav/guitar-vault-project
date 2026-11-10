package com.guitarvault;

import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(name = "appointment_messages", indexes = @Index(name = "idx_appointment_message", columnList = "appointment_id,id"))
public class AppointmentMessage {
    public enum Kind { PROPOSAL, MESSAGE, CONFIRMED, DECLINED, CANCELLED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false, updatable = false) private LuthierAppointment appointment;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false, updatable = false) private User author;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false) private Kind kind;
    @Column(nullable = false, length = 2000, updatable = false) private String body;
    @Column(updatable = false) private Instant proposedAt;
    @Column(length = 80, updatable = false) private String timeZone;
    @Column(updatable = false) private Integer durationMinutes;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    protected AppointmentMessage() { }
    AppointmentMessage(LuthierAppointment appointment, User author, Kind kind, String body) {
        this.appointment = appointment; this.author = author; this.kind = kind; this.body = body;
        if (kind == Kind.PROPOSAL) { proposedAt = appointment.getProposedAt(); timeZone = appointment.getTimeZone(); durationMinutes = appointment.getDurationMinutes(); }
    }
    public Long getId() { return id; }
    public User getAuthor() { return author; }
    public Kind getKind() { return kind; }
    public String getBody() { return body; }
    public Instant getProposedAt() { return proposedAt; }
    public String getTimeZone() { return timeZone; }
    public LocalDateTime getProposedLocal() { return proposedAt == null ? null : LocalDateTime.ofInstant(proposedAt, ZoneId.of(timeZone)); }
    public int getDurationMinutes() { return durationMinutes == null ? 30 : durationMinutes; }
    public Instant getCreatedAt() { return createdAt; }
    public LocalDateTime getCreatedDateTime() { return LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC); }
}
