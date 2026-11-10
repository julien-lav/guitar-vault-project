package com.guitarvault;

import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentReminderService {
    private final LuthierAppointmentRepository appointments;
    private final AvailabilityService availability;
    private final MailMessages mail;
    public AppointmentReminderService(LuthierAppointmentRepository appointments, AvailabilityService availability, MailMessages mail) {
        this.appointments = appointments; this.availability = availability; this.mail = mail;
    }
    @Transactional
    public void send(Long id) {
        var candidate = appointments.findById(id).orElse(null);
        if (candidate == null) return;
        availability.lockWorkshop(candidate.getWorkshop().getId());
        var a = appointments.findForUpdate(id, candidate.getRequester().getId()).orElseThrow();
        entityManager.refresh(a);
        var now = Instant.now();
        if (a.getStatus() != LuthierAppointment.Status.CONFIRMED || !a.getProposedAt().isAfter(now)
                || a.getProposedAt().isAfter(now.plus(Duration.ofHours(24)))) return;
        if (!a.getRequester().isEnabled() || !a.getWorkshop().getUser().isEnabled()) return;
        for (boolean requester : new boolean[]{true, false}) {
            if (!a.reminderEnabled(requester) || a.reminderSent(requester)) continue;
            try {
                mail.appointmentReminder(requester ? a.getRequester() : a.getWorkshop().getUser(), a);
                a.reminded(requester);
            } catch (org.springframework.mail.MailException e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Appointment reminder delivery failed for appointment {}", id);
            }
        }
        appointments.flush();
    }
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
}
