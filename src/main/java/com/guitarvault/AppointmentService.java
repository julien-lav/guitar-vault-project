package com.guitarvault;

import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AppointmentService {
    private final LuthierAppointmentRepository appointments;
    private final AppointmentMessageRepository messages;
    private final LuthierService luthiers;
    private final NotificationService notifications;
    private final AvailabilityService availability;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    public AppointmentService(LuthierAppointmentRepository appointments, AppointmentMessageRepository messages,
            LuthierService luthiers, NotificationService notifications, AvailabilityService availability) {
        this.availability = availability; this.appointments = appointments; this.messages = messages; this.luthiers = luthiers; this.notifications = notifications;
    }
    public LuthierProfile requestable(User actor, Long workshopId) {
        LuthierProfile workshop = luthiers.publicProfile(workshopId);
        if (!active(actor) || workshop.getUser().getId().equals(actor.getId())) throw notFound();
        return workshop;
    }
    @Transactional
    public LuthierAppointment create(User actor, Long workshopId, Map<String, String> form) {
        availability.lockWorkshop(workshopId);
        var workshop = requestable(actor, workshopId);
        String title = required(form.get("title"), 150), body = required(form.get("message"), 2000);
        var proposal = proposal(workshop, form);
        var appointment = new LuthierAppointment(actor, workshop, title, proposal.time(), proposal.zone());
        appointment.duration(proposal.duration());
        appointments.saveAndFlush(appointment);
        record(actor, appointment, AppointmentMessage.Kind.PROPOSAL, body);
        return appointment;
    }
    @Transactional
    public LuthierAppointment contact(User actor, Long workshopId, Map<String, String> form) {
        availability.lockWorkshop(workshopId);
        var workshop = requestable(actor, workshopId);
        var a = appointments.saveAndFlush(new LuthierAppointment(actor, workshop,
                required(form.get("title"), 150), null, null));
        record(actor, a, AppointmentMessage.Kind.MESSAGE, required(form.get("message"), 2000));
        return a;
    }
    @Transactional
    public void reminder(User actor, Long id, long version, boolean enabled) {
        var a = locked(actor, id, version);
        if (!a.isOpen()) throw conflict("appointment.closed");
        a.reminder(a.getRequester().getId().equals(actor.getId()), enabled);
        appointments.flush();
    }
    public LuthierAppointment visible(User actor, Long id) {
        var appointment = appointments.findById(id).orElseThrow(AppointmentService::notFound);
        authorize(actor, appointment); return appointment;
    }
    private void authorize(User actor, LuthierAppointment a) {
        if (!a.getRequester().getId().equals(actor.getId()) && !a.getWorkshop().getUser().getId().equals(actor.getId())) throw notFound();
    }
    private LuthierAppointment locked(User actor, Long id, long version) {
        // Authorize in the locking query: do not preload an entity that can become stale while waiting.
        Long workshopId = appointments.visibleWorkshopId(id, actor.getId()).orElseThrow(AppointmentService::notFound);
        availability.lockWorkshop(workshopId);
        var a = appointments.findForUpdate(id, actor.getId()).orElseThrow(AppointmentService::notFound);
        entityManager.refresh(a);
        authorize(actor, a);
        if (a.getVersion() != version) throw conflict("appointment.stale");
        if (!active(actor) || !active(a.getRequester()) || !active(a.getWorkshop().getUser())) throw conflict("appointment.inactive");
        return a;
    }
    @Transactional
    public void message(User actor, Long id, long version, String body) {
        var a = locked(actor, id, version);
        if (!a.isOpen()) throw conflict("appointment.closed");
        body = required(body, 2000);
        a.touch();
        record(actor, a, AppointmentMessage.Kind.MESSAGE, body);
    }
    @Transactional
    public void propose(User actor, Long id, long version, Map<String, String> form) {
        var a = locked(actor, id, version);
        if (a.getStatus() != LuthierAppointment.Status.PENDING) throw conflict("appointment.closed");
        String body = required(form.get("message"), 2000);
        var proposal = proposal(a.getWorkshop(), form);
        a.propose(actor, proposal.time(), proposal.zone());
        a.duration(proposal.duration());
        record(actor, a, AppointmentMessage.Kind.PROPOSAL, body);
    }
    @Transactional
    public void resolve(User actor, Long id, long version, String action) {
        var a = locked(actor, id, version);
        LuthierAppointment.Status next;
        switch (action) {
            case "cancel" -> { if (!a.isOpen()) throw conflict("appointment.closed"); next = LuthierAppointment.Status.CANCELLED; }
            case "confirm", "decline" -> {
                if (a.getStatus() != LuthierAppointment.Status.PENDING || a.getProposer().getId().equals(actor.getId()) || !a.isScheduled())
                    throw conflict("appointment.notYourTurn");
                if (action.equals("confirm") && !a.getProposedAt().isAfter(Instant.now())) throw conflict("appointment.past");
                if (action.equals("confirm")) availability.confirm(a);
                next = action.equals("confirm") ? LuthierAppointment.Status.CONFIRMED : LuthierAppointment.Status.DECLINED;
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        a.resolve(next);
        record(actor, a, AppointmentMessage.Kind.valueOf(next.name()), "");
    }
    private AvailabilityService.Proposal proposal(LuthierProfile workshop, Map<String, String> form) {
        boolean calendar = availability.enabled(workshop);
        Instant manual = (calendar || !form.getOrDefault("slot", "").isBlank()) ? null : time(form.get("proposedTime"), form.get("timeZone"));
        return availability.proposal(workshop, form, manual, form.get("timeZone"));
    }
    private void record(User actor, LuthierAppointment a, AppointmentMessage.Kind kind, String body) {
        messages.save(new AppointmentMessage(a, actor, kind, body));
        appointments.flush();
        User recipient = a.getRequester().getId().equals(actor.getId()) ? a.getWorkshop().getUser() : a.getRequester();
        notifications.send(actor, recipient, Notification.Kind.LUTHIER_APPOINTMENT, a.getTitle(), "/appointments/" + a.getId());
    }
    private static Instant time(String value, String zone) {
        try {
            if (!ZoneId.getAvailableZoneIds().contains(zone)) throw new IllegalArgumentException();
            var local = LocalDateTime.parse(value);
            if (local.getYear() < 1 || local.getYear() > 9999) throw new IllegalArgumentException();
            var offsets = ZoneId.of(zone).getRules().getValidOffsets(local);
            // Reject nonexistent and ambiguous local times instead of silently moving the appointment.
            if (offsets.size() != 1) throw new IllegalArgumentException();
            var instant = local.atOffset(offsets.getFirst()).toInstant();
            if (!instant.isAfter(Instant.now())) throw new IllegalArgumentException();
            return instant;
        } catch (RuntimeException e) { throw new IllegalArgumentException("appointment.timeError"); }
    }
    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.strip().length() > max) throw new IllegalArgumentException("appointment.fieldsError");
        return value.strip();
    }
    private static boolean active(User u) { return u.isEnabled() && !u.isSuspended(); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
    private static ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT, reason); }
}
