package com.guitarvault;

import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/bands/{bandId}/calendar")
public class BandCalendarController {
    private final BandCalendarService service;
    private final InstrumentService instruments;
    public BandCalendarController(BandCalendarService service, InstrumentService instruments) { this.service = service; this.instruments = instruments; }
    @ModelAttribute
    public void privatePage(HttpServletResponse response) { response.setHeader("Cache-Control", "private, no-store"); }
    public record Day(LocalDate date, boolean currentMonth, boolean today, List<BandCalendarService.Entry> entries) { }
    @GetMapping
    public String calendar(Principal principal, @PathVariable Long bandId, @RequestParam(defaultValue = "") String month,
                           @RequestParam(defaultValue = "month") String view, Locale locale, Model model) {
        User actor = instruments.currentUser(principal);
        Band band = service.accessible(actor, bandId, false);
        YearMonth selected;
        try {
            selected = month.isBlank() ? YearMonth.now(ZoneOffset.UTC) : YearMonth.parse(month);
            if ((!month.isBlank() && !month.matches("[0-9]{4}-[0-9]{2}")) || selected.getYear() < 1 || selected.getYear() > 9999) throw new DateTimeParseException("Invalid month", month, 0);
        } catch (DateTimeParseException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        if (!Set.of("month", "list").contains(view)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        LocalDate first = selected.atDay(1);
        int offset = first.getDayOfWeek().getValue() - 1;
        int cells = ((offset + selected.lengthOfMonth() + 6) / 7) * 7;
        LocalDate start = view.equals("month") ? first.minusDays(offset) : first;
        LocalDate end = view.equals("month") ? start.plusDays(cells - 1) : selected.atEndOfMonth();
        var entries = service.entries(actor, bandId, start, end);
        var byDate = entries.stream().collect(Collectors.groupingBy(BandCalendarService.Entry::date));
        List<Day> days = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            days.add(new Day(day, YearMonth.from(day).equals(selected), day.equals(today), byDate.getOrDefault(day, List.of())));
        }
        model.addAttribute("band", band);
        model.addAttribute("canManageCalendar", band.getCreator().getId().equals(actor.getId()));
        model.addAttribute("selectedMonth", selected.toString()); model.addAttribute("calendarView", view);
        model.addAttribute("monthLabel", first.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)));
        model.addAttribute("previousMonth", selected.equals(YearMonth.of(1, 1)) ? null : selected.minusMonths(1).toString());
        model.addAttribute("nextMonth", selected.equals(YearMonth.of(9999, 12)) ? null : selected.plusMonths(1).toString());
        model.addAttribute("weekdays", Arrays.stream(DayOfWeek.values()).map(d -> d.getDisplayName(TextStyle.SHORT, locale)).toList());
        model.addAttribute("days", days); model.addAttribute("entries", entries);
        model.addAttribute("calendarTypes", List.of("CONCERT", "REHEARSAL", "STUDIO", "PROMO", "RELEASE", "OTHER"));
        model.addAttribute("title", "calendar.title"); model.addAttribute("contentTemplate", "band-calendar"); return "layout";
    }
    @GetMapping("/events/new")
    public String createForm(Principal principal, @PathVariable Long bandId, Model model) {
        return form(model, service.accessible(instruments.currentUser(principal), bandId, true), null);
    }
    @GetMapping("/events/{id}/edit")
    public String editForm(Principal principal, @PathVariable Long bandId, @PathVariable Long id, Model model) {
        BandCalendarEvent event = service.editable(instruments.currentUser(principal), bandId, id);
        if (!model.containsAttribute("form")) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("title", event.getTitle()); values.put("type", event.getType().name()); values.put("date", event.getDate().toString());
            values.put("startTime", value(event.getStartTime())); values.put("endTime", value(event.getEndTime()));
            values.put("location", event.getLocation()); values.put("notes", event.getNotes());
            values.put("recurrence", event.getRecurrence().name()); values.put("repeatUntil", value(event.getRepeatUntil()));
            model.addAttribute("form", values);
        }
        return form(model, event.getBand(), id);
    }
    @PostMapping("/events")
    public String create(Principal principal, @PathVariable Long bandId, @RequestParam Map<String, String> params, Model model, RedirectAttributes redirect) {
        return save(principal, bandId, null, params, model, redirect);
    }
    @PostMapping("/events/{id}")
    public String edit(Principal principal, @PathVariable Long bandId, @PathVariable Long id, @RequestParam Map<String, String> params, Model model, RedirectAttributes redirect) {
        return save(principal, bandId, id, params, model, redirect);
    }
    private String save(Principal principal, Long bandId, Long id, Map<String, String> params, Model model, RedirectAttributes redirect) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : List.of("title", "type", "date", "startTime", "endTime", "location", "notes", "repeatUntil")) values.put(field, params.getOrDefault(field, "").strip());
        values.put("recurrence", params.getOrDefault("recurrence", "NONE").strip());
        try {
            BandCalendarEvent event = service.save(instruments.currentUser(principal), bandId, id, values);
            redirect.addFlashAttribute("success", "calendar.saved");
            return "redirect:/bands/" + bandId + "/calendar?month=" + YearMonth.from(event.getDate()) + "&view=list";
        } catch (IllegalArgumentException e) {
            model.addAttribute("form", values); model.addAttribute("error", e.getMessage());
            return id == null ? createForm(principal, bandId, model) : editForm(principal, bandId, id, model);
        }
    }
    private String form(Model model, Band band, Long id) {
        model.addAttribute("band", band); model.addAttribute("eventId", id);
        model.addAttribute("eventTypes", BandCalendarEvent.Type.values()); model.addAttribute("recurrences", BandCalendarEvent.Recurrence.values());
        if (!model.containsAttribute("form")) model.addAttribute("form", Map.of("type", "REHEARSAL", "recurrence", "NONE"));
        model.addAttribute("title", id == null ? "calendar.create" : "calendar.edit"); model.addAttribute("contentTemplate", "band-calendar-form"); return "layout";
    }
    @GetMapping("/events/{id}/delete")
    public String deleteForm(Principal principal, @PathVariable Long bandId, @PathVariable Long id, Model model) {
        BandCalendarEvent event = service.editable(instruments.currentUser(principal), bandId, id);
        model.addAttribute("band", event.getBand()); model.addAttribute("event", event);
        model.addAttribute("title", "calendar.delete"); model.addAttribute("contentTemplate", "band-calendar-confirm"); return "layout";
    }
    @PostMapping("/events/{id}/delete")
    public String delete(Principal principal, @PathVariable Long bandId, @PathVariable Long id, RedirectAttributes redirect) {
        service.delete(instruments.currentUser(principal), bandId, id); redirect.addFlashAttribute("success", "calendar.deleted");
        return "redirect:/bands/" + bandId + "/calendar";
    }
    private static String value(Object value) { return value == null ? "" : value.toString(); }
}
