package com.guitarvault;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "workshop_blocked_dates", uniqueConstraints = @UniqueConstraint(columnNames = {"workshop_id", "blocked_date"}))
public class WorkshopBlockedDate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workshop_id", nullable = false) private LuthierProfile workshop;
    @Column(name = "blocked_date", nullable = false) private LocalDate date;
    protected WorkshopBlockedDate() { }
    WorkshopBlockedDate(LuthierProfile workshop, LocalDate date) { this.workshop = workshop; this.date = date; }
    public Long getId() { return id; }
    public LocalDate getDate() { return date; }
}
