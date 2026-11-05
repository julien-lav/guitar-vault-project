package com.guitarvault;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "band_connections", uniqueConstraints = @UniqueConstraint(columnNames = {"first_band_id", "second_band_id", "type"}))
public class BandConnection {
    public enum Type { FRIEND, PLAYED_TOGETHER }
    public enum Status { PENDING, ACCEPTED, DECLINED, CANCELLED, REMOVED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "first_band_id", nullable = false)
    private Band firstBand;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "second_band_id", nullable = false)
    private Band secondBand;
    @Column(nullable = false)
    private boolean requestedByFirst;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private Type type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Status status;
    private LocalDate playedOn;
    @Column(length = 200)
    private String venue;
    @Column(nullable = false)
    private Instant updatedAt;

    protected BandConnection() { }
    BandConnection(Band firstBand, Band secondBand, Type type) {
        this.firstBand = firstBand;
        this.secondBand = secondBand;
        this.type = type;
    }
    void request(Long senderId, LocalDate playedOn, String venue) {
        this.requestedByFirst = firstBand.getId().equals(senderId);
        this.playedOn = playedOn;
        this.venue = venue;
        setStatus(Status.PENDING);
    }
    void setStatus(Status status) { this.status = status; this.updatedAt = Instant.now(); }
    public Long getId() { return id; }
    public Band getFirstBand() { return firstBand; }
    public Band getSecondBand() { return secondBand; }
    public Band getSender() { return requestedByFirst ? firstBand : secondBand; }
    public Band getRecipient() { return requestedByFirst ? secondBand : firstBand; }
    public Type getType() { return type; }
    public Status getStatus() { return status; }
    public LocalDate getPlayedOn() { return playedOn; }
    public String getVenue() { return venue; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Band other(Long bandId) { return firstBand.getId().equals(bandId) ? secondBand : firstBand; }
}
