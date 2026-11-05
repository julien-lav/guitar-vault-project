package com.guitarvault;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BandCalendarService {
    private final BandService bands;
    private final BandCalendarEventRepository events;
    private final ConcertRepository concerts;
    public BandCalendarService(BandService bands, BandCalendarEventRepository events, ConcertRepository concerts) {
        this.bands = bands; this.events = events; this.concerts = concerts;
    }
    public Band accessible(User actor, Long bandId, boolean manage) {
        Band band = bands.find(bandId);
        boolean owner = band.getCreator().getId().equals(actor.getId());
        if (manage ? !owner : !owner && band.getMembers().stream().noneMatch(u -> u.getId().equals(actor.getId()))) throw notFound();
        return band;
    }
    public BandCalendarEvent editable(User actor, Long bandId, Long id) {
        accessible(actor, bandId, true);
        return events.findByIdAndBandId(id, bandId).orElseThrow(BandCalendarService::notFound);
    }
    @Transactional
    public BandCalendarEvent save(User actor, Long bandId, Long id, Map<String, String> form) {
        Band band = accessible(actor, bandId, true);
        BandCalendarEvent event = id == null ? new BandCalendarEvent(band) : events.findForUpdate(id, bandId).orElseThrow(BandCalendarService::notFound);
        String title = form.get("title"), location = form.get("location"), notes = form.get("notes");
        if (title.isBlank() || title.length() > 150 || location.length() > 200 || notes.length() > 5000) throw new IllegalArgumentException("calendar.fieldsError");
        BandCalendarEvent.Type type;
        BandCalendarEvent.Recurrence recurrence;
        try { type = BandCalendarEvent.Type.valueOf(form.get("type")); recurrence = BandCalendarEvent.Recurrence.valueOf(form.get("recurrence")); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("calendar.typeError"); }
        LocalDate date = date(form.get("date"));
        LocalTime start = time(form.get("startTime")), end = time(form.get("endTime"));
        if (end != null && (start == null || !end.isAfter(start))) throw new IllegalArgumentException("calendar.timeError");
        LocalDate until = null;
        if (recurrence == BandCalendarEvent.Recurrence.WEEKLY) {
            until = date(form.get("repeatUntil"));
            if (until.isBefore(date)) throw new IllegalArgumentException("calendar.repeatError");
        } else if (!form.get("repeatUntil").isEmpty()) throw new IllegalArgumentException("calendar.repeatError");
        event.update(title, type, date, start, end, location, notes, recurrence, until);
        return events.save(event);
    }
    @Transactional
    public void delete(User actor, Long bandId, Long id) {
        accessible(actor, bandId, true);
        events.delete(events.findForUpdate(id, bandId).orElseThrow(BandCalendarService::notFound));
    }
    public record Entry(Long id, String type, String title, LocalDate date, LocalTime startTime, LocalTime endTime,
                        String location, String notes, boolean concert, boolean cancelled, boolean recurring, String link) {
        public String anchor() { return (concert ? "concert-" : "event-") + id + "-" + date; }
    }
    @Transactional(readOnly = true)
    public List<Entry> entries(User actor, Long bandId, LocalDate start, LocalDate end) {
        accessible(actor, bandId, false);
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > 42) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        List<Entry> entries = new ArrayList<>();
        for (BandCalendarEvent event : events.overlapping(bandId, start, end, BandCalendarEvent.Recurrence.WEEKLY)) {
            LocalDate day = event.getDate();
            if (event.getRecurrence() == BandCalendarEvent.Recurrence.WEEKLY) {
                if (day.isBefore(start)) day = day.plusWeeks((ChronoUnit.DAYS.between(day, start) + 6) / 7);
                LocalDate last = event.getRepeatUntil().isBefore(end) ? event.getRepeatUntil() : end;
                while (!day.isAfter(last)) { entries.add(entry(event, day)); day = day.plusWeeks(1); }
            } else if (!day.isBefore(start)) entries.add(entry(event, day));
        }
        for (Concert concert : concerts.forCalendar(bandId, start, end, ConcertParticipation.Status.ACCEPTED)) {
            String location = concert.getVenue() + ((concert.getCity() == null || concert.getCity().isBlank()) ? "" : " · " + concert.getCity());
            entries.add(new Entry(concert.getId(), "CONCERT", concert.getTitle(), concert.getDate(), null, null,
                    location, concert.getDescription(), true, concert.isCancelled(), false, "/concerts/" + concert.getId()));
        }
        entries.sort(Comparator.comparing(Entry::date).thenComparing(Entry::startTime, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Entry::title, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::type).thenComparing(Entry::id));
        return List.copyOf(entries);
    }
    private static Entry entry(BandCalendarEvent event, LocalDate date) {
        return new Entry(event.getId(), event.getType().name(), event.getTitle(), date, event.getStartTime(), event.getEndTime(),
                event.getLocation(), event.getNotes(), false, false, event.getRecurrence() == BandCalendarEvent.Recurrence.WEEKLY, null);
    }
    private static LocalDate date(String value) {
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeParseException("Invalid date", value, 0);
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1) throw new DateTimeParseException("Invalid year", value, 0);
            return date;
        } catch (DateTimeParseException e) { throw new IllegalArgumentException("calendar.dateError"); }
    }
    private static LocalTime time(String value) {
        if (value.isEmpty()) return null;
        try {
            if (!value.matches("[0-9]{2}:[0-9]{2}")) throw new DateTimeParseException("Invalid time", value, 0);
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) { throw new IllegalArgumentException("calendar.timeError"); }
    }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
