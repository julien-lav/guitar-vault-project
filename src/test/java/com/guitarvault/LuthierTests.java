package com.guitarvault;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
class LuthierTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired InstrumentRepository instruments;
    @Autowired LuthierProfileRepository profiles;
    @Autowired LuthierInterventionRepository interventions;
    @Autowired LuthierAssessmentRepository assessments;
    @Autowired LuthierVerificationRepository verifications;
    @Autowired EntityManager em;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    User owner, luthier, outsider, admin;
    Instrument instrument;
    LuthierProfile workshop;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = account("passport-owner"); luthier = account("passport-luthier"); outsider = account("passport-outsider");
        admin = account("passport-admin"); admin.setRoles(java.util.Set.of(Role.ROLE_ADMIN)); users.saveAndFlush(admin);
        instrument = new Instrument("Private Strat", "Fender", 1987, owner, "Actif");
        instrument.setSerialNumber("PRIVATE-SERIAL"); instrument.setNotes("OWNER SECRET NOTES"); instrument.setHistory("OWNER SECRET HISTORY");
        instrument = instruments.saveAndFlush(instrument);
        workshop = new LuthierProfile(luthier);
        workshop.update("Julien Workshop", "Paris", "Vintage guitars", "Restoration and inspections", "workshop@example.org", true);
        workshop = profiles.saveAndFlush(workshop);
    }

    @Test void publicDirectoryAndProfileDoNotExposeAccountOrInstrumentData() throws Exception {
        mvc.perform(get("/luthiers").param("lang", "en")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Julien Workshop")))
                .andExpect(content().string(containsString("Professional identity unverified")))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/luthiers/" + workshop.getId())).andExpect(status().isOk())
                .andExpect(content().string(not(containsString(luthier.getEmail()))))
                .andExpect(content().string(not(containsString("PRIVATE-SERIAL"))))
                .andExpect(content().string(not(containsString("Private Strat"))))
                .andExpect(content().string(containsString("workshop@example.org")));
        workshop.update(workshop.getName(), workshop.getLocation(), workshop.getSpecialties(), workshop.getDescription(), workshop.getContact(), false);
        profiles.flush();
        mvc.perform(get("/luthiers/" + workshop.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/luthiers")).andExpect(model().attribute("luthiers", hasSize(0)));
    }

    @Test void workshopEditingIgnoresForgedIdentityAndVerificationAndProtectsOtherProfiles() throws Exception {
        mvc.perform(get("/workshop").with(user(outsider.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("??"))));
        mvc.perform(profilePost(outsider).param("userId", luthier.getId().toString()).param("verified", "true"))
                .andExpect(redirectedUrl("/workshop"));
        LuthierProfile saved = profiles.findByUserId(outsider.getId()).orElseThrow();
        assertThat(saved.isVerified()).isFalse(); assertThat(saved.isPublished()).isFalse();
        assertThat(workshop.getName()).isEqualTo("Julien Workshop");
        mvc.perform(profilePost(owner).param("name", "x".repeat(151)))
                .andExpect(model().attribute("error", "luthier.invalid"))
                .andExpect(content().string(containsString("role=\"alert\"")));
        assertThat(profiles.findByUserId(owner.getId())).isEmpty();
        mvc.perform(profilePost(luthier).param("version", "999")).andExpect(status().isConflict());
    }

    @Test void ownerRequestAppearsInPassportAndAssignedInboxWithLimitedAccess() throws Exception {
        mvc.perform(get(requestPath() + "/new").with(user(owner.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(requestPost(owner).param("requesterId", outsider.getId().toString()).param("status", "COMPLETED"))
                .andExpect(status().is3xxRedirection());
        LuthierIntervention i = interventions.findByInstrumentIdOrderByIdDesc(instrument.getId()).getFirst();
        assertThat(i.getRequester().getId()).isEqualTo(owner.getId());
        assertThat(i.getStatus()).isEqualTo(LuthierIntervention.Status.REQUESTED);
        mvc.perform(get("/instruments/" + instrument.getId()).with(user(owner.getEmail())))
                .andExpect(content().string(containsString("/interventions/" + i.getId())));
        mvc.perform(get("/interventions").with(user(luthier.getEmail())).param("lang", "en"))
                .andExpect(model().attribute("interventions", hasSize(1)))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/interventions/" + i.getId()).with(user(luthier.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("PRIVATE-SERIAL")))
                .andExpect(content().string(not(containsString("OWNER SECRET"))))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/instruments/" + instrument.getId()).with(user(luthier.getEmail()))).andExpect(status().isNotFound());
        for (User actor : List.of(outsider, admin)) {
            mvc.perform(get("/interventions/" + i.getId()).with(user(actor.getEmail()))).andExpect(status().isNotFound());
            mvc.perform(assessmentPost(actor, i, "0")).andExpect(status().isNotFound());
            mvc.perform(post("/interventions/" + i.getId() + "/cancel").with(user(actor.getEmail())).with(csrf())).andExpect(status().isNotFound());
            mvc.perform(requestPost(actor)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/interventions").with(user(outsider.getEmail()))).andExpect(model().attribute("interventions", hasSize(0)));
    }

    @Test void assessmentsAreAttributedAppendOnlyEscapedAndRejectStaleCorrections() throws Exception {
        LuthierIntervention i = request();
        mvc.perform(assessmentPost(owner, i, "0")).andExpect(status().isNotFound());
        mvc.perform(assessmentPost(luthier, i, "0").param("authorId", owner.getId().toString()).param("professionalVerified", "true"))
                .andExpect(redirectedUrl("/interventions/" + i.getId()));
        assessments.flush(); em.clear();
        LuthierAssessment original = assessments.findByInterventionIdOrderByRevisionDesc(i.getId()).getFirst();
        assertThat(original.getAuthor().getId()).isEqualTo(luthier.getId());
        assertThat(original.isProfessionalVerified()).isFalse();
        assertThat(original.getFindings()).isEqualTo("Consistent <script>alert(1)</script>");
        assertThat(interventions.findById(i.getId()).orElseThrow().getStatus()).isEqualTo(LuthierIntervention.Status.COMPLETED);
        mvc.perform(assessmentPost(luthier, i, "0").param("correctionReason", "Stale form")).andExpect(status().isConflict());
        mvc.perform(assessmentPost(luthier, i, "1")).andExpect(model().attribute("error", "luthier.invalid"));
        mvc.perform(assessmentPost(luthier, i, "1").param("findings", "Replacement pickups identified").param("correctionReason", "Additional examination"))
                .andExpect(redirectedUrl("/interventions/" + i.getId()));
        assessments.flush(); em.clear();
        List<LuthierAssessment> history = assessments.findByInterventionIdOrderByRevisionDesc(i.getId());
        assertThat(history).extracting(LuthierAssessment::getRevision).containsExactly(2, 1);
        assertThat(history.getLast().getFindings()).isEqualTo(original.getFindings());
        assertThat(history.getFirst().getCorrectionReason()).isEqualTo("Additional examination");
        mvc.perform(get("/interventions/" + i.getId()).with(user(owner.getEmail())).param("lang", "en"))
                .andExpect(content().string(containsString("Previous report")))
                .andExpect(content().string(containsString("Replacement pickups identified")))
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert"))))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(post("/interventions/" + i.getId() + "/cancel").with(user(owner.getEmail())).with(csrf())).andExpect(status().isConflict());
    }

    @Test void invalidReportsNeverCompleteRequestsOrLeavePartialHistory() throws Exception {
        LuthierIntervention i = request();
        for (var entry : List.of(Map.entry("findings", " "), Map.entry("findings", "x".repeat(5001)),
                Map.entry("evidence", ""), Map.entry("evidence", "x".repeat(3001)), Map.entry("limitations", ""),
                Map.entry("examinedOn", "2026-02-30"), Map.entry("examinedOn", "0000-01-01"),
                Map.entry("examinedOn", LocalDate.now(ZoneOffset.UTC).plusDays(1).toString()),
                Map.entry("conclusion", "CERTIFIED"), Map.entry("conclusion", "WORK_RECORDED"))) {
            mvc.perform(assessmentPost(luthier, i, "0").param(entry.getKey(), entry.getValue()))
                    .andExpect(status().isOk()).andExpect(model().attribute("error", "luthier.invalid"));
        }
        assertThat(assessments.count()).isZero();
        assertThat(i.getStatus()).isEqualTo(LuthierIntervention.Status.REQUESTED);
    }

    @Test void cancellationRetainsRequestButBlocksReportsAndRepeatActions() throws Exception {
        LuthierIntervention i = request();
        mvc.perform(post("/interventions/" + i.getId() + "/cancel").with(user(luthier.getEmail())).with(csrf()))
                .andExpect(redirectedUrl("/interventions/" + i.getId()));
        assertThat(i.getStatus()).isEqualTo(LuthierIntervention.Status.CANCELLED);
        mvc.perform(assessmentPost(luthier, i, "0")).andExpect(status().isConflict());
        mvc.perform(post("/interventions/" + i.getId() + "/cancel").with(user(owner.getEmail())).with(csrf())).andExpect(status().isConflict());
        mvc.perform(get("/interventions/" + i.getId()).with(user(owner.getEmail()))).andExpect(status().isOk());
        assertThat(assessments.count()).isZero();
    }

    @Test void identityVerificationIsAdminOnlyAuditedAndSeparateFromAuthenticity() throws Exception {
        String path = "/admin/luthiers/" + workshop.getId();
        mvc.perform(get("/admin/luthiers").with(user(admin.getEmail()).roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get(path).with(user(admin.getEmail()).roles("ADMIN")).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("??"))));
        mvc.perform(post(path + "/verification").with(user(luthier.getEmail())).with(csrf())
                .param("verified", "true").param("version", "0").param("evidence", "Self verify"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path + "/verification").with(user(admin.getEmail()).roles("ADMIN")).with(csrf())
                .param("verified", "true").param("version", "0").param("evidence", "Registration and identity reviewed"))
                .andExpect(redirectedUrl(path));
        assertThat(workshop.isVerified()).isTrue();
        assertThat(verifications.findByProfileIdOrderByIdDesc(workshop.getId())).hasSize(1);
        LuthierIntervention i = request();
        mvc.perform(assessmentPost(luthier, i, "0")).andExpect(status().is3xxRedirection());
        assertThat(assessments.findByInterventionIdOrderByRevisionDesc(i.getId()).getFirst().isProfessionalVerified()).isTrue();
        long version = workshop.getVersion();
        mvc.perform(profilePost(luthier).param("version", Long.toString(version)))
                .andExpect(redirectedUrl("/workshop"));
        assertThat(workshop.isVerified()).isFalse();
        assertThat(assessments.findByInterventionIdOrderByRevisionDesc(i.getId()).getFirst().isProfessionalVerified()).isTrue();
        mvc.perform(get(path).with(user(outsider.getEmail()).roles("ADMIN"))).andExpect(status().isNotFound());
    }

    @Test void authenticationCsrfHiddenAndSuspendedProfilesAreEnforced() throws Exception {
        LuthierIntervention i = request();
        for (String path : List.of("/workshop", "/interventions", "/interventions/" + i.getId(), requestPath() + "/new"))
            mvc.perform(get(path)).andExpect(redirectedUrl("/login"));
        mvc.perform(post(requestPath()).with(user(owner.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(post("/workshop").with(user(luthier.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(post("/interventions/" + i.getId() + "/assessments").with(user(luthier.getEmail()))).andExpect(status().isForbidden());
        luthier.setModerationHidden(true); users.flush();
        mvc.perform(get("/luthiers/" + workshop.getId())).andExpect(status().isNotFound());
        mvc.perform(requestPost(owner)).andExpect(status().isNotFound());
        luthier.setModerationHidden(false); luthier.setSuspended(true); users.flush();
        mvc.perform(get("/luthiers")).andExpect(model().attribute("luthiers", hasSize(0)));
        mvc.perform(requestPost(owner)).andExpect(status().isNotFound());
        mvc.perform(assessmentPost(luthier, i, "0")).andExpect(status().is3xxRedirection());
        assertThat(assessments.count()).isZero();
    }

    private String requestPath() { return "/instruments/" + instrument.getId() + "/interventions"; }
    private MockHttpServletRequestBuilder requestPost(User actor) {
        return post(requestPath()).with(user(actor.getEmail())).with(csrf()).with(defaults(Map.of(
                "luthierId", workshop.getId().toString(), "kind", "AUTHENTICITY", "description", "Please examine the construction and replaced parts")));
    }
    private LuthierIntervention request() {
        return interventions.saveAndFlush(new LuthierIntervention(instrument, owner, workshop, LuthierIntervention.Kind.AUTHENTICITY, "Please inspect"));
    }
    private MockHttpServletRequestBuilder assessmentPost(User actor, LuthierIntervention i, String version) {
        return post("/interventions/" + i.getId() + "/assessments").with(user(actor.getEmail())).with(csrf()).with(defaults(Map.of(
                "version", version, "examinedOn", LocalDate.now(ZoneOffset.UTC).toString(), "conclusion", "CONSISTENT",
                "findings", "Consistent <script>alert(1)</script>", "evidence", "Inspected neck stamp and construction", "limitations", "Electronics not opened")));
    }
    private MockHttpServletRequestBuilder profilePost(User actor) {
        return post("/workshop").with(user(actor.getEmail())).with(csrf()).with(defaults(Map.of(
                "version", "0", "name", "New workshop", "location", "Lyon", "specialties", "Repairs",
                "description", "My professional workshop", "contact", "contact@example.org")));
    }
    private static org.springframework.test.web.servlet.request.RequestPostProcessor defaults(Map<String, String> values) {
        return request -> { values.forEach((key, value) -> { if (request.getParameter(key) == null) request.setParameter(key, value); }); return request; };
    }
    private User account(String name) {
        User u = new User(); u.setUsername(name); u.setEmail(name + "@example.org");
        u.setPasswordHash("unused-test-hash"); u.setEnabled(true); return users.saveAndFlush(u);
    }
}
