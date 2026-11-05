package com.guitarvault;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "band_invitations", uniqueConstraints = @UniqueConstraint(columnNames = {"band_id", "invitee_id"}),
        indexes = @Index(name = "idx_invitation_invitee", columnList = "invitee_id,status"))
public class BandInvitation {
    public enum Status { PENDING, ACCEPTED, DECLINED, CANCELLED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "band_id", nullable = false)
    private Band band;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invitee_id", nullable = false)
    private User invitee;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected BandInvitation() { }
    public BandInvitation(Band band, User invitee) { this.band = band; this.invitee = invitee; }
    public Long getId() { return id; }
    public Band getBand() { return band; }
    public User getInvitee() { return invitee; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; updatedAt = Instant.now(); }
}
