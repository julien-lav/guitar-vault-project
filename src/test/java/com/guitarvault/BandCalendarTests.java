package com.guitarvault;

import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class BandCalendarTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired BandRepository bands;
    @Autowired BandInvitationRepository invitations;
    @Autowired BandCalendarEventRepository events;
    @Autowired BandCalendarService service;
    @Autowired ConcertService concertService;
    @Autowired ConcertParticipationRepository participations;
    @Autowired EntityManager em;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    User alice, bob, outsider;
    Band band, other;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        alice = account("calendar-alice"); bob = account("calendar-bob"); outsider = account("calendar-other");
        band = band(alice, "Calendar Band"); other = band(bob, "Other Band");
        band.getMembers().add(bob); bands.flush();
    }
    @Test void membersCanReadBothViewsWithPrivateCachingAndOnlyOwnerHasManagementLinks() throws Exception {
        event("Private rehearsal", "2026-02-10", "REHEARSAL", "NONE", "");
        for (String view : List.of("month", "list")) {
            mvc.perform(get(base()).with(user(bob.getEmail())).param("month", "2026-02").param("view", view))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(model().attribute("canManageCalendar", false))
                    .andExpect(content().string(containsString("Private rehearsal")))
                    .andExpect(content().string(not(containsString(base() + "/events/new"))));
        }
        mvc.perform(get(base()).with(user(alice.getEmail())).param("month", "2026-02"))
                .andExpect(model().attribute("canManageCalendar", true))
                .andExpect(content().string(containsString(base() + "/events/new")))
                .andExpect(content().string(containsString("&amp;view=list#event-")));
        mvc.perform(get("/bands/" + band.getId()).with(user(bob.getEmail())))
                .andExpect(content().string(containsString(base())))
                .andExpect(content().string(not(containsString("Private rehearsal"))));
        mvc.perform(get("/bands/" + band.getId()).with(user(outsider.getEmail())))
                .andExpect(content().string(not(containsString(base()))));
    }
    @Test void outsidersAndUnacceptedInviteesCannotReadOrModifyEvenWithKnownEventIds() throws Exception {
        BandCalendarEvent event = event("Secret", "2026-02-10", "STUDIO", "NONE", "");
        invitations.saveAndFlush(new BandInvitation(band, outsider));
        for (String suffix : List.of("", "/events/new", "/events/" + event.getId() + "/edit", "/events/" + event.getId() + "/delete")) {
            mvc.perform(get(base() + suffix).with(user(outsider.getEmail()))).andExpect(status().isNotFound());
        }
        for (String suffix : List.of("/events", "/events/" + event.getId(), "/events/" + event.getId() + "/delete")) {
            mvc.perform(post(base() + suffix).with(user(outsider.getEmail())).with(csrf()).param("title", "Forged"))
                    .andExpect(status().isNotFound());
        }
        assertThat(events.findById(event.getId()).orElseThrow().getTitle()).isEqualTo("Secret");
    }
    @Test void ordinaryMembersCannotManageAndAccessIsRevokedWhenTheyLeave() throws Exception {
        BandCalendarEvent event = event("Secret", "2026-02-10", "STUDIO", "NONE", "");
        for (String suffix : List.of("/events/new", "/events/" + event.getId() + "/edit", "/events/" + event.getId() + "/delete")) {
            mvc.perform(get(base() + suffix).with(user(bob.getEmail()))).andExpect(status().isNotFound());
        }
        for (String suffix : List.of("/events", "/events/" + event.getId(), "/events/" + event.getId() + "/delete")) {
            mvc.perform(post(base() + suffix).with(user(bob.getEmail())).with(csrf())).andExpect(status().isNotFound());
        }
        band.getMembers().removeIf(u -> u.getId().equals(bob.getId())); bands.flush(); em.clear();
        mvc.perform(get(base()).with(user(bob.getEmail()))).andExpect(status().isNotFound());
    }
    @Test void ownerCanCreateEditAndDeleteButCannotMoveEventsAcrossBands() throws Exception {
        mvc.perform(post(base() + "/events").with(user(alice.getEmail())).with(csrf())
                        .param("title", "  Studio session ").param("type", "STUDIO").param("date", "2026-02-10")
                        .param("startTime", "14:00").param("endTime", "18:30").param("location", "  Studio A  ")
                        .param("notes", "  Private budget  ").param("bandId", other.getId().toString()).param("creatorId", bob.getId().toString()))
                .andExpect(redirectedUrl(base() + "?month=2026-02&view=list"));
        events.flush(); em.clear();
        BandCalendarEvent event = events.findAll().getFirst();
        assertThat(event.getBand().getId()).isEqualTo(band.getId()); assertThat(event.getTitle()).isEqualTo("Studio session");
        assertThat(event.getLocation()).isEqualTo("Studio A"); assertThat(event.getNotes()).isEqualTo("Private budget");
        assertThat(event.getStartTime()).isEqualTo(LocalTime.of(14, 0));
        mvc.perform(get(base() + "/events/" + event.getId() + "/edit").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("form", hasEntry("notes", "Private budget")));
        Band owned = band(alice, "Second owned band");
        mvc.perform(post("/bands/" + owned.getId() + "/calendar/events/" + event.getId()).with(user(alice.getEmail())).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post(base() + "/events/" + event.getId()).with(user(alice.getEmail())).with(csrf())
                        .param("title", "Updated").param("type", "PROMO").param("date", "2026-03-10"))
                .andExpect(redirectedUrl(base() + "?month=2026-03&view=list"));
        mvc.perform(get(base() + "/events/" + event.getId() + "/delete").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(post(base() + "/events/" + event.getId() + "/delete").with(user(alice.getEmail())).with(csrf()))
                .andExpect(redirectedUrl(base()));
        events.flush(); em.clear(); assertThat(events.count()).isZero();
    }
    @Test void weeklySeriesExpandsAcrossMonthBoundariesAndStopsAtInclusiveEnd() throws Exception {
        event("Weekly", "2026-01-27", "REHEARSAL", "WEEKLY", "2026-03-03");
        var february = entries("2026-02", "list");
        assertThat(february).extracting(BandCalendarService.Entry::date).containsExactly(
                LocalDate.parse("2026-02-03"), LocalDate.parse("2026-02-10"), LocalDate.parse("2026-02-17"), LocalDate.parse("2026-02-24"));
        assertThat(entries("2026-03", "list")).extracting(BandCalendarService.Entry::date).containsExactly(LocalDate.parse("2026-03-03"));
        assertThat(entries("2026-04", "list")).isEmpty(); assertThat(events.count()).isEqualTo(1);
        assertThat(entries("2026-02", "month")).hasSize(5);
        event("Leap week", "2028-02-22", "REHEARSAL", "WEEKLY", "2028-02-29");
        assertThat(entries("2028-02", "list")).extracting(BandCalendarService.Entry::date)
                .containsExactly(LocalDate.parse("2028-02-22"), LocalDate.parse("2028-02-29"));
    }
    @Test void editingAndDeletingSeriesAffectsEveryOccurrenceAndIsExplicitInUi() throws Exception {
        BandCalendarEvent event = event("Weekly", "2026-01-27", "REHEARSAL", "WEEKLY", "2026-03-03");
        mvc.perform(get(base() + "/events/" + event.getId() + "/edit").with(user(alice.getEmail())))
                .andExpect(content().string(containsString("toute la série")));
        mvc.perform(post(base() + "/events/" + event.getId()).with(user(alice.getEmail())).with(csrf())
                        .param("title", "New weekly title").param("type", "REHEARSAL").param("date", "2026-01-28")
                        .param("recurrence", "WEEKLY").param("repeatUntil", "2026-02-04"))
                .andExpect(status().is3xxRedirection());
        assertThat(entries("2026-02", "list")).extracting(BandCalendarService.Entry::date).containsExactly(LocalDate.parse("2026-02-04"));
        mvc.perform(get(base() + "/events/" + event.getId() + "/delete").with(user(alice.getEmail())))
                .andExpect(content().string(containsString("Toutes les occurrences")));
        mvc.perform(post(base() + "/events/" + event.getId() + "/delete").with(user(alice.getEmail())).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(entries("2026-02", "list")).isEmpty();
    }
    @Test void concertsStaySynchronizedWithAcceptedParticipationEditsCancellationAndWithdrawal() throws Exception {
        Concert own = concert(alice, band, "Own show", "2026-02-05");
        Concert invited = concert(bob, other, "Shared show", "2026-02-06");
        concertService.invite(bob, invited.getId(), band.getId());
        Long invitationId = participations.findByConcertIdAndBandId(invited.getId(), band.getId()).orElseThrow().getId();
        assertThat(entries("2026-02", "list")).extracting(BandCalendarService.Entry::id).containsExactly(own.getId());
        concertService.respond(alice, invitationId, "accept");
        assertThat(entries("2026-02", "list")).hasSize(2);
        concertService.save(bob, null, invited.getId(), concertFields("Changed show", "2026-02-08"), null, false);
        assertThat(entries("2026-02", "list")).extracting(BandCalendarService.Entry::date)
                .containsExactly(LocalDate.parse("2026-02-05"), LocalDate.parse("2026-02-08"));
        concertService.respond(alice, invitationId, "remove"); assertThat(entries("2026-02", "list")).hasSize(1);
        concertService.invite(bob, invited.getId(), band.getId()); concertService.respond(alice, invitationId, "accept");
        concertService.cancel(bob, invited.getId());
        var cancelled = entries("2026-02", "list").getLast(); assertThat(cancelled.cancelled()).isTrue();
        assertThat(cancelled.concert()).isTrue(); assertThat(cancelled.link()).isEqualTo("/concerts/" + invited.getId());
        assertThat(events.count()).isZero();
    }
    @Test void privateNotesAreEscapedAndNeverAppearOnPublicBandOrMusicianPages() throws Exception {
        Map<String, String> form = fields("Private <script>event</script>", "2026-02-10", "PROMO", "NONE", "");
        form.put("notes", "secret-note-token <script>notes</script>"); service.save(alice, band.getId(), null, form);
        mvc.perform(get(base()).with(user(bob.getEmail())).param("month", "2026-02").param("view", "list"))
                .andExpect(content().string(containsString("&lt;script&gt;notes&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>notes</script>"))));
        for (String path : List.of("/bands", "/bands/" + band.getId(), "/musicians/" + alice.getId())) {
            mvc.perform(get(path).with(user(alice.getEmail())))
                    .andExpect(content().string(not(containsString("secret-note-token"))));
        }
    }
    @Test void validatesFieldsDatesRecurrenceAndTimesBeforeChangingAnExistingEvent() throws Exception {
        BandCalendarEvent event = event("Original", "2026-02-10", "STUDIO", "NONE", "");
        for (var invalid : List.of(Map.entry("title", " "), Map.entry("title", "x".repeat(151)),
                Map.entry("location", "x".repeat(201)), Map.entry("notes", "x".repeat(5001)))) {
            invalid(event.getId(), invalid.getKey(), invalid.getValue(), "calendar.fieldsError");
        }
        for (String date : List.of("", "wrong", "0000-01-01", "2026-02-30")) invalid(event.getId(), "date", date, "calendar.dateError");
        invalid(event.getId(), "type", "CONCERT", "calendar.typeError"); invalid(event.getId(), "recurrence", "DAILY", "calendar.typeError");
        for (String time : List.of("24:00", "1:00", "12:99")) invalid(event.getId(), "startTime", time, "calendar.timeError");
        invalid(event.getId(), "endTime", "16:00", "calendar.timeError");
        invalid(event.getId(), "repeatUntil", "2026-03-01", "calendar.repeatError");
        var request = post(base() + "/events/" + event.getId()).with(user(alice.getEmail())).with(csrf())
                .param("title", "Changed").param("type", "STUDIO").param("date", "2026-02-10").param("startTime", "18:00").param("endTime", "17:00");
        mvc.perform(request).andExpect(model().attribute("error", "calendar.timeError"));
        mvc.perform(post(base() + "/events/" + event.getId()).with(user(alice.getEmail())).with(csrf())
                        .param("title", "Changed").param("type", "REHEARSAL").param("date", "2026-02-10")
                        .param("recurrence", "WEEKLY").param("repeatUntil", "2026-02-09"))
                .andExpect(model().attribute("error", "calendar.repeatError"));
        events.flush(); em.clear(); assertThat(events.findById(event.getId()).orElseThrow().getTitle()).isEqualTo("Original");
    }
    @Test void handlesEmptyMonthsMonthBoundariesAndLocalizedFormsAndRejectsInvalidNavigation() throws Exception {
        mvc.perform(get(base()).with(user(bob.getEmail())).param("month", "2026-02").param("view", "list").param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("No events this month.")))
                .andExpect(model().attribute("monthLabel", "February 2026"))
                .andExpect(content().string(not(containsString("??"))));
        for (String month : List.of("bad", "2026-13", "0000-01", "10000-01")) {
            mvc.perform(get(base()).with(user(alice.getEmail())).param("month", month)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(base()).with(user(alice.getEmail())).param("view", "unknown")).andExpect(status().isBadRequest());
        for (String month : List.of("0001-01", "9999-12")) {
            mvc.perform(get(base()).with(user(alice.getEmail())).param("month", month)).andExpect(status().isOk());
        }
        mvc.perform(get(base() + "/events/new").with(user(alice.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Add an event")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("??"))));
    }
    @Test void requiresLoginAndCsrfAndReturns404ForMissingResources() throws Exception {
        BandCalendarEvent event = event("Secret", "2026-02-10", "STUDIO", "NONE", "");
        mvc.perform(get(base())).andExpect(redirectedUrl("/login"));
        mvc.perform(post(base() + "/events").with(user(alice.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(post(base() + "/events/" + event.getId() + "/delete").with(user(alice.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(get(base() + "/events/9223372036854775807/edit").with(user(alice.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get("/bands/9223372036854775807/calendar").with(user(alice.getEmail()))).andExpect(status().isNotFound());
    }
    private String base() { return "/bands/" + band.getId() + "/calendar"; }
    @SuppressWarnings("unchecked") private List<BandCalendarService.Entry> entries(String month, String view) throws Exception {
        return (List<BandCalendarService.Entry>) mvc.perform(get(base()).with(user(alice.getEmail())).param("month", month).param("view", view))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("entries");
    }
    private void invalid(Long id, String field, String value, String error) throws Exception {
        var values = fields("Changed", "2026-02-10", "STUDIO", "NONE", ""); values.put(field, value);
        var request = post(base() + "/events/" + id).with(user(alice.getEmail())).with(csrf()); values.forEach(request::param);
        mvc.perform(request).andExpect(status().isOk()).andExpect(model().attribute("error", error))
                .andExpect(model().attribute("form", hasEntry(field, value.strip())));
    }
    private BandCalendarEvent event(String title, String date, String type, String recurrence, String until) {
        return service.save(alice, band.getId(), null, fields(title, date, type, recurrence, until));
    }
    private Map<String, String> fields(String title, String date, String type, String recurrence, String until) {
        return new LinkedHashMap<>(Map.of("title", title, "date", date, "type", type, "recurrence", recurrence,
                "repeatUntil", until, "startTime", "", "endTime", "", "location", "", "notes", ""));
    }
    private Map<String, String> concertFields(String title, String date) { return Map.of("title", title, "date", date, "venue", "Club", "city", "", "description", ""); }
    private Concert concert(User actor, Band organizer, String title, String date) { return concertService.save(actor, organizer.getId(), null, concertFields(title, date), null, false); }
    private Band band(User owner, String name) { Band band = new Band(owner); band.setName(name); band.getMembers().add(owner); return bands.saveAndFlush(band); }
    private User account(String name) {
        User user = new User(); user.setUsername(name); user.setEmail(name + "@example.org"); user.setPasswordHash("unused-test-hash"); user.setEnabled(true); return users.saveAndFlush(user);
    }
}
