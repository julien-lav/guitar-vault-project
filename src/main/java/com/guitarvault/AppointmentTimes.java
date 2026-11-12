package com.guitarvault;

import java.time.*;

/** Rejects ambiguous/nonexistent local hours and past appointments. */
final class AppointmentTimes {
    private AppointmentTimes() { }
    static Instant parse(String value, String zone) {
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
}
