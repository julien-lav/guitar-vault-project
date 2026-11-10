package com.guitarvault;

import java.security.Principal;
import java.time.ZoneId;
import java.time.LocalDate;
import java.util.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class AppointmentController {
    private final AppointmentService service;
    private final LuthierAppointmentRepository appointments;
    private final AppointmentMessageRepository messages;
    private final InstrumentService instruments;
    private final AvailabilityService availability;
    public AppointmentController(AppointmentService service, LuthierAppointmentRepository appointments,
            AppointmentMessageRepository messages, InstrumentService instruments, AvailabilityService availability) {
        this.availability = availability; this.service = service; this.appointments = appointments; this.messages = messages; this.instruments = instruments;
    }
    @ModelAttribute public void privatePage(HttpServletResponse response) { response.setHeader("Cache-Control", "private, no-store"); }
    @GetMapping("/appointments")
    public String index(Principal principal, @RequestParam(defaultValue = "0") int page, Model model) {
        if (page < 0 || page > 100000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        model.addAttribute("appointmentPage", appointments.inbox(instruments.currentUser(principal).getId(), PageRequest.of(page, 20)));
        return page(model, "appointments", "appointment.title");
    }
    @GetMapping("/luthiers/{id}/contact")
    public String contactForm(Principal principal, @PathVariable Long id, Model model) {
        model.addAttribute("workshop", service.requestable(instruments.currentUser(principal), id));
        if (!model.containsAttribute("form")) model.addAttribute("form", Map.of());
        return page(model, "luthier-contact", "appointment.contact");
    }
    @PostMapping("/luthiers/{id}/contact")
    public String contact(Principal principal, @PathVariable Long id, @RequestParam Map<String, String> params, Model model) {
        try { return "redirect:/appointments/" + service.contact(instruments.currentUser(principal), id, values(params)).getId(); }
        catch (IllegalArgumentException e) {
            model.addAttribute("form", values(params)); model.addAttribute("error", e.getMessage());
            return contactForm(principal, id, model);
        }
    }
    @PostMapping("/appointments/{id}/reminder")
    public String reminder(Principal principal, @PathVariable Long id, @RequestParam long version,
            @RequestParam(defaultValue = "false") boolean enabled) {
        service.reminder(instruments.currentUser(principal), id, version, enabled);
        return "redirect:/appointments/" + id;
    }
    @GetMapping(value = "/appointments/{id}/calendar.ics", produces = "text/calendar;charset=UTF-8")
    @ResponseBody
    public String export(Principal principal, @PathVariable Long id, HttpServletResponse response) {
        var a = service.visible(instruments.currentUser(principal), id);
        if (a.getStatus() != LuthierAppointment.Status.CONFIRMED) throw new ResponseStatusException(HttpStatus.CONFLICT);
        response.setHeader("Content-Disposition", "attachment; filename=appointment-" + id + ".ics");
        return AppointmentCalendar.export(a);
    }
    @GetMapping("/luthiers/{id}/appointments/new")
    public String form(Principal principal, @PathVariable Long id, @RequestParam(required = false) LocalDate date, Model model) {
        var workshop = service.requestable(instruments.currentUser(principal), id);
        model.addAttribute("workshop", workshop);
        model.addAttribute("calendar", availability.calendar(workshop, date));
        if (!model.containsAttribute("form")) model.addAttribute("form", Map.of("timeZone", "UTC"));
        zones(model);
        return page(model, "appointment-form", "appointment.request");
    }
    @PostMapping("/luthiers/{id}/appointments")
    public String create(Principal principal, @PathVariable Long id, @RequestParam Map<String, String> params, Model model) {
        Map<String, String> form = values(params);
        try { return "redirect:/appointments/" + service.create(instruments.currentUser(principal), id, form).getId(); }
        catch (IllegalArgumentException e) {
            model.addAttribute("form", form); model.addAttribute("error", e.getMessage()); return form(principal, id, null, model);
        }
    }
    @GetMapping("/appointments/{id}")
    public String detail(Principal principal, @PathVariable Long id, @RequestParam(required = false) LocalDate date, Model model) {
        var actor = instruments.currentUser(principal);
        var a = service.visible(actor, id);
        model.addAttribute("appointment", a);
        model.addAttribute("calendar", availability.calendar(a.getWorkshop(), date));
        model.addAttribute("conversation", messages.findByAppointmentIdOrderByIdAsc(id));
        model.addAttribute("canRespond", a.isScheduled() && a.getStatus() == LuthierAppointment.Status.PENDING && !a.getProposer().getId().equals(actor.getId()));
        if (!model.containsAttribute("form")) model.addAttribute("form", Map.of("timeZone", a.getTimeZone() == null ? "UTC" : a.getTimeZone()));
        model.addAttribute("reminderEnabled", a.reminderEnabled(a.getRequester().getId().equals(actor.getId())));
        zones(model);
        return page(model, "appointment-detail", "appointment.detail");
    }
    @PostMapping("/appointments/{id}/messages")
    public String message(Principal principal, @PathVariable Long id, @RequestParam long version,
            @RequestParam(defaultValue = "") String message, Model model) {
        try { service.message(instruments.currentUser(principal), id, version, message); }
        catch (IllegalArgumentException e) {
            model.addAttribute("form", Map.of("message", message)); model.addAttribute("error", e.getMessage());
            return detail(principal, id, null, model);
        }
        return "redirect:/appointments/" + id;
    }
    @PostMapping("/appointments/{id}/proposals")
    public String propose(Principal principal, @PathVariable Long id, @RequestParam long version,
            @RequestParam Map<String, String> params, Model model) {
        var form = values(params);
        try { service.propose(instruments.currentUser(principal), id, version, form); }
        catch (IllegalArgumentException e) {
            model.addAttribute("form", form); model.addAttribute("error", e.getMessage()); return detail(principal, id, null, model);
        }
        return "redirect:/appointments/" + id;
    }
    @PostMapping("/appointments/{id}/{action:confirm|decline|cancel}")
    public String resolve(Principal principal, @PathVariable Long id, @PathVariable String action, @RequestParam long version) {
        service.resolve(instruments.currentUser(principal), id, version, action); return "redirect:/appointments/" + id;
    }
    @ExceptionHandler(ResponseStatusException.class)
    public String conflict(ResponseStatusException e, Model model, HttpServletResponse response) {
        if (e.getStatusCode() != HttpStatus.CONFLICT) throw e;
        response.setStatus(409); model.addAttribute("error", e.getReason()); return "appointment-conflict";
    }
    private static void zones(Model model) { model.addAttribute("timeZones", ZoneId.getAvailableZoneIds().stream().sorted().toList()); }
    private static Map<String, String> values(Map<String, String> params) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : List.of("title", "message", "proposedTime", "timeZone", "slot", "availabilityVersion")) result.put(key, params.getOrDefault(key, "").strip());
        return result;
    }
    private static String page(Model model, String template, String title) {
        model.addAttribute("contentTemplate", template); model.addAttribute("title", title); return "layout";
    }
}
