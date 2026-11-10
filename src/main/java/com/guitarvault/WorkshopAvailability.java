package com.guitarvault;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "workshop_availability")
public class WorkshopAvailability {
    @Id private Long id;
    @OneToOne(fetch = FetchType.LAZY) @MapsId @JoinColumn(name = "workshop_id") private LuthierProfile workshop;
    @Version private long version;
    @Column(nullable = false) private boolean enabled;
    @Column(nullable = false, length = 80) private String timeZone = "UTC";
    @Column(nullable = false) private int durationMinutes = 30;
    @Column(nullable = false) private LocalTime opensAt = LocalTime.of(9, 0);
    @Column(nullable = false) private LocalTime closesAt = LocalTime.of(17, 0);
    @ElementCollection @CollectionTable(name = "workshop_working_days", joinColumns = @JoinColumn(name = "workshop_id"))
    @Enumerated(EnumType.STRING) @Column(name = "weekday", length = 12, nullable = false)
    private Set<DayOfWeek> days = new HashSet<>(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
    @ElementCollection
    @CollectionTable(name = "workshop_day_hours", joinColumns = @JoinColumn(name = "workshop_id"))
    @MapKeyEnumerated(EnumType.STRING) @MapKeyColumn(name = "weekday", length = 12)
    private Map<DayOfWeek, WorkshopDayHours> dayHours = new HashMap<>();
    public Map<DayOfWeek, WorkshopDayHours> getDayHours() { return Map.copyOf(dayHours); }
    public WorkshopDayHours hours(DayOfWeek day) { return dayHours.getOrDefault(day, new WorkshopDayHours(opensAt, closesAt, null, null)); }
    void weeklyHours(Set<DayOfWeek> days, Map<DayOfWeek, WorkshopDayHours> hours) {
        this.days.clear(); this.days.addAll(days); dayHours.clear(); dayHours.putAll(hours); touch();
    }
    @Column(nullable = false) private Instant updatedAt = Instant.now();
    protected WorkshopAvailability() { }
    WorkshopAvailability(LuthierProfile workshop) { this.workshop = workshop; }
    public Long getId() { return id; }
    public long getVersion() { return version; }
    public boolean isEnabled() { return enabled; }
    public String getTimeZone() { return timeZone; }
    public int getDurationMinutes() { return durationMinutes; }
    public LocalTime getOpensAt() { return opensAt; }
    public LocalTime getClosesAt() { return closesAt; }
    public Set<DayOfWeek> getDays() { return Set.copyOf(days); }
    void update(boolean enabled, String zone, int duration, LocalTime opens, LocalTime closes, Set<DayOfWeek> days) {
        this.enabled = enabled; timeZone = zone; durationMinutes = duration; opensAt = opens; closesAt = closes;
        this.days.clear(); this.days.addAll(days); dayHours.clear(); touch();
    }
    void touch() { updatedAt = Instant.now(); }
}
