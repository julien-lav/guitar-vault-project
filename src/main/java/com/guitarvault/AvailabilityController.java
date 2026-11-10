package com.guitarvault;

import java.security.Principal;
import java.time.*;
import java.util.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/workshop/availability")
public class AvailabilityController {
    private final AvailabilityService availability;
    private final InstrumentService instruments;
    public AvailabilityController(AvailabilityService availability, InstrumentService instruments) { this.availability = availability; this.instruments = instruments; }
    @ModelAttribute public void privatePage(HttpServletResponse response) { response.setHeader("Cache-Control", "private, no-store"); }
    @GetMapping("/calendar")
    public String overview(Principal principal, @RequestParam(required = false) LocalDate date,
            @RequestParam(defaultValue = "month") String view, Model model) {
        var actor = instruments.currentUser(principal); var workshop = availability.owner(actor);
        var calendar = availability.calendar(workshop, date);
        var selected = calendar.date();
        model.addAttribute("overview", availability.overview(actor, selected, view));
        model.addAttribute("calendar", calendar); model.addAttribute("calendarView", view);
        model.addAttribute("calendarZone", ZoneId.of(calendar.settings().getTimeZone()));
        var previous = view.equals("month") ? selected.withDayOfMonth(1).minusMonths(1) : selected.minusWeeks(1);
        var next = view.equals("month") ? selected.withDayOfMonth(1).plusMonths(1) : selected.plusWeeks(1);
        model.addAttribute("previousDate", previous.isBefore(calendar.today()) ? (selected.isAfter(calendar.today()) ? calendar.today() : null) : previous);
        model.addAttribute("nextDate", next.isAfter(calendar.lastDate()) ? null : next);
        model.addAttribute("title", "availability.overview"); model.addAttribute("contentTemplate", "workshop-calendar");
        return "layout";
    }
    @PostMapping("/hours")
    public String hours(Principal principal, @RequestParam long version, @RequestParam(defaultValue = "") List<String> days,
            @RequestParam Map<String, String> params, Model model) {
        try { availability.saveHours(instruments.currentUser(principal), version, days, params); }
        catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage()); model.addAttribute("hoursForm", params); model.addAttribute("hoursDays", days);
            return index(principal, null, model);
        }
        return "redirect:/workshop/availability";
    }
    @GetMapping
    public String index(Principal principal, @RequestParam(required = false) LocalDate date, Model model) {
        var actor = instruments.currentUser(principal); var workshop = availability.owner(actor);
        var calendar = availability.calendar(workshop, date); var config = calendar.settings();
        model.addAttribute("calendarZone", ZoneId.of(config.getTimeZone()));
        model.addAttribute("workshop", workshop); model.addAttribute("calendar", calendar);
        model.addAttribute("blockedDates", availability.blockedDates(actor));
        model.addAttribute("bookings", availability.ownerBookings(actor, calendar.date()));
        Map<String, String> hours = new LinkedHashMap<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            var h = config.hours(day);
            hours.put(day + "-opens", h.getOpens().toString()); hours.put(day + "-closes", h.getCloses().toString());
            hours.put(day + "-breakStart", h.getBreakStart() == null ? "" : h.getBreakStart().toString());
            hours.put(day + "-breakEnd", h.getBreakEnd() == null ? "" : h.getBreakEnd().toString());
        }
        if (!model.containsAttribute("hoursForm")) model.addAttribute("hoursForm", hours);
        if (!model.containsAttribute("hoursDays")) model.addAttribute("hoursDays", config.getDays().stream().map(Enum::name).toList());
        model.addAttribute("days", DayOfWeek.values()); model.addAttribute("durations", AvailabilityService.DURATIONS);
        model.addAttribute("timeZones", ZoneId.getAvailableZoneIds().stream().sorted().toList());
        if (!model.containsAttribute("form")) {
            Map<String, Object> form = new LinkedHashMap<>(); form.put("enabled", config.isEnabled());
            form.put("version", config.getId() == null ? -1 : config.getVersion()); form.put("timeZone", config.getTimeZone());
            form.put("duration", config.getDurationMinutes()); form.put("opens", config.getOpensAt()); form.put("closes", config.getClosesAt());
            form.put("days", config.getDays().stream().map(Enum::name).toList()); model.addAttribute("form", form);
        }
        model.addAttribute("title", "availability.title"); model.addAttribute("contentTemplate", "workshop-availability"); return "layout";
    }
    @PostMapping
    public String save(Principal principal, @RequestParam long version, @RequestParam(defaultValue = "false") boolean enabled,
            @RequestParam(defaultValue = "") String timeZone, @RequestParam int duration,
            @RequestParam(defaultValue = "") String opens, @RequestParam(defaultValue = "") String closes,
            @RequestParam(defaultValue = "") List<String> days, Model model) {
        try { availability.save(instruments.currentUser(principal), version, enabled, timeZone, duration, opens, closes, days); }
        catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage()); model.addAttribute("form", Map.of("version",version,"enabled",enabled,
                    "timeZone",timeZone,"duration",duration,"opens",opens,"closes",closes,"days",days));
            return index(principal, null, model);
        }
        return "redirect:/workshop/availability";
    }
    @PostMapping("/blocks")
    public String block(Principal principal, @RequestParam long version, @RequestParam LocalDate date, Model model) {
        try { availability.block(instruments.currentUser(principal), version, date); }
        catch (IllegalArgumentException e) { model.addAttribute("error", e.getMessage()); return index(principal, null, model); }
        return "redirect:/workshop/availability";
    }
    @PostMapping("/blocks/remove")
    public String unblock(Principal principal, @RequestParam long version, @RequestParam LocalDate date) {
        availability.unblock(instruments.currentUser(principal), version, date); return "redirect:/workshop/availability";
    }
    @ExceptionHandler(ResponseStatusException.class)
    public String conflict(ResponseStatusException e, Model model, HttpServletResponse response) {
        if (e.getStatusCode() != HttpStatus.CONFLICT) throw e;
        response.setStatus(409); model.addAttribute("error", e.getReason()); return "availability-conflict";
    }
}
