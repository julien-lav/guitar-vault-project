package com.guitarvault;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "luthier_interventions", indexes = {
    @Index(name = "idx_intervention_instrument", columnList = "instrument_id"),
    @Index(name = "idx_intervention_luthier", columnList = "luthier_id")})
public class LuthierIntervention {
    public enum Kind { INSPECTION, REPAIR, SETUP, MODIFICATION, AUTHENTICITY }
    public enum Status { REQUESTED, COMPLETED, CANCELLED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private Instrument instrument;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private User requester;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "luthier_id", nullable = false) private LuthierProfile luthier;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private Kind kind;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private Status status = Status.REQUESTED;
    @Column(nullable = false, length = 3000) private String description;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    protected LuthierIntervention() { }
    public LuthierIntervention(Instrument instrument, User requester, LuthierProfile luthier, Kind kind, String description) {
        this.instrument = instrument; this.requester = requester; this.luthier = luthier; this.kind = kind; this.description = description;
    }
    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public User getRequester() { return requester; }
    public LuthierProfile getLuthier() { return luthier; }
    public Kind getKind() { return kind; }
    public Status getStatus() { return status; }
    public String getDescription() { return description; }
    public java.time.LocalDateTime getCreatedDateTime() { return java.time.LocalDateTime.ofInstant(createdAt, java.time.ZoneOffset.UTC); }
    public Instant getCreatedAt() { return createdAt; }
    void complete() { status = Status.COMPLETED; }
    void cancel() { status = Status.CANCELLED; }
}
