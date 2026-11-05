package com.guitarvault;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "concerts", indexes = {@Index(name = "idx_concert_band", columnList = "organizer_id"), @Index(name = "idx_concert_date", columnList = "concert_date")})
public class Concert {
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean moderationHidden;
    public boolean isModerationHidden() { return moderationHidden; }
    void setModerationHidden(boolean value) { moderationHidden = value; }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version private long version;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizer_id", nullable = false) private Band organizer;
    @Column(nullable = false, length = 150) private String title;
    @Column(name = "concert_date", nullable = false) private LocalDate date;
    @Column(nullable = false, length = 200) private String venue;
    @Column(length = 100) private String city;
    @Column(length = 5000) private String description;
    @Column(nullable = false) private boolean cancelled;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();

    protected Concert() { }
    public Concert(Band organizer) { this.organizer = organizer; }
    void update(String title, LocalDate date, String venue, String city, String description) {
        this.title = title; this.date = date; this.venue = venue; this.city = city; this.description = description;
    }
    void cancel() { cancelled = true; }
    public Long getId() { return id; }
    public Band getOrganizer() { return organizer; }
    public String getTitle() { return title; }
    public LocalDate getDate() { return date; }
    public String getVenue() { return venue; }
    public String getCity() { return city; }
    public String getDescription() { return description; }
    public boolean isCancelled() { return cancelled; }
}
