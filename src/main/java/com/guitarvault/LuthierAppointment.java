package com.guitarvault;

import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(name = "luthier_appointments", indexes = {
    @Index(name = "idx_appointment_requester", columnList = "requester_id,id"),
    @Index(name = "idx_appointment_workshop", columnList = "workshop_id,id"),
    @Index(name = "idx_appointment_slot", columnList = "workshop_id,status,proposed_at")})
public class LuthierAppointment {
    public enum Status { PENDING, CONFIRMED, DECLINED, CANCELLED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private User requester;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private LuthierProfile workshop;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private User proposer;
    @Column(nullable = false, length = 150) private String title;
    private Instant proposedAt;
    @Column(length = 80) private String timeZone;
    private Integer durationMinutes;
    private Boolean requesterReminder;
    private Boolean workshopReminder;
    private Instant requesterRemindedAt;
    private Instant workshopRemindedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status = Status.PENDING;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();
    @Version private long version;
    protected LuthierAppointment() { }
    LuthierAppointment(User requester, LuthierProfile workshop, String title, Instant proposedAt, String timeZone) {
        this.requester = requester; this.workshop = workshop; this.proposer = requester;
        this.title = title; this.proposedAt = proposedAt; this.timeZone = timeZone;
    }
    public Long getId() { return id; }
    public User getRequester() { return requester; }
    public LuthierProfile getWorkshop() { return workshop; }
    public User getProposer() { return proposer; }
    public String getTitle() { return title; }
    public Instant getProposedAt() { return proposedAt; }
    public String getTimeZone() { return timeZone; }
    public LocalDateTime getProposedLocal() { return proposedAt == null ? null : LocalDateTime.ofInstant(proposedAt, ZoneId.of(timeZone)); }
    public int getDurationMinutes() { return durationMinutes == null ? 30 : durationMinutes; }
    public Instant getEndsAt() { return proposedAt == null ? null : proposedAt.plusSeconds(getDurationMinutes() * 60L); }
    public LocalDateTime getEndsLocal() { return LocalDateTime.ofInstant(getEndsAt(), ZoneId.of(timeZone)); }
    void duration(int minutes) { durationMinutes = minutes; }
    public boolean isScheduled() { return proposedAt != null; }
    public boolean reminderEnabled(boolean requester) { return Boolean.TRUE.equals(requester ? requesterReminder : workshopReminder); }
    public boolean reminderSent(boolean requester) { return (requester ? requesterRemindedAt : workshopRemindedAt) != null; }
    void reminder(boolean requester, boolean enabled) { if (requester) requesterReminder = enabled; else workshopReminder = enabled; touch(); }
    void reminded(boolean requester) { if (requester) requesterRemindedAt = Instant.now(); else workshopRemindedAt = Instant.now(); }
    public Status getStatus() { return status; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isOpen() { return status == Status.PENDING || status == Status.CONFIRMED; }
    void touch() { updatedAt = Instant.now(); }
    void propose(User actor, Instant time, String zone) { proposer = actor; proposedAt = time; timeZone = zone; touch(); }
    void resolve(Status next) { status = next; touch(); }
}
