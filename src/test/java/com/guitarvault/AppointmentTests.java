package com.guitarvault;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class AppointmentTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired LuthierProfileRepository workshops;
    @Autowired LuthierAppointmentRepository appointments;
    @Autowired AppointmentMessageRepository messages;
    @Autowired AppointmentService service;
    @Autowired AppointmentReminderService reminders;
    @Autowired NotificationRepository notifications;
    @Autowired InstrumentRepository instruments;
    @Autowired InstrumentTransferService transfers;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    User musician, luthier, outsider, admin;
    LuthierProfile workshop;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        musician = account("appointment-musician"); luthier = account("appointment-luthier");
        outsider = account("appointment-outsider"); admin = account("appointment-admin");
        admin.setRoles(Set.of(Role.ROLE_ADMIN)); users.flush();
        workshop = new LuthierProfile(luthier); workshop.update("Test workshop", "Paris", "Setups", "About", "contact", true);
        workshops.saveAndFlush(workshop);
    }
    @Test void musicianCanContactAWorkshopWithoutOwningAnInstrumentAndBothReceiveNotifications() throws Exception {
        mvc.perform(get("/luthiers/" + workshop.getId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("/appointments/new")));
        mvc.perform(get("/luthiers/" + workshop.getId() + "/appointments/new").with(user(musician.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().string(containsString("Request an appointment")));
        mvc.perform(post("/luthiers/" + workshop.getId() + "/appointments").with(user(musician.getEmail())).with(csrf())
                        .param("title", "Setup visit").param("message", "Private appointment <script>").param("timeZone", "Europe/Paris")
                        .param("proposedTime", future()))
                .andExpect(status().is3xxRedirection());
        var a = appointments.findAll().getFirst();
        assertThat(a.getStatus()).isEqualTo(LuthierAppointment.Status.PENDING);
        assertThat(a.getProposedAt()).isEqualTo(LocalDateTime.parse(future()).atZone(ZoneId.of("Europe/Paris")).toInstant());
        assertThat(messages.findByAppointmentIdOrderByIdAsc(a.getId())).hasSize(1);
        assertThat(notifications.countByRecipientIdAndReadAtIsNull(luthier.getId())).isEqualTo(1);
        mvc.perform(get(path(a)).with(user(luthier.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Confirm this time")))
                .andExpect(content().string(containsString("Private appointment &lt;script&gt;")))
                .andExpect(content().string(not(containsString("??"))));
        service.message(luthier, a.getId(), a.getVersion(), "Please bring the guitar.");
        assertThat(notifications.countByRecipientIdAndReadAtIsNull(musician.getId())).isEqualTo(1);
        var notice = notifications.findByRecipientIdOrderByIdDesc(musician.getId(), org.springframework.data.domain.Pageable.unpaged()).getContent().getFirst();
        mvc.perform(post("/notifications/" + notice.getId() + "/open").with(user(musician.getEmail())).with(csrf()))
                .andExpect(redirectedUrl(path(a)));
    }
    @Test void counterProposalRequiresTheOtherParticipantAndKeepsEarlierTimesAndMessages() throws Exception {
        var a = create(); Instant original = a.getProposedAt();
        mvc.perform(post(path(a) + "/confirm").with(user(musician.getEmail())).with(csrf()).param("version", "0"))
                .andExpect(status().isConflict());
        var next = new HashMap<>(form()); next.put("proposedTime", LocalDateTime.parse(future()).plusDays(1).toString());
        next.put("message", "Could you come the following day?");
        service.propose(luthier, a.getId(), a.getVersion(), next);
        mvc.perform(post(path(a) + "/confirm").with(user(luthier.getEmail())).with(csrf()).param("version", Long.toString(a.getVersion())))
                .andExpect(status().isConflict());
        service.resolve(musician, a.getId(), a.getVersion(), "confirm");
        assertThat(a.getStatus()).isEqualTo(LuthierAppointment.Status.CONFIRMED);
        var history = messages.findByAppointmentIdOrderByIdAsc(a.getId());
        assertThat(history).extracting(AppointmentMessage::getKind).containsExactly(AppointmentMessage.Kind.PROPOSAL, AppointmentMessage.Kind.PROPOSAL, AppointmentMessage.Kind.CONFIRMED);
        assertThat(history.getFirst().getProposedAt()).isEqualTo(original);
        service.message(musician, a.getId(), a.getVersion(), "Thank you!");
        mvc.perform(get(path(a)).with(user(musician.getEmail())).param("lang", "fr"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Confirmé")))
                .andExpect(content().string(not(containsString("??"))));
        assertThatThrownBy(() -> service.propose(luthier, a.getId(), a.getVersion(), next)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void onlyTheTwoParticipantsCanReadListOrMutateEvenIfTheOtherUserIsAnAdministrator() throws Exception {
        var a = create();
        for (User actor : List.of(outsider, admin)) {
            mvc.perform(get(path(a)).with(user(actor.getEmail()))).andExpect(status().isNotFound());
            mvc.perform(get("/appointments").with(user(actor.getEmail())))
                    .andExpect(content().string(not(containsString(a.getTitle()))));
            for (String action : List.of("messages", "proposals", "confirm", "decline", "cancel")) {
                mvc.perform(post(path(a) + "/" + action).with(user(actor.getEmail())).with(csrf())
                                .param("version", "0").param("message", "Forged").param("timeZone", "UTC").param("proposedTime", future()))
                        .andExpect(status().isNotFound());
            }
        }
        assertThat(messages.findByAppointmentIdOrderByIdAsc(a.getId())).hasSize(1);
        mvc.perform(get("/appointments")).andExpect(redirectedUrl("/login"));
        mvc.perform(get(path(a))).andExpect(redirectedUrl("/login"));
        mvc.perform(post(path(a) + "/cancel").with(user(musician.getEmail())).param("version", "0"))
                .andExpect(status().isForbidden());
    }
    @Test void invalidFieldsPastDatesZonesAndClockChangesNeverCreatePartialRequests() throws Exception {
        var bad = List.of(Map.entry("title", " "), Map.entry("message", "x".repeat(2001)),
                Map.entry("proposedTime", LocalDateTime.now(ZoneOffset.UTC).minusDays(1).toString()),
                Map.entry("proposedTime", "not-a-date"), Map.entry("timeZone", "Moon/Invalid"));
        for (var entry : bad) {
            var values = new HashMap<>(form()); values.put(entry.getKey(), entry.getValue());
            assertThatThrownBy(() -> service.create(musician, workshop.getId(), values)).isInstanceOf(IllegalArgumentException.class);
        }
        int year = LocalDate.now(ZoneOffset.UTC).getYear() + 1;
        for (int month : List.of(3, 10)) {
            var values = new HashMap<>(form()); values.put("timeZone", "Europe/Paris");
            values.put("proposedTime", LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.SUNDAY)).atTime(2, 30).toString());
            assertThatThrownBy(() -> service.create(musician, workshop.getId(), values)).isInstanceOf(IllegalArgumentException.class).hasMessage("appointment.timeError");
        }
        mvc.perform(post("/luthiers/" + workshop.getId() + "/appointments").with(user(musician.getEmail())).with(csrf())
                        .param("title", "Kept subject").param("message", "Kept message").param("proposedTime", "invalid").param("timeZone", "UTC").param("lang", "en"))
                .andExpect(status().isOk()).andExpect(model().attribute("error", "appointment.timeError"))
                .andExpect(content().string(containsString("Kept subject"))).andExpect(content().string(containsString("Kept message")));
        assertThat(appointments.count()).isZero(); assertThat(notifications.count()).isZero();
    }
    @Test void selfRequestsAndUnpublishedHiddenOrInactiveWorkshopsAreRejected() throws Exception {
        assertThatThrownBy(() -> service.create(luthier, workshop.getId(), form())).isInstanceOf(ResponseStatusException.class);
        workshop.update("Test workshop", "Paris", "Setups", "About", "contact", false); workshops.flush();
        mvc.perform(get("/luthiers/" + workshop.getId() + "/appointments/new").with(user(musician.getEmail())))
                .andExpect(status().isNotFound());
        workshop.update("Test workshop", "Paris", "Setups", "About", "contact", true); workshops.flush();
        luthier.setEnabled(false); users.flush();
        assertThatThrownBy(() -> service.create(musician, workshop.getId(), form())).isInstanceOf(ResponseStatusException.class);
    }
    @Test void staleFormsCannotConfirmAnEarlierProposalOrOverwriteNewMessages() throws Exception {
        var a = create(); service.message(luthier, a.getId(), 0, "Updated conversation");
        for (String action : List.of("messages", "proposals", "confirm", "decline", "cancel")) {
            mvc.perform(post(path(a) + "/" + action).with(user(musician.getEmail())).with(csrf())
                            .param("version", "0").param("message", "Stale").param("timeZone", "UTC").param("proposedTime", future()))
                    .andExpect(status().isConflict()).andExpect(content().string(containsString("role=\"alert\"")));
        }
        assertThat(messages.findByAppointmentIdOrderByIdAsc(a.getId())).hasSize(2);
        assertThat(a.getStatus()).isEqualTo(LuthierAppointment.Status.PENDING);
    }
    @Test void declineAndCancellationCloseTheConversationButPreserveItsHistory() throws Exception {
        var a = create(); service.resolve(luthier, a.getId(), a.getVersion(), "decline");
        assertThat(a.getStatus()).isEqualTo(LuthierAppointment.Status.DECLINED);
        assertThatThrownBy(() -> service.message(musician, a.getId(), a.getVersion(), "Late message")).isInstanceOf(ResponseStatusException.class);
        var second = create(); service.resolve(luthier, second.getId(), second.getVersion(), "confirm");
        service.resolve(musician, second.getId(), second.getVersion(), "cancel");
        assertThat(second.getStatus()).isEqualTo(LuthierAppointment.Status.CANCELLED);
        assertThat(messages.findByAppointmentIdOrderByIdAsc(second.getId())).hasSize(3);
        mvc.perform(get(path(second)).with(user(luthier.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("id=\"appointment-reply\""))));
    }
    @Test void unpublishingDoesNotLoseExistingConversationsAndTransfersDoNotShareThem() throws Exception {
        var a = create();
        workshop.update("Test workshop", "Paris", "Setups", "About", "contact", false); workshops.flush();
        service.message(luthier, a.getId(), a.getVersion(), "Still arranging our visit.");
        var instrument = instruments.saveAndFlush(new Instrument("Appointment guitar", "Brand", 2000, musician, "Actif"));
        var transfer = transfers.propose(musician, instrument.getId(), outsider.getUsername(), true);
        transfers.resolve(outsider, transfer.getId(), InstrumentTransfer.Status.ACCEPTED);
        mvc.perform(get(path(a)).with(user(outsider.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get(path(a)).with(user(musician.getEmail()))).andExpect(status().isOk());
        assertThat(a.getRequester().getId()).isEqualTo(musician.getId());
    }
    @Test void contactNeedsNoTimeAndCanBecomeAnAppointmentWithoutLosingTheConversation() throws Exception {
        mvc.perform(get("/luthiers/" + workshop.getId() + "/contact").with(user(musician.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("datetime-local"))));
        mvc.perform(post("/luthiers/" + workshop.getId() + "/contact").with(user(musician.getEmail())).with(csrf())
                .param("title", "Question about a repair").param("message", "Can you repair a cracked top?"))
                .andExpect(status().is3xxRedirection());
        var a = appointments.findAll().getFirst();
        assertThat(a.isScheduled()).isFalse();
        assertThatThrownBy(() -> service.resolve(luthier, a.getId(), a.getVersion(), "confirm"))
                .isInstanceOf(ResponseStatusException.class);
        mvc.perform(get(path(a)).with(user(luthier.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("no appointment time agreed")))
                .andExpect(content().string(not(containsString("Confirm this time"))));
        mvc.perform(get("/appointments").with(user(musician.getEmail()))).andExpect(status().isOk());
        mvc.perform(get(path(a)).with(user(outsider.getEmail()))).andExpect(status().isNotFound());
        service.message(luthier, a.getId(), a.getVersion(), "Yes, please bring it in.");
        service.propose(luthier, a.getId(), a.getVersion(), form());
        service.resolve(musician, a.getId(), a.getVersion(), "confirm");
        assertThat(a.getStatus()).isEqualTo(LuthierAppointment.Status.CONFIRMED);
        assertThat(messages.findByAppointmentIdOrderByIdAsc(a.getId())).hasSize(4);
    }
    @Test void privateCalendarExportUsesUtcAndDoesNotIncludeConversationMessages() throws Exception {
        var data = new HashMap<>(form()); data.put("title", "Réglage, guitare;\nBEGIN:EVIL");
        var a = service.create(musician, workshop.getId(), data);
        mvc.perform(get(path(a) + "/calendar.ics").with(user(musician.getEmail()))).andExpect(status().isConflict());
        service.resolve(luthier, a.getId(), a.getVersion(), "confirm");
        var result = mvc.perform(get(path(a) + "/calendar.ics").with(user(musician.getEmail())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=appointment-" + a.getId() + ".ics"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(result).contains("BEGIN:VCALENDAR\r\n", "SUMMARY:Réglage\\, guitare\\;\\nBEGIN:EVIL", "DTSTART:", "DTEND:")
                .doesNotContain("Private appointment details", "\r\nBEGIN:EVIL");
        mvc.perform(get(path(a) + "/calendar.ics").with(user(outsider.getEmail()))).andExpect(status().isNotFound());
        service.resolve(luthier, a.getId(), a.getVersion(), "cancel");
        mvc.perform(get(path(a) + "/calendar.ics").with(user(luthier.getEmail()))).andExpect(status().isConflict());
    }
    @Test void remindersAreOptInPerParticipantAndSentOnlyOnceForAnUpcomingConfirmedVisit() {
        var data = new HashMap<>(form()); data.put("proposedTime", LocalDateTime.now(ZoneOffset.UTC).plusHours(2).withSecond(0).withNano(0).toString());
        var a = service.create(musician, workshop.getId(), data);
        service.resolve(luthier, a.getId(), a.getVersion(), "confirm");
        reminders.send(a.getId());
        org.mockito.Mockito.verifyNoInteractions(mail);
        service.reminder(musician, a.getId(), a.getVersion(), true);
        service.reminder(luthier, a.getId(), a.getVersion(), true);
        service.reminder(luthier, a.getId(), a.getVersion(), false);
        reminders.send(a.getId()); reminders.send(a.getId());
        var sent = org.mockito.ArgumentCaptor.forClass(org.springframework.mail.SimpleMailMessage.class);
        org.mockito.Mockito.verify(mail).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly(musician.getEmail());
        assertThat(sent.getValue().getText()).contains(path(a)).doesNotContain(a.getTitle(), "Private appointment details");
        assertThat(a.reminderSent(true)).isTrue(); assertThat(a.reminderSent(false)).isFalse();
    }
    @Test void failedEmailCanRetryAndCancelledOrDistantVisitsAreNeverReminded() {
        var distant = create(); service.resolve(luthier, distant.getId(), distant.getVersion(), "confirm");
        service.reminder(musician, distant.getId(), distant.getVersion(), true); reminders.send(distant.getId());
        var data = new HashMap<>(form()); data.put("proposedTime", LocalDateTime.now(ZoneOffset.UTC).plusHours(2).toString());
        var a = service.create(musician, workshop.getId(), data);
        service.reminder(musician, a.getId(), a.getVersion(), true);
        reminders.send(a.getId()); org.mockito.Mockito.verifyNoInteractions(mail);
        service.resolve(luthier, a.getId(), a.getVersion(), "confirm");
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("offline")).doNothing().when(mail).send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));
        reminders.send(a.getId()); assertThat(a.reminderSent(true)).isFalse();
        reminders.send(a.getId()); assertThat(a.reminderSent(true)).isTrue();
        service.reminder(luthier, a.getId(), a.getVersion(), true);
        service.resolve(luthier, a.getId(), a.getVersion(), "cancel"); reminders.send(a.getId());
        org.mockito.Mockito.verify(mail, org.mockito.Mockito.times(2)).send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));
        assertThat(a.reminderSent(false)).isFalse();
    }
    private Map<String, String> form() { return Map.of("title", "Private setup visit", "message", "Private appointment details", "proposedTime", future(), "timeZone", "UTC"); }
    private String future() { return LocalDate.now(ZoneOffset.UTC).plusDays(7).atTime(10, 0).toString(); }
    private LuthierAppointment create() { return service.create(musician, workshop.getId(), form()); }
    private String path(LuthierAppointment a) { return "/appointments/" + a.getId(); }
    private User account(String name) { var u = new User(); u.setUsername(name); u.setEmail(name + "@example.org"); u.setPasswordHash("unused"); return users.saveAndFlush(u); }
}
