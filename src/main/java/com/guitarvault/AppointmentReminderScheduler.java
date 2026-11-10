package com.guitarvault;

import java.time.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.data.domain.PageRequest;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.appointment-reminders.enabled", havingValue = "true", matchIfMissing = true)
public class AppointmentReminderScheduler {
    private final LuthierAppointmentRepository appointments;
    private final AppointmentReminderService reminders;
    public AppointmentReminderScheduler(LuthierAppointmentRepository appointments, AppointmentReminderService reminders) {
        this.appointments = appointments; this.reminders = reminders;
    }
    @Scheduled(fixedDelayString = "${app.appointment-reminders.interval-ms:60000}", initialDelay = 60000)
    public void sendDue() {
        var now = Instant.now();
        for (Long id : appointments.dueReminders(now, now.plus(Duration.ofHours(24)), PageRequest.of(0, 100))) {
            try { reminders.send(id); }
            catch (RuntimeException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Appointment reminder processing failed for appointment {}", id); }
        }
    }
}
