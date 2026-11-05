package com.guitarvault;

import jakarta.persistence.*;

@Entity
@Table(name = "concert_participations", uniqueConstraints = @UniqueConstraint(columnNames = {"concert_id", "band_id"}),
        indexes = @Index(name = "idx_concert_participation_band", columnList = "band_id,status"))
public class ConcertParticipation {
    public enum Status { PENDING, ACCEPTED, DECLINED, REMOVED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "concert_id", nullable = false) private Concert concert;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "band_id", nullable = false) private Band band;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status = Status.PENDING;
    protected ConcertParticipation() { }
    ConcertParticipation(Concert concert, Band band) { this.concert = concert; this.band = band; }
    void setStatus(Status status) { this.status = status; }
    public Long getId() { return id; }
    public Concert getConcert() { return concert; }
    public Band getBand() { return band; }
    public Status getStatus() { return status; }
}
