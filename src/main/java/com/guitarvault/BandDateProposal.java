package com.guitarvault;

import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(name = "band_date_proposals", indexes = {@Index(name = "idx_date_sender", columnList = "sender_id,id"), @Index(name = "idx_date_recipient", columnList = "recipient_id,id")})
public class BandDateProposal {
    public enum Status { PENDING, ACCEPTED, DECLINED, CANCELLED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "sender_id", nullable = false) private Band sender;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "recipient_id", nullable = false) private Band recipient;
    @Column(nullable = false) private boolean proposedBySender;
    @Column(nullable = false) private int revision;
    @Column(nullable = false) private LocalDate proposedDate;
    @Column(nullable = false, length = 200) private String venue;
    @Column(nullable = false, length = 2000) private String message;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status = Status.PENDING;
    @Column(nullable = false, length = 2000) private String decisionMessage = "";
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();
    protected BandDateProposal() { }
    BandDateProposal(Band sender, Band recipient, LocalDate date, String venue, String message) {
        this.sender = sender; this.recipient = recipient;
        propose(true, date, venue, message);
    }
    void propose(boolean bySender, LocalDate date, String venue, String message) {
        proposedBySender = bySender; revision++; proposedDate = date; this.venue = venue; this.message = message; updatedAt = Instant.now();
    }
    void decide(Status status, String message) { this.status = status; decisionMessage = message; updatedAt = Instant.now(); }
    public Long getId() { return id; }
    public Band getSender() { return sender; }
    public Band getRecipient() { return recipient; }
    public Band getProposer() { return proposedBySender ? sender : recipient; }
    public Band getResponder() { return proposedBySender ? recipient : sender; }
    public int getRevision() { return revision; }
    public LocalDate getProposedDate() { return proposedDate; }
    public String getVenue() { return venue; }
    public String getMessage() { return message; }
    public Status getStatus() { return status; }
    public String getDecisionMessage() { return decisionMessage; }
    public LocalDateTime getUpdatedDateTime() { return LocalDateTime.ofInstant(updatedAt, ZoneOffset.UTC); }
}
