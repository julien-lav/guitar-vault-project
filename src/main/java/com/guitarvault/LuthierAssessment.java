package com.guitarvault;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/** Each correction creates a new attributed revision; earlier reports are retained. */
@Entity
@Table(name = "luthier_assessments", uniqueConstraints = @UniqueConstraint(columnNames = {"intervention_id", "revision"}))
public class LuthierAssessment {
    public enum Conclusion { WORK_RECORDED, CONSISTENT, INCONCLUSIVE, INCONSISTENT }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private LuthierIntervention intervention;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private User author;
    @Column(nullable = false) private int revision;
    @Column(nullable = false) private LocalDate examinedOn;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private Conclusion conclusion;
    @Column(nullable = false, length = 5000) private String findings;
    @Column(nullable = false, length = 3000) private String evidence;
    @Column(nullable = false, length = 3000) private String limitations;
    @Column(nullable = false, length = 1000) private String correctionReason;
    @Column(nullable = false, length = 150) private String workshopName;
    @Column(nullable = false, length = 255) private String authorName;
    @Column(nullable = false) private boolean professionalVerified;
    @Column(nullable = false, updatable = false) private Instant recordedAt = Instant.now();
    protected LuthierAssessment() { }
    public LuthierAssessment(LuthierIntervention intervention, User author, int revision, LocalDate examinedOn,
            Conclusion conclusion, String findings, String evidence, String limitations, String correctionReason) {
        this.intervention = intervention; this.author = author; this.revision = revision; this.examinedOn = examinedOn;
        this.conclusion = conclusion; this.findings = findings; this.evidence = evidence;
        this.limitations = limitations; this.correctionReason = correctionReason;
        this.workshopName = intervention.getLuthier().getName(); this.authorName = author.getUsername();
        this.professionalVerified = intervention.getLuthier().isVerified();
    }
    public LuthierIntervention getIntervention() { return intervention; }
    public Long getId() { return id; }
    public User getAuthor() { return author; }
    public int getRevision() { return revision; }
    public LocalDate getExaminedOn() { return examinedOn; }
    public Conclusion getConclusion() { return conclusion; }
    public String getFindings() { return findings; }
    public String getEvidence() { return evidence; }
    public String getLimitations() { return limitations; }
    public String getCorrectionReason() { return correctionReason; }
    public String getWorkshopName() { return workshopName; }
    public String getAuthorName() { return authorName; }
    public boolean isProfessionalVerified() { return professionalVerified; }
    public java.time.LocalDateTime getRecordedDateTime() { return java.time.LocalDateTime.ofInstant(recordedAt, java.time.ZoneOffset.UTC); }
    public Instant getRecordedAt() { return recordedAt; }
}
