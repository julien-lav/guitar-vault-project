package com.guitarvault;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** A private, one-event calendar download, with stable identity for re-imports. */
final class AppointmentCalendar {
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private AppointmentCalendar() { }
    static String export(LuthierAppointment a) {
        StringBuilder out = new StringBuilder();
        for (String line : new String[]{"BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Guitar Vault//Appointments//EN",
                "CALSCALE:GREGORIAN", "BEGIN:VEVENT", "UID:appointment-" + a.getId() + "@guitar-vault",
                "DTSTAMP:" + UTC.format(a.getUpdatedAt()), "SEQUENCE:" + a.getVersion(),
                "DTSTART:" + UTC.format(a.getProposedAt()), "DTEND:" + UTC.format(a.getEndsAt()),
                "SUMMARY:" + escape(a.getTitle()), "LOCATION:" + escape(a.getWorkshop().getLocation()),
                "STATUS:CONFIRMED", "END:VEVENT", "END:VCALENDAR"}) {
            int bytes = 0;
            for (int cp : line.codePoints().toArray()) {
                String c = new String(Character.toChars(cp)); int size = c.getBytes(StandardCharsets.UTF_8).length;
                if (bytes + size > 75) { out.append("\r\n "); bytes = 1; }
                out.append(c); bytes += size;
            }
            out.append("\r\n");
        }
        return out.toString();
    }
    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n")
                .replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,");
    }
}
