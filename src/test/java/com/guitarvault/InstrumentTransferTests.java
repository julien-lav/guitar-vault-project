package com.guitarvault;

import java.util.List;
import jakarta.persistence.EntityManager;
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
class InstrumentTransferTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired InstrumentRepository instruments;
    @Autowired InstrumentTransferRepository transfers;
    @Autowired InstrumentOwnershipRepository ownerships;
    @Autowired InstrumentTransferService service;
    @Autowired LuthierProfileRepository workshops;
    @Autowired LuthierInterventionRepository interventions;
    @Autowired LuthierAssessmentRepository assessments;
    @Autowired EntityManager em;
    @Autowired NotificationRepository notifications;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    User alice, bob, carol, admin, luthier;
    Instrument instrument;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        alice = account("transfer-alice"); bob = account("transfer-bob"); carol = account("transfer-carol");
        admin = account("transfer-admin"); admin.setRoles(java.util.Set.of(Role.ROLE_ADMIN));
        luthier = account("transfer-luthier"); users.flush();
        instrument = new Instrument("Original Passport", "Fender", 1987, alice, "En vente");
        instrument.setSerialNumber("UNCHANGED-SERIAL"); instrument.setNotes("PRIVATE FINANCES");
        instrument.setHistory("PRIVATE LEGACY HISTORY"); instrument.setPreviousOwner("PRIVATE NAME");
        instrument.getRecordings().add("Public album credit"); instrument.getConcerts().add("Festival performance");
        instrument.getPhotos().add(new InstrumentPhoto(instrument, "image/png", new byte[]{1,2,3}));
        instruments.saveAndFlush(instrument);
    }

    @Test void proposalShowsOnlyBasicDetailsAndRemainsPrivateToParticipants() throws Exception {
        mvc.perform(get(formPath()).with(user(alice.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(post(createPath()).with(user(alice.getEmail())).with(csrf()).param("recipient", bob.getUsername()).param("confirmed", "true")
                        .param("senderId", carol.getId().toString()).param("status", "ACCEPTED"))
                .andExpect(status().is3xxRedirection());
        InstrumentTransfer t = transfers.findBySenderIdOrRecipientIdOrderByIdDesc(alice.getId(), alice.getId()).getFirst();
        assertThat(t.getStatus()).isEqualTo(InstrumentTransfer.Status.PENDING);
        assertThat(notifications.countByRecipientIdAndReadAtIsNull(bob.getId())).isEqualTo(1);
        assertThat(t.getSender().getId()).isEqualTo(alice.getId());
        assertThat(instrument.getOwner().getId()).isEqualTo(alice.getId());
        mvc.perform(get("/transfers/" + t.getId()).with(user(bob.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("UNCHANGED-SERIAL")))
                .andExpect(content().string(not(containsString("PRIVATE FINANCES"))))
                .andExpect(content().string(not(containsString("PRIVATE LEGACY HISTORY"))))
                .andExpect(content().string(not(containsString("Public album credit"))))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/transfers").with(user(bob.getEmail()))).andExpect(model().attribute("transfers", hasSize(1)));
        mvc.perform(get("/transfers").with(user(carol.getEmail()))).andExpect(model().attribute("transfers", hasSize(0)));
        for (User actor : List.of(carol, admin)) {
            mvc.perform(get("/transfers/" + t.getId()).with(user(actor.getEmail()))).andExpect(status().isNotFound());
            for (String action : List.of("accept", "decline", "cancel"))
                mvc.perform(post("/transfers/" + t.getId() + "/" + action).with(user(actor.getEmail())).with(csrf())).andExpect(status().isNotFound());
        }
        mvc.perform(get("/instruments/" + instrument.getId()).with(user(bob.getEmail()))).andExpect(status().isNotFound());
    }

    @Test void acceptancePreservesIdentityAndPeriodsWhileKeepingNotesAndPhotosWithTheirAuthor() throws Exception {
        Long photoId = instrument.getPhotos().getFirst().getId();
        var createdAt = instrument.getCreatedAt();
        em.createNativeQuery("update instrument_photos set private_owner_id = null where id = :id").setParameter("id", photoId).executeUpdate();
        em.clear();
        InstrumentTransfer t = proposal(alice, bob);
        mvc.perform(post("/transfers/" + t.getId() + "/accept").with(user(bob.getEmail())).with(csrf()))
                .andExpect(redirectedUrl("/transfers/" + t.getId()));
        em.flush(); em.clear();
        Instrument saved = instruments.findById(instrument.getId()).orElseThrow();
        assertThat(saved.getOwner().getId()).isEqualTo(bob.getId());
        assertThat(saved.getSerialNumber()).isEqualTo("UNCHANGED-SERIAL");
        assertThat(saved.getCreatedAt()).isCloseTo(createdAt, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS));
        assertThat(saved.getNotes()).isNull(); assertThat(saved.getHistory()).isNull(); assertThat(saved.getPreviousOwner()).isNull();
        assertThat(saved.getStatus()).isEqualTo("Actif");
        assertThat(saved.getRecordings()).containsExactly("Public album credit");
        var periods = ownerships.findByInstrumentIdOrderByStartedAtAsc(saved.getId());
        assertThat(periods).hasSize(2);
        assertThat(periods.getFirst().getEndedAt()).isEqualTo(periods.getLast().getStartedAt());
        assertThat(periods.getLast().getEndedAt()).isNull();
        assertThat(periods.getFirst().getNotes()).isEqualTo("PRIVATE FINANCES");
        mvc.perform(get("/instruments/" + saved.getId()).with(user(alice.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get("/instruments/" + saved.getId()).with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("PRIVATE"))))
                .andExpect(content().string(not(containsString("/photos/" + photoId))));
        mvc.perform(get("/instruments/" + saved.getId() + "/photos/" + photoId).with(user(bob.getEmail()))).andExpect(status().isNotFound());
        Long archive = periods.getFirst().getId();
        mvc.perform(get("/ownership/" + archive + "/private").with(user(alice.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("PRIVATE FINANCES")))
                .andExpect(content().string(containsString("PRIVATE LEGACY HISTORY")))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/ownership/" + archive + "/photos/" + photoId).with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1,2,3}));
        for (User actor : List.of(bob, carol, admin)) {
            mvc.perform(get("/ownership/" + archive + "/private").with(user(actor.getEmail()))).andExpect(status().isNotFound());
            mvc.perform(get("/ownership/" + archive + "/photos/" + photoId).with(user(actor.getEmail()))).andExpect(status().isNotFound());
        }
    }

    @Test void transferPreservesAnInstrumentsRepairStatus() {
        Instrument repair = instruments.saveAndFlush(new Instrument("Needs repair", "Brand", 1980, alice, "À réparer"));
        InstrumentTransfer t = service.propose(alice, repair.getId(), bob.getUsername(), true);
        service.resolve(bob, t.getId(), InstrumentTransfer.Status.ACCEPTED);
        assertThat(instruments.findById(repair.getId()).orElseThrow().getStatus()).isEqualTo("À réparer");
    }

    @Test void onlyRecipientCanAcceptOrDeclineAndOnlySenderCanCancel() throws Exception {
        InstrumentTransfer t = proposal(alice, bob);
        for (String action : List.of("accept", "decline"))
            mvc.perform(post("/transfers/" + t.getId() + "/" + action).with(user(alice.getEmail())).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/transfers/" + t.getId() + "/cancel").with(user(bob.getEmail())).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/transfers/" + t.getId() + "/decline").with(user(bob.getEmail())).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(post("/transfers/" + t.getId() + "/accept").with(user(bob.getEmail())).with(csrf()))
                .andExpect(status().isConflict()).andExpect(content().string(containsString("role=\"alert\"")));
        InstrumentTransfer next = proposal(alice, carol);
        mvc.perform(post("/transfers/" + next.getId() + "/cancel").with(user(alice.getEmail())).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(post("/transfers/" + next.getId() + "/accept").with(user(carol.getEmail())).with(csrf())).andExpect(status().isConflict());
        assertThat(instrument.getOwner().getId()).isEqualTo(alice.getId());
        assertThat(ownerships.findByInstrumentIdOrderByStartedAtAsc(instrument.getId())).hasSize(1);
    }

    @Test void multipleTransfersKeepContinuousPeriodsWithoutExposingOtherOwnerIdentities() throws Exception {
        InstrumentTransfer first = proposal(alice, bob);
        service.resolve(bob, first.getId(), InstrumentTransfer.Status.ACCEPTED);
        InstrumentTransfer second = proposal(bob, carol);
        service.resolve(carol, second.getId(), InstrumentTransfer.Status.ACCEPTED);
        em.flush(); em.clear();
        var periods = ownerships.findByInstrumentIdOrderByStartedAtAsc(instrument.getId());
        assertThat(periods).hasSize(3);
        assertThat(periods.get(0).getEndedAt()).isEqualTo(periods.get(1).getStartedAt());
        assertThat(periods.get(1).getEndedAt()).isEqualTo(periods.get(2).getStartedAt());
        for (User actor : List.of(alice, bob, carol)) {
            var result = mvc.perform(get("/instruments/" + instrument.getId() + "/ownership").with(user(actor.getEmail())).param("lang", "en"))
                    .andExpect(status().isOk());
            for (User other : List.of(alice, bob, carol))
                if (!other.getId().equals(actor.getId())) result.andExpect(content().string(not(containsString(other.getUsername()))));
        }
        mvc.perform(get("/instruments/" + instrument.getId() + "/ownership").with(user(admin.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(post("/transfers/" + first.getId() + "/accept").with(user(bob.getEmail())).with(csrf())).andExpect(status().isConflict());
        assertThat(instruments.findById(instrument.getId()).orElseThrow().getOwner().getId()).isEqualTo(carol.getId());
    }

    @Test void professionalReportsFollowThePassportWithoutPrivateRequestsOrRequesters() throws Exception {
        LuthierProfile workshop = new LuthierProfile(luthier);
        workshop.update("Repair workshop", "Paris", "Repairs", "Workshop", "contact", true); workshops.saveAndFlush(workshop);
        LuthierIntervention i = interventions.saveAndFlush(new LuthierIntervention(instrument, alice, workshop, LuthierIntervention.Kind.REPAIR, "PRIVATE REQUEST FINANCES"));
        assessments.saveAndFlush(new LuthierAssessment(i, luthier, 1, java.time.LocalDate.now(), LuthierAssessment.Conclusion.WORK_RECORDED,
                "Repair evidence <script>bad</script>", "Observed neck", "Electronics not inspected", ""));
        assessments.saveAndFlush(new LuthierAssessment(i, luthier, 2, java.time.LocalDate.now(), LuthierAssessment.Conclusion.WORK_RECORDED,
                "Corrected repair", "Observed neck", "Electronics not inspected", "Further inspection"));
        i.complete(); interventions.flush();
        InstrumentTransfer t = proposal(alice, bob);
        service.resolve(bob, t.getId(), InstrumentTransfer.Status.ACCEPTED);
        mvc.perform(get("/instruments/" + instrument.getId() + "/passport-reports").with(user(bob.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Corrected repair")))
                .andExpect(content().string(containsString("Previous report")))
                .andExpect(content().string(containsString("Current report")))
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(not(containsString("PRIVATE REQUEST"))))
                .andExpect(content().string(not(containsString(alice.getUsername()))))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/interventions/" + i.getId()).with(user(bob.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get("/interventions/" + i.getId()).with(user(alice.getEmail()))).andExpect(status().isOk());
        mvc.perform(get("/instruments/" + instrument.getId() + "/passport-reports").with(user(alice.getEmail()))).andExpect(status().isNotFound());
    }

    @Test void rejectsUnconfirmedSelfInactiveUnknownAndDuplicateRecipients() throws Exception {
        for (String username : List.of(alice.getUsername(), "unknown", "x".repeat(256)))
            mvc.perform(post(createPath()).with(user(alice.getEmail())).with(csrf()).param("recipient", username).param("confirmed", "true"))
                    .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
        mvc.perform(post(createPath()).with(user(alice.getEmail())).with(csrf()).param("recipient", bob.getUsername()))
                .andExpect(model().attribute("error", "transfer.invalid"));
        bob.setEnabled(false); users.flush();
        mvc.perform(post(createPath()).with(user(alice.getEmail())).with(csrf()).param("recipient", bob.getUsername()).param("confirmed", "true"))
                .andExpect(model().attribute("error", "transfer.recipientError"));
        bob.setEnabled(true); users.flush();
        proposal(alice, bob);
        mvc.perform(post(createPath()).with(user(alice.getEmail())).with(csrf()).param("recipient", carol.getUsername()).param("confirmed", "true"))
                .andExpect(status().isConflict());
        assertThat(transfers.count()).isEqualTo(1);
    }

    @Test void unresolvedWorkAndInactiveSenderBlockAcceptanceWithoutPartialUpdates() throws Exception {
        LuthierProfile workshop = new LuthierProfile(luthier);
        workshop.update("Workshop", "Paris", "Setup", "About", "contact", true); workshops.saveAndFlush(workshop);
        LuthierIntervention i = interventions.saveAndFlush(new LuthierIntervention(instrument, alice, workshop, LuthierIntervention.Kind.SETUP, "Work"));
        InstrumentTransfer t = proposal(alice, bob);
        mvc.perform(post("/transfers/" + t.getId() + "/accept").with(user(bob.getEmail())).with(csrf())).andExpect(status().isConflict());
        assertThat(instrument.getOwner().getId()).isEqualTo(alice.getId());
        assertThat(instrument.getNotes()).isEqualTo("PRIVATE FINANCES");
        assertThat(ownerships.findByInstrumentIdOrderByStartedAtAsc(instrument.getId())).hasSize(1);
        i.cancel(); interventions.flush(); alice.setEnabled(false); users.flush();
        mvc.perform(post("/transfers/" + t.getId() + "/accept").with(user(bob.getEmail())).with(csrf())).andExpect(status().isConflict());
    }

    @Test void authenticationCsrfAndOwnershipAreRequired() throws Exception {
        InstrumentTransfer t = proposal(alice, bob);
        for (String path : List.of("/transfers", "/transfers/" + t.getId(), formPath(), "/instruments/" + instrument.getId() + "/ownership"))
            mvc.perform(get(path)).andExpect(redirectedUrl("/login"));
        mvc.perform(post(createPath()).with(user(alice.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(post("/transfers/" + t.getId() + "/accept").with(user(bob.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(get(formPath()).with(user(carol.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(post(createPath()).with(user(carol.getEmail())).with(csrf()).param("recipient", bob.getUsername()).param("confirmed", "true"))
                .andExpect(status().isNotFound());
    }
    private String createPath() { return "/instruments/" + instrument.getId() + "/transfers"; }
    private String formPath() { return createPath() + "/new"; }
    private InstrumentTransfer proposal(User sender, User recipient) { return service.propose(sender, instrument.getId(), recipient.getUsername(), true); }
    private User account(String name) {
        User value = new User(); value.setUsername(name); value.setEmail(name + "@example.org"); value.setPasswordHash("unused");
        value.setEnabled(true); return users.saveAndFlush(value);
    }
}
