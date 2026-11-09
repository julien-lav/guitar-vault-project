package com.guitarvault;

import jakarta.persistence.*;
import java.time.Instant;

/** Append-only record of an administrator's professional identity review. */
@Entity
@Table(name = "luthier_verifications")
public class LuthierVerification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) private LuthierProfile profile;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) private User reviewer;
    @Column(nullable = false, length = 150) private String workshopName;
    @Column(nullable = false, length = 200) private String workshopLocation;
    @Column(nullable = false, length = 300) private String workshopContact;
    @Column(nullable = false) private boolean verified;
    @Column(nullable = false, length = 2000) private String evidence;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    protected LuthierVerification() { }
    public LuthierVerification(LuthierProfile profile, User reviewer, boolean verified, String evidence) {
        this.profile = profile; this.reviewer = reviewer; this.verified = verified; this.evidence = evidence;
        this.workshopName = profile.getName(); this.workshopLocation = profile.getLocation(); this.workshopContact = profile.getContact();
    }
    public String getWorkshopName() { return workshopName; }
    public String getWorkshopLocation() { return workshopLocation; }
    public String getWorkshopContact() { return workshopContact; }
    public Long getId() { return id; }
    public User getReviewer() { return reviewer; }
    public boolean isVerified() { return verified; }
    public String getEvidence() { return evidence; }
    public java.time.LocalDateTime getCreatedDateTime() { return java.time.LocalDateTime.ofInstant(createdAt, java.time.ZoneOffset.UTC); }
    public Instant getCreatedAt() { return createdAt; }
}
