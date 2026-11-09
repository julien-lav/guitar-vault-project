package com.guitarvault;

import jakarta.persistence.*;
import java.time.Instant;

/** Recorded ownership periods; private legacy fields are archived only for their author. */
@Entity
@Table(name = "instrument_ownerships", uniqueConstraints = @UniqueConstraint(columnNames = {"instrument_id", "started_at"}))
public class InstrumentOwnership {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private Instrument instrument;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private User owner;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    private Instant endedAt;
    @Column(length = 5000) private String notes;
    @Column(length = 10000) private String history;
    @Column(length = 200) private String previousOwner;
    protected InstrumentOwnership() { }
    public InstrumentOwnership(Instrument instrument, User owner, Instant start) {
        this.instrument = instrument; this.owner = owner; startedAt = start;
    }
    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public User getOwner() { return owner; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public String getNotes() { return notes; }
    public String getHistory() { return history; }
    public String getPreviousOwner() { return previousOwner; }
    void close(Instant at) {
        if (endedAt != null) throw new IllegalStateException("Ownership already closed");
        endedAt = at; notes = instrument.getNotes(); history = instrument.getHistory(); previousOwner = instrument.getPreviousOwner();
    }
}
