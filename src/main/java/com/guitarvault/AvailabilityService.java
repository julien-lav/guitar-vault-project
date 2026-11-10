package com.guitarvault;

import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AvailabilityService {
    public static final List<Integer> DURATIONS = List.of(15, 30, 45, 60, 90, 120);
    public record Slot(Instant start, Instant end, LocalTime localStart, LocalTime localEnd) { }
    public record CalendarDay(WorkshopAvailability settings, LocalDate date, LocalDate today, LocalDate lastDate, List<Slot> slots) { }
    private final Clock clock;
    private final LuthierProfileRepository workshops;
    private final WorkshopAvailabilityRepository settings;
    private final WorkshopBlockedDateRepository blocked;
    private final LuthierAppointmentRepository appointments;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    @org.springframework.beans.factory.annotation.Autowired
    public AvailabilityService(LuthierProfileRepository workshops, WorkshopAvailabilityRepository settings,
            WorkshopBlockedDateRepository blocked, LuthierAppointmentRepository appointments) {
        this(workshops, settings, blocked, appointments, Clock.systemUTC());
    }
    AvailabilityService(LuthierProfileRepository workshops, WorkshopAvailabilityRepository settings,
            WorkshopBlockedDateRepository blocked, LuthierAppointmentRepository appointments, Clock clock) {
        this.workshops = workshops; this.settings = settings; this.blocked = blocked; this.appointments = appointments; this.clock = clock;
    }
    // All appointment/calendar mutations lock this row FIRST, including when settings do not exist yet.
    LuthierProfile lockWorkshop(Long id) {
        var workshop = workshops.findForUpdate(id).orElseThrow(AvailabilityService::notFound);
        entityManager.refresh(workshop);
        return workshop;
    }
    public LuthierProfile owner(User actor) {
        return workshops.findByUserId(actor.getId()).orElseThrow(AvailabilityService::notFound);
    }
    private LuthierProfile lockOwner(User actor) {
        var id = workshops.idForOwner(actor.getId()).orElseThrow(AvailabilityService::notFound);
        return lockWorkshop(id);
    }
    WorkshopAvailability current(LuthierProfile workshop) {
        return settings.findById(workshop.getId()).orElseGet(() -> new WorkshopAvailability(workshop));
    }
    private WorkshopAvailability fresh(LuthierProfile workshop) {
        var config = current(workshop);
        if (config.getId() != null) entityManager.refresh(config);
        return config;
    }
    private WorkshopAvailability checked(LuthierProfile workshop, long version) {
        var config = fresh(workshop);
        if ((config.getId() == null ? -1 : config.getVersion()) != version) throw conflict("availability.changed");
        return config;
    }
    @Transactional
    public void save(User actor, long version, boolean enabled, String zone, int minutes, String opens, String closes, List<String> days) {
        var workshop = lockOwner(actor);
        var config = checked(workshop, version);
        LocalTime from, to; Set<DayOfWeek> weekdays = new HashSet<>();
        try {
            if (!ZoneId.getAvailableZoneIds().contains(zone) || !DURATIONS.contains(minutes)) throw new IllegalArgumentException();
            from = LocalTime.parse(opens); to = LocalTime.parse(closes);
            if (!from.isBefore(to) || from.getSecond() != 0 || to.getSecond() != 0 || from.getNano() != 0 || to.getNano() != 0
                    || Duration.between(from, to).toMinutes() < minutes) throw new IllegalArgumentException();
            for (String day : days) weekdays.add(DayOfWeek.valueOf(day));
            if (weekdays.isEmpty()) throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new IllegalArgumentException("availability.invalid"); }
        config.update(enabled, zone, minutes, from, to, weekdays);
        settings.saveAndFlush(config);
    }
    @Transactional
    public void saveHours(User actor, long version, List<String> days, Map<String, String> form) {
        var workshop = lockOwner(actor); var config = checked(workshop, version);
        if (config.getId() == null) throw conflict("availability.configureFirst");
        Set<DayOfWeek> weekdays = new HashSet<>(); Map<DayOfWeek, WorkshopDayHours> hours = new HashMap<>();
        try {
            for (String name : days) {
                var day = DayOfWeek.valueOf(name);
                var opens = minute(form.get(name + "-opens")); var closes = minute(form.get(name + "-closes"));
                var start = optionalMinute(form.get(name + "-breakStart")); var end = optionalMinute(form.get(name + "-breakEnd"));
                if (!opens.isBefore(closes) || Duration.between(opens, closes).toMinutes() < config.getDurationMinutes()
                        || (start == null) != (end == null)
                        || (start != null && (!opens.isBefore(start) || !start.isBefore(end) || !end.isBefore(closes)))) throw new IllegalArgumentException();
                weekdays.add(day); hours.put(day, new WorkshopDayHours(opens, closes, start, end));
            }
            if (weekdays.isEmpty()) throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new IllegalArgumentException("availability.invalid"); }
        config.weeklyHours(weekdays, hours); settings.saveAndFlush(config);
    }
    private static LocalTime minute(String value) {
        var time = LocalTime.parse(value);
        if (time.getSecond() != 0 || time.getNano() != 0) throw new IllegalArgumentException();
        return time;
    }
    private static LocalTime optionalMinute(String value) { return value == null || value.isBlank() ? null : minute(value); }
    public record OverviewDay(LocalDate date, List<LuthierAppointment> bookings, int freeSlots, boolean blocked) { }
    @Transactional(readOnly = true)
    public List<OverviewDay> overview(User actor, LocalDate selected, String view) {
        var workshop = owner(actor); var config = current(workshop); var zone = ZoneId.of(config.getTimeZone());
        var today = LocalDate.now(clock.withZone(zone));
        if (selected == null) selected = today;
        if (selected.isBefore(today) || selected.isAfter(today.plusDays(90))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        LocalDate start, end;
        if ("month".equals(view)) { start = selected.withDayOfMonth(1); end = start.plusMonths(1); }
        else if ("week".equals(view)) { start = selected.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); end = start.plusDays(7); }
        else throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var bookings = occupied(workshop.getId(), start.atStartOfDay(zone).toInstant(), end.atStartOfDay(zone).toInstant());
        var blockedDates = blocked.findByWorkshopIdOrderByDateAsc(workshop.getId()).stream().map(WorkshopBlockedDate::getDate).collect(java.util.stream.Collectors.toSet());
        List<OverviewDay> result = new ArrayList<>();
        for (LocalDate date = start; date.isBefore(end); date = date.plusDays(1)) {
            var from = date.atStartOfDay(zone).toInstant(); var to = date.plusDays(1).atStartOfDay(zone).toInstant();
            var dayBookings = bookings.stream().filter(a -> overlaps(from, to, a.getProposedAt(), a.getEndsAt())).toList();
            int free = date.isBefore(today) || date.isAfter(today.plusDays(90)) ? 0 : slots(workshop.getId(), config, date).size();
            result.add(new OverviewDay(date, dayBookings, free, blockedDates.contains(date)));
        }
        return List.copyOf(result);
    }
    @Transactional
    public void block(User actor, long version, LocalDate date) {
        var workshop = lockOwner(actor); var config = checked(workshop, version);
        if (config.getId() == null) throw conflict("availability.configureFirst");
        var zone = ZoneId.of(config.getTimeZone()); var today = LocalDate.now(clock.withZone(zone));
        if (date == null || date.isBefore(today) || date.isAfter(today.plusDays(365))) throw new IllegalArgumentException("availability.dateError");
        if (blocked.existsByWorkshopIdAndDate(workshop.getId(), date)) throw new IllegalArgumentException("availability.alreadyBlocked");
        if (!confirmedOn(workshop.getId(), date, zone).isEmpty()) throw conflict("availability.hasBooking");
        blocked.save(new WorkshopBlockedDate(workshop, date)); config.touch(); settings.flush();
    }
    @Transactional
    public void unblock(User actor, long version, LocalDate date) {
        var workshop = lockOwner(actor); var config = checked(workshop, version);
        var entry = blocked.findByWorkshopIdOrderByDateAsc(workshop.getId()).stream().filter(d -> d.getDate().equals(date))
                .findFirst().orElseThrow(AvailabilityService::notFound);
        blocked.delete(entry); config.touch(); settings.flush();
    }
    @Transactional(readOnly = true)
    public CalendarDay calendar(LuthierProfile workshop, LocalDate requested) {
        var config = current(workshop); var zone = ZoneId.of(config.getTimeZone()); var today = LocalDate.now(clock.withZone(zone));
        var date = requested == null ? today : requested;
        if (date.isBefore(today) || date.isAfter(today.plusDays(90))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return new CalendarDay(config, date, today, today.plusDays(90), slots(workshop.getId(), config, date));
    }
    @Transactional(readOnly = true)
    public List<WorkshopBlockedDate> blockedDates(User actor) { return blocked.findByWorkshopIdOrderByDateAsc(owner(actor).getId()); }
    @Transactional(readOnly = true)
    public List<LuthierAppointment> ownerBookings(User actor, LocalDate date) {
        var workshop = owner(actor); var config = current(workshop);
        return confirmedOn(workshop.getId(), date, ZoneId.of(config.getTimeZone()));
    }
    private List<LuthierAppointment> confirmedOn(Long workshop, LocalDate date, ZoneId zone) {
        var start = date.atStartOfDay(zone).toInstant(); var end = date.plusDays(1).atStartOfDay(zone).toInstant();
        return occupied(workshop, start, end);
    }
    private List<LuthierAppointment> occupied(Long workshop, Instant start, Instant end) {
        // Durations are at most 120 minutes; legacy rows without a duration use 30 minutes.
        return appointments.bookings(workshop, LuthierAppointment.Status.CONFIRMED, start.minusSeconds(120 * 60L), end)
                .stream().filter(a -> a.getEndsAt().isAfter(start)).toList();
    }
    private List<Slot> slots(Long workshop, WorkshopAvailability config, LocalDate date) {
        if (!config.isEnabled() || !config.getDays().contains(date.getDayOfWeek()) || blocked.existsByWorkshopIdAndDate(workshop, date)) return List.of();
        var zone = ZoneId.of(config.getTimeZone());
        var bookings = confirmedOn(workshop, date, zone);
        List<Slot> result = new ArrayList<>();
        var hours = config.hours(date.getDayOfWeek());
        List<LocalTime[]> windows = hours.getBreakStart() == null
                ? Collections.singletonList(new LocalTime[]{hours.getOpens(), hours.getCloses()})
                : List.of(new LocalTime[]{hours.getOpens(), hours.getBreakStart()}, new LocalTime[]{hours.getBreakEnd(), hours.getCloses()});
        for (var window : windows) {
        for (var local = date.atTime(window[0]); !local.plusMinutes(config.getDurationMinutes()).isAfter(date.atTime(window[1])); local = local.plusMinutes(config.getDurationMinutes())) {
            var endLocal = local.plusMinutes(config.getDurationMinutes());
            var starts = zone.getRules().getValidOffsets(local); var ends = zone.getRules().getValidOffsets(endLocal);
            if (starts.size() != 1 || ends.size() != 1) continue;
            var start = local.atOffset(starts.getFirst()).toInstant(); var end = endLocal.atOffset(ends.getFirst()).toInstant();
            if (!Duration.between(start, end).equals(Duration.ofMinutes(config.getDurationMinutes())) || !start.isAfter(Instant.now(clock))) continue;
            if (bookings.stream().anyMatch(a -> overlaps(start, end, a.getProposedAt(), a.getEndsAt()))) continue;
            result.add(new Slot(start, end, local.toLocalTime(), endLocal.toLocalTime()));
        }
        }
        return List.copyOf(result);
    }
    record Proposal(Instant time, String zone, int duration) { }
    Proposal proposal(LuthierProfile workshop, Map<String, String> form, Instant manualTime, String manualZone) {
        var config = fresh(workshop);
        if (!config.isEnabled()) {
            if (form.containsKey("slot") && !form.get("slot").isBlank()) throw conflict("availability.changed");
            return new Proposal(manualTime, manualZone, 30);
        }
        try {
            if (Long.parseLong(form.getOrDefault("availabilityVersion", "")) != config.getVersion()) throw conflict("availability.changed");
        } catch (NumberFormatException e) { throw conflict("availability.changed"); }
        Instant time;
        try { time = Instant.parse(form.get("slot")); }
        catch (RuntimeException e) { throw new IllegalArgumentException("availability.pickSlot"); }
        validate(workshop, config, time, config.getDurationMinutes());
        return new Proposal(time, config.getTimeZone(), config.getDurationMinutes());
    }
    boolean enabled(LuthierProfile workshop) { return fresh(workshop).isEnabled(); }
    void confirm(LuthierAppointment a) {
        var config = fresh(a.getWorkshop());
        if (config.isEnabled()) validate(a.getWorkshop(), config, a.getProposedAt(), a.getDurationMinutes());
        if (!occupied(a.getWorkshop().getId(), a.getProposedAt(), a.getEndsAt()).isEmpty()) throw conflict("availability.taken");
    }
    private void validate(LuthierProfile workshop, WorkshopAvailability config, Instant time, int duration) {
        var date = time.atZone(ZoneId.of(config.getTimeZone())).toLocalDate(); var today = LocalDate.now(clock.withZone(ZoneId.of(config.getTimeZone())));
        if (duration != config.getDurationMinutes() || date.isBefore(today) || date.isAfter(today.plusDays(90))
                || slots(workshop.getId(), config, date).stream().noneMatch(s -> s.start().equals(time))) throw conflict("availability.taken");
    }
    private static boolean overlaps(Instant start, Instant end, Instant otherStart, Instant otherEnd) { return start.isBefore(otherEnd) && end.isAfter(otherStart); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
    private static ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT, reason); }
}
