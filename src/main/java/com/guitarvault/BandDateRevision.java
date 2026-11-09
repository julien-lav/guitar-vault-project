package com.guitarvault;

import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(name = "band_date_revisions", uniqueConstraints = @UniqueConstraint(columnNames = {"proposal_id", "revision_number"}))
public class BandDateRevision {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "proposal_id", nullable = false, updatable = false) private BandDateProposal proposal;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false, updatable = false) private User author;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false, updatable = false) private Band band;
    @Column(name = "revision_number", nullable = false, updatable = false) private int number;
    @Column(nullable = false, updatable = false) private LocalDate proposedDate;
    @Column(nullable = false, updatable = false, length = 200) private String venue;
    @Column(nullable = false, updatable = false, length = 2000) private String message;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    protected BandDateRevision() { }
    BandDateRevision(BandDateProposal proposal, User actor) {
        this.proposal = proposal; author = actor; band = proposal.getProposer(); number = proposal.getRevision();
        proposedDate = proposal.getProposedDate(); venue = proposal.getVenue(); message = proposal.getMessage();
    }
    public int getNumber() { return number; }
    public Band getBand() { return band; }
    public User getAuthor() { return author; }
    public LocalDate getProposedDate() { return proposedDate; }
    public String getVenue() { return venue; }
    public String getMessage() { return message; }
    public LocalDateTime getCreatedDateTime() { return LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC); }
}
