package com.guitarvault;

import jakarta.persistence.EntityManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
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
class ConcertTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired BandRepository bands;
    @Autowired ConcertRepository concerts;
    @Autowired ConcertPosterRepository posters;
    @Autowired ConcertParticipationRepository participations;
    @Autowired ConcertService service;
    @Autowired EntityManager em;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    User alice, bob, outsider;
    Band organizer, guest;
    LocalDate today;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        alice = account("concert-alice"); bob = account("concert-bob"); outsider = account("concert-other");
        organizer = band(alice, "Organizer Band"); guest = band(bob, "Guest Band"); today = LocalDate.now(ZoneOffset.UTC);
    }

    @Test void createsConcertWithTrustedOrganizerAndPosterAndShowsItOnBandProfile() throws Exception {
        byte[] image = image(3, 4);
        mvc.perform(multipart(createPath()).file(upload(image)).with(user(alice.getEmail())).with(csrf())
                        .param("title", "  Rock Night  ").param("date", today.plusDays(7).toString()).param("venue", "  Le Club  ")
                        .param("city", " Rennes ").param("description", "Live <script>show</script>")
                        .param("organizerId", guest.getId().toString()).param("cancelled", "true").param("participants", guest.getId().toString()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("success", "concert.saved"));
        concerts.flush(); em.clear();
        Concert concert = concerts.findAll().getFirst();
        assertThat(concert.getTitle()).isEqualTo("Rock Night");
        assertThat(concert.getVenue()).isEqualTo("Le Club");
        assertThat(concert.getCity()).isEqualTo("Rennes");
        assertThat(concert.getOrganizer().getId()).isEqualTo(organizer.getId());
        assertThat(concert.isCancelled()).isFalse(); assertThat(participations.count()).isZero();
        mvc.perform(get(path(concert)).with(user(outsider.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("&lt;script&gt;show&lt;/script&gt;")))
                .andExpect(model().attribute("confirmedParticipants", hasSize(0)))
                .andExpect(content().string(not(containsString("Guest Band"))));
        mvc.perform(get("/bands/" + organizer.getId()).with(user(bob.getEmail())))
                .andExpect(model().attribute("upcomingConcerts", hasSize(1)))
                .andExpect(model().attribute("concertPosterIds", hasItem(concert.getId())))
                .andExpect(content().string(containsString(path(concert) + "/poster")))
                .andExpect(content().string(containsString("Rock Night")));
        mvc.perform(get(path(concert) + "/poster").with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(content().bytes(image))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test void separatesUpcomingPastAndCancelledConcertsWithNearestDatesFirst() throws Exception {
        Concert later = concert("Later", today.plusDays(9));
        Concert past = concert("Past", today.minusDays(3));
        Concert now = concert("Today", today);
        Concert cancelled = concert("Cancelled", today.plusDays(2)); service.cancel(alice, cancelled.getId());
        mvc.perform(get("/bands/" + organizer.getId()).with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("upcomingConcerts", contains(now, later)))
                .andExpect(model().attribute("pastConcerts", contains(past)))
                .andExpect(model().attribute("cancelledConcerts", contains(cancelled)));
        mvc.perform(get("/bands/" + guest.getId()).with(user(bob.getEmail())))
                .andExpect(model().attribute("upcomingConcerts", hasSize(0)));
    }

    @Test void participationRequiresGuestManagerConsentAndAppearsOnBothBandProfiles() throws Exception {
        Concert concert = concert("Shared Show", today.plusDays(4)); invite(concert);
        Long id = participation(concert).getId();
        mvc.perform(get("/bands/" + guest.getId()).with(user(outsider.getEmail())))
                .andExpect(model().attribute("upcomingConcerts", hasSize(0)));
        mvc.perform(get(path(concert)).with(user(outsider.getEmail())))
                .andExpect(model().attribute("confirmedParticipants", hasSize(0)))
                .andExpect(model().attribute("pendingParticipants", hasSize(0)))
                .andExpect(content().string(not(containsString("Guest Band"))));
        mvc.perform(get("/concert-invitations").with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("concertInvitations", hasSize(1)));
        mvc.perform(get(path(concert)).with(user(bob.getEmail())))
                .andExpect(model().attribute("myConcertInvitations", hasSize(1)))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        action(id, bob, "accept", path(concert));
        participations.flush(); em.clear();
        assertThat(participation(concert).getStatus()).isEqualTo(ConcertParticipation.Status.ACCEPTED);
        for (Band band : List.of(organizer, guest)) {
            mvc.perform(get("/bands/" + band.getId()).with(user(outsider.getEmail())))
                    .andExpect(model().attribute("upcomingConcerts", hasSize(1)))
                    .andExpect(content().string(containsString("Shared Show")));
        }
        mvc.perform(get(path(concert)).with(user(outsider.getEmail())))
                .andExpect(content().string(containsString("Guest Band")))
                .andExpect(model().attribute("confirmedParticipants", hasSize(1)));
    }

    @Test void onlyOrganizerMayEditInviteOrCancelAndOnlyInvitedManagerMayRespond() throws Exception {
        organizer.getMembers().add(bob); guest.getMembers().add(outsider); bands.flush();
        Concert concert = concert("Protected", today); invite(concert); Long id = participation(concert).getId();
        for (User actor : List.of(bob, outsider)) {
            mvc.perform(get(path(concert) + "/edit").with(user(actor.getEmail()))).andExpect(status().isNotFound());
            mvc.perform(post(path(concert)).with(user(actor.getEmail())).with(csrf()).param("title", "Changed")).andExpect(status().isNotFound());
            mvc.perform(post(path(concert) + "/invitations").with(user(actor.getEmail())).with(csrf()).param("bandId", guest.getId().toString())).andExpect(status().isNotFound());
            mvc.perform(post(path(concert) + "/cancel").with(user(actor.getEmail())).with(csrf())).andExpect(status().isNotFound());
        }
        for (User actor : List.of(alice, outsider)) {
            mvc.perform(post("/concert-participations/" + id + "/accept").with(user(actor.getEmail())).with(csrf())).andExpect(status().isNotFound());
        }
        mvc.perform(get("/concert-invitations").with(user(outsider.getEmail())))
                .andExpect(model().attribute("concertInvitations", hasSize(0)));
        assertThat(concert.getTitle()).isEqualTo("Protected");
        assertThat(participation(concert).getStatus()).isEqualTo(ConcertParticipation.Status.PENDING);
    }

    @Test void duplicateInvitationsAreRejectedAndDeclinedInvitationsMayBeSentAgain() throws Exception {
        Concert concert = concert("Show", today); invite(concert); Long id = participation(concert).getId();
        mvc.perform(post(path(concert) + "/invitations").with(user(alice.getEmail())).with(csrf()).param("bandId", guest.getId().toString()))
                .andExpect(flash().attribute("error", "concert.existsError"));
        action(id, bob, "decline", path(concert)); invite(concert);
        action(id, bob, "accept", path(concert));
        mvc.perform(post("/concert-participations/" + id + "/accept").with(user(bob.getEmail())).with(csrf()))
                .andExpect(status().isConflict());
        assertThat(participations.count()).isEqualTo(1);
        mvc.perform(post(path(concert) + "/invitations").with(user(alice.getEmail())).with(csrf()).param("bandId", organizer.getId().toString()))
                .andExpect(flash().attribute("error", "concert.organizerError"));
    }

    @Test void eitherManagerMayRemoveParticipationWithConfirmationAndOtherBandsRemainIntact() throws Exception {
        Concert concert = concert("Show", today); invite(concert); Long id = participation(concert).getId(); action(id, bob, "accept", path(concert));
        mvc.perform(get("/concert-participations/" + id + "/remove").with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(get("/concert-participations/" + id + "/remove").with(user(outsider.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(post("/concert-participations/" + id + "/remove").with(user(outsider.getEmail())).with(csrf())).andExpect(status().isNotFound());
        action(id, bob, "remove", path(concert));
        mvc.perform(get("/bands/" + guest.getId()).with(user(outsider.getEmail())))
                .andExpect(model().attribute("upcomingConcerts", hasSize(0)));
        invite(concert); action(id, alice, "remove", path(concert));
        assertThat(bands.count()).isEqualTo(2); assertThat(concerts.count()).isEqualTo(1);
    }

    @Test void organizerCanUpdateFieldsReplaceAndRemovePosterWithoutChangingParticipants() throws Exception {
        Concert concert = concert("Original", today); invite(concert); action(participation(concert).getId(), bob, "accept", path(concert));
        byte[] image = image(2, 3);
        mvc.perform(multipart(path(concert)).file(upload(image)).with(user(alice.getEmail())).with(csrf())
                        .param("title", "Updated").param("date", today.plusDays(10).toString()).param("venue", "New venue").param("removePoster", "true"))
                .andExpect(redirectedUrl(path(concert)));
        concerts.flush(); em.clear();
        assertThat(concerts.findById(concert.getId()).orElseThrow().getTitle()).isEqualTo("Updated");
        assertThat(participation(concert).getStatus()).isEqualTo(ConcertParticipation.Status.ACCEPTED);
        mvc.perform(get(path(concert) + "/edit").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("hasPoster", true));
        mvc.perform(multipart(path(concert)).file(upload(image(3, 2))).with(user(alice.getEmail())).with(csrf())
                        .param("title", "Updated").param("date", today.toString()).param("venue", "New venue"))
                .andExpect(redirectedUrl(path(concert)));
        assertThat(posters.count()).isEqualTo(1);
        mvc.perform(post(path(concert)).with(user(alice.getEmail())).with(csrf())
                        .param("title", "Updated").param("date", today.toString()).param("venue", "New venue").param("removePoster", "true"))
                .andExpect(redirectedUrl(path(concert)));
        posters.flush(); em.clear();
        mvc.perform(get(path(concert) + "/poster").with(user(bob.getEmail()))).andExpect(status().isNotFound());
    }

    @Test void invalidFieldsAndDatesNeverCreatePartialConcertAndKeepEnteredValues() throws Exception {
        for (var entry : List.of(java.util.Map.entry("title", " "), java.util.Map.entry("title", "x".repeat(151)),
                java.util.Map.entry("venue", " "), java.util.Map.entry("venue", "x".repeat(201)),
                java.util.Map.entry("city", "x".repeat(101)), java.util.Map.entry("description", "x".repeat(5001)))) {
            var request = post(createPath()).with(user(alice.getEmail())).with(csrf()).param("date", today.toString());
            if (!entry.getKey().equals("title")) request.param("title", "Keep title");
            if (!entry.getKey().equals("venue")) request.param("venue", "Keep venue");
            mvc.perform(request.param(entry.getKey(), entry.getValue())).andExpect(status().isOk())
                    .andExpect(model().attribute("error", "concert.fieldsError"))
                    .andExpect(model().attribute("form", hasEntry(entry.getKey(), entry.getValue().strip())));
        }
        for (String date : List.of("", "invalid", "0000-01-01", "2026-02-30", "99999-01-01")) {
            mvc.perform(post(createPath()).with(user(alice.getEmail())).with(csrf()).param("title", "Keep title").param("venue", "Club").param("date", date))
                    .andExpect(model().attribute("error", "concert.dateError"))
                    .andExpect(content().string(containsString("Keep title")));
        }
        assertThat(concerts.count()).isZero(); assertThat(posters.count()).isZero();
    }

    @Test void invalidPosterDoesNotChangeSavedConcertOrExistingPoster() throws Exception {
        Concert concert = concert("Original", today); byte[] original = image(2, 2);
        service.save(alice, null, concert.getId(), fields("Original", today), upload(original), false);
        concerts.flush(); em.clear();
        for (byte[] data : List.of("<svg>fake</svg>".getBytes(), new byte[5 * 1024 * 1024 + 1], image(4097, 1))) {
            mvc.perform(multipart(path(concert)).file(upload(data)).with(user(alice.getEmail())).with(csrf())
                            .param("title", "Changed").param("date", today.toString()).param("venue", "New venue").param("removePoster", "true"))
                    .andExpect(status().isOk()).andExpect(model().attribute("error", "concert.posterError"))
                    .andExpect(content().string(containsString("Changed")));
        }
        concerts.flush(); em.clear();
        assertThat(concerts.findById(concert.getId()).orElseThrow().getTitle()).isEqualTo("Original");
        assertThat(posters.findByConcertId(concert.getId()).orElseThrow().getData()).isEqualTo(original);
    }

    @Test void cancelledShowsRemainVisibleAndRejectFurtherMutationsAndInvitations() throws Exception {
        Concert concert = concert("Cancelled show", today.plusDays(7)); invite(concert); Long id = participation(concert).getId();
        Band confirmedGuest = band(outsider, "Confirmed Guest");
        service.invite(alice, concert.getId(), confirmedGuest.getId());
        Long confirmedId = participations.findByConcertIdAndBandId(concert.getId(), confirmedGuest.getId()).orElseThrow().getId();
        action(confirmedId, outsider, "accept", path(concert));
        mvc.perform(get(path(concert) + "/cancel").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(post(path(concert) + "/cancel").with(user(alice.getEmail())).with(csrf()))
                .andExpect(redirectedUrl(path(concert)));
        for (String endpoint : List.of(path(concert), path(concert) + "/invitations", path(concert) + "/cancel")) {
            mvc.perform(post(endpoint).with(user(alice.getEmail())).with(csrf()).param("bandId", guest.getId().toString()))
                    .andExpect(status().isConflict());
        }
        mvc.perform(post("/concert-participations/" + id + "/accept").with(user(bob.getEmail())).with(csrf())).andExpect(status().isConflict());
        mvc.perform(get("/concert-invitations").with(user(bob.getEmail())))
                .andExpect(model().attribute("concertInvitations", hasSize(0)));
        mvc.perform(get(path(concert)).with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("myConcertInvitations", hasSize(0)))
                .andExpect(content().string(containsString("Annulé")));
        mvc.perform(get("/bands/" + confirmedGuest.getId()).with(user(bob.getEmail())))
                .andExpect(model().attribute("upcomingConcerts", hasSize(0)))
                .andExpect(model().attribute("cancelledConcerts", hasSize(1)));
    }

    @Test void inactiveGroupsCannotBeInvitedAndInactiveOrganizerPreventsAcceptance() throws Exception {
        Concert concert = concert("Show", today);
        bob.setEnabled(false); users.flush();
        mvc.perform(post(path(concert) + "/invitations").with(user(alice.getEmail())).with(csrf()).param("bandId", guest.getId().toString()))
                .andExpect(flash().attribute("error", "concert.inactiveError"));
        bob.setEnabled(true); users.flush(); invite(concert);
        alice.setEnabled(false); users.flush();
        mvc.perform(post("/concert-participations/" + participation(concert).getId() + "/accept").with(user(bob.getEmail())).with(csrf()))
                .andExpect(flash().attribute("error", "concert.inactiveError"));
        assertThat(participation(concert).getStatus()).isEqualTo(ConcertParticipation.Status.PENDING);
    }

    @Test void authenticationCsrfLocalizationAndMissingResourcesAreHandled() throws Exception {
        Concert concert = concert("Show", today);
        for (String endpoint : List.of(path(concert), path(concert) + "/poster", createPath() + "/new", "/concert-invitations")) {
            mvc.perform(get(endpoint)).andExpect(redirectedUrl("/login"));
        }
        mvc.perform(post(createPath()).with(user(alice.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(post(path(concert) + "/invitations").with(user(alice.getEmail())).param("bandId", guest.getId().toString())).andExpect(status().isForbidden());
        for (String endpoint : List.of(createPath() + "/new", path(concert), "/concert-invitations", path(concert) + "/edit")) {
            mvc.perform(get(endpoint).with(user(alice.getEmail())).param("lang", "en"))
                    .andExpect(status().isOk()).andExpect(content().string(not(containsString("??"))));
        }
        mvc.perform(get("/concerts/9223372036854775807").with(user(bob.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(post("/concert-participations/9223372036854775807/accept").with(user(bob.getEmail())).with(csrf())).andExpect(status().isNotFound());
    }

    String createPath() { return "/bands/" + organizer.getId() + "/concerts"; }
    String path(Concert concert) { return "/concerts/" + concert.getId(); }
    java.util.Map<String, String> fields(String title, LocalDate date) { return java.util.Map.of("title", title, "date", date.toString(), "venue", "Club", "city", "", "description", ""); }
    Concert concert(String title, LocalDate date) { return service.save(alice, organizer.getId(), null, fields(title, date), null, false); }
    void invite(Concert concert) throws Exception {
        mvc.perform(post(path(concert) + "/invitations").with(user(alice.getEmail())).with(csrf()).param("bandId", guest.getId().toString()))
                .andExpect(flash().attribute("success", "concert.invited"));
    }
    ConcertParticipation participation(Concert concert) { return participations.findByConcertIdAndBandId(concert.getId(), guest.getId()).orElseThrow(); }
    void action(Long id, User actor, String action, String redirect) throws Exception {
        mvc.perform(post("/concert-participations/" + id + "/" + action).with(user(actor.getEmail())).with(csrf()))
                .andExpect(redirectedUrl(redirect)).andExpect(flash().attribute("success", "concert.done." + action));
    }
    Band band(User owner, String name) { Band band = new Band(owner); band.setName(name); band.getMembers().add(owner); return bands.saveAndFlush(band); }
    User account(String name) {
        User user = new User(); user.setUsername(name); user.setEmail(name + "@example.org"); user.setPasswordHash("unused-test-hash"); user.setEnabled(true); return users.saveAndFlush(user);
    }
    static MockMultipartFile upload(byte[] bytes) { return new MockMultipartFile("poster", "poster.png", "text/plain", bytes); }
    static byte[] image(int width, int height) throws Exception {
        var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out); return out.toByteArray();
    }
}
