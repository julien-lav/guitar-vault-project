package com.guitarvault;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "band_calendar_events", indexes = @Index(name = "idx_band_calendar_date", columnList = "band_id,event_date"))
public class BandCalendarEvent {
    public enum Type { REHEARSAL, STUDIO, PROMO, RELEASE, OTHER }
    public enum Recurrence { NONE, WEEKLY }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version private long version;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "band_id", nullable = false) private Band band;
    @Column(nullable = false, length = 150) private String title;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Type type;
    @Column(name = "event_date", nullable = false) private LocalDate date;
    private LocalTime startTime;
    private LocalTime endTime;
    @Column(length = 200) private String location;
    @Column(length = 5000) private String notes;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Recurrence recurrence;
    private LocalDate repeatUntil;
    protected BandCalendarEvent() { }
    BandCalendarEvent(Band band) { this.band = band; }
    void update(String title, Type type, LocalDate date, LocalTime startTime, LocalTime endTime,
                String location, String notes, Recurrence recurrence, LocalDate repeatUntil) {
        this.title = title; this.type = type; this.date = date; this.startTime = startTime; this.endTime = endTime;
        this.location = location; this.notes = notes; this.recurrence = recurrence; this.repeatUntil = repeatUntil;
    }
    public Long getId() { return id; }
    public Band getBand() { return band; }
    public String getTitle() { return title; }
    public Type getType() { return type; }
    public LocalDate getDate() { return date; }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime() { return endTime; }
    public String getLocation() { return location; }
    public String getNotes() { return notes; }
    public Recurrence getRecurrence() { return recurrence; }
    public LocalDate getRepeatUntil() { return repeatUntil; }
}
