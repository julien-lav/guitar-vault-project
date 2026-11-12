package com.guitarvault;

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
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class WorkshopJobTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired InstrumentRepository instruments;
    @Autowired LuthierProfileRepository profiles;
    @Autowired LuthierService luthiers;
    @Autowired LuthierInterventionRepository interventions;
    @Autowired WorkshopJobService service;
    @Autowired WorkshopJobRepository jobs;
    @Autowired WorkshopJobEventRepository events;
    @Autowired NotificationRepository notifications;
    @Autowired InstrumentTransferService transfers;
    @Autowired NotificationService notices;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    User owner, luthier, outsider, admin;
    Instrument instrument;
    LuthierIntervention intervention;
    LuthierProfile workshop;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = account("job-owner"); luthier = account("job-luthier"); outsider = account("job-outsider"); admin = account("job-admin");
        admin.setRoles(Set.of(Role.ROLE_ADMIN)); users.flush();
        instrument = instruments.saveAndFlush(new Instrument("Repair guitar", "Brand", 2000, owner, "Actif"));
        workshop = new LuthierProfile(luthier); workshop.update("Repair workshop", "Paris", "Repairs", "About", "Contact", true);
        profiles.saveAndFlush(workshop);
        intervention = luthiers.request(owner, instrument.getId(), Map.of("luthierId", workshop.getId().toString(), "kind", "REPAIR", "description", "Repair the bridge"));
    }
    @Test void fullRepairJourneyRendersBothLanguagesAndNotifiesTheOwnerWhenReady() throws Exception {
        mvc.perform(get(path()).with(user(luthier.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Record instrument intake")));
        postAction(luthier, "receive", -1, Map.of("message", "Case and worn strings <script>"));
        quote("125.50", "Replace bridge, parts and tax included");
        mvc.perform(get(path()).with(user(owner.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("125.50 EUR")))
                .andExpect(content().string(containsString("Approve this quote")))
                .andExpect(content().string(containsString("Case and worn strings &lt;script&gt;")));
        postAction(owner, "approve", version(), Map.of());
        postAction(luthier, "start", version(), Map.of());
        postAction(luthier, "update", version(), Map.of("message", "Bridge fitted"));
        postAction(luthier, "ready", version(), Map.of("message", "Collect Tuesday after 10"));
        assertThat(job().getStage()).isEqualTo(WorkshopJob.Stage.READY);
        var notification = notifications.findByRecipientIdOrderByIdDesc(owner.getId(), org.springframework.data.domain.Pageable.unpaged()).getContent().getFirst();
        assertThat(notices.open(owner, notification.getId())).isEqualTo(path());
        for (String lang : List.of("fr", "en")) {
            mvc.perform(get(path()).with(user(owner.getEmail())).param("lang", lang))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(content().string(not(containsString("??"))))
                    .andExpect(content().string(containsString("Collect Tuesday after 10")));
        }
        postAction(luthier, "collect", version(), Map.of());
        assertThat(job().getStage()).isEqualTo(WorkshopJob.Stage.COLLECTED);
        assertThat(intervention.getStatus()).isEqualTo(LuthierIntervention.Status.COMPLETED);
        assertThat(events.findByJobIdOrderByIdAsc(job().getId())).hasSize(7);
        mvc.perform(get(path()).with(user(luthier.getEmail())).param("lang", "en"))
                .andExpect(content().string(not(containsString("name=\"amount\""))));
        assertConflict(luthier, "ready", version());
    }
    @Test void revisionsKeepEveryQuoteAndInvalidateEarlierApprovalForms() throws Exception {
        act(luthier, "receive", Map.of("message", "Received")); quote("100", "Original quote");
        long oldVersion = version(); quote("150", "Revised quote");
        mvc.perform(post(path() + "/job/approve").with(user(owner.getEmail())).with(csrf()).param("version", "" + oldVersion))
                .andExpect(status().isConflict()).andExpect(content().string(containsString("role=\"alert\"")));
        act(owner, "decline", Map.of()); quote("120", "Reduced scope"); act(owner, "approve", Map.of());
        assertThat(job().getAmount()).isEqualByComparingTo("120");
        assertThat(events.findByJobIdOrderByIdAsc(job().getId()).stream().filter(e -> e.getAction().equals("quote")).map(WorkshopJobEvent::getMessage))
                .containsExactly("Original quote", "Revised quote", "Reduced scope");
        assertConflict(luthier, "quote", version());
    }
    @Test void onlyAssignedParticipantsCanReadOrActAndRolesCannotBeForged() throws Exception {
        for (User actor : List.of(outsider, admin)) {
            mvc.perform(get(path()).with(user(actor.getEmail()))).andExpect(status().isNotFound());
            mvc.perform(post(path() + "/job/receive").with(user(actor.getEmail())).with(csrf()).param("version", "-1").param("message", "forged"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get(path())).andExpect(redirectedUrl("/login"));
        mvc.perform(post(path() + "/job/receive").with(user(luthier.getEmail())).param("version", "-1")).andExpect(status().isForbidden());
        assertConflict(owner, "receive", -1);
        act(luthier, "receive", Map.of("message", "Received"));
        assertConflict(luthier, "start", version()); quote("100", "Work");
        assertConflict(luthier, "approve", version()); assertConflict(owner, "start", version());
        act(owner, "approve", Map.of()); act(luthier, "start", Map.of());
        assertConflict(owner, "ready", version()); assertConflict(owner, "cancel", version());
        mvc.perform(post(path() + "/cancel").with(user(owner.getEmail())).with(csrf())).andExpect(status().isConflict());
    }
    @Test void invalidQuoteAndNotesNeverCreatePartialActivity() throws Exception {
        mvc.perform(post(path() + "/job/receive").with(user(luthier.getEmail())).with(csrf()).param("version", "-1").param("message", " ").param("lang", "en"))
                .andExpect(status().isOk()).andExpect(model().attribute("error", "job.invalid"));
        assertThat(jobs.count()).isZero(); act(luthier, "receive", Map.of("message", "Received"));
        for (String amount : List.of("-1", "NaN", "1.001", "1000000000", "1e5", "")) {
            assertThatThrownBy(() -> service.act(luthier, intervention.getId(), version(), "quote", Map.of("message", "Work", "amount", amount, "currency", "EUR")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.act(luthier, intervention.getId(), version(), "quote", Map.of("message", "Work", "amount", "50", "currency", "XYZ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(events.findByJobIdOrderByIdAsc(job().getId())).hasSize(1);
        assertThat(job().getStage()).isEqualTo(WorkshopJob.Stage.RECEIVED);
    }
    @Test void activeRepairBlocksTransferEvenAfterSigningAReportAndCollectionReleasesIt() throws Exception {
        act(luthier, "receive", Map.of("message", "Received")); quote("100", "Work"); act(owner, "approve", Map.of()); act(luthier, "start", Map.of());
        luthiers.assess(luthier, intervention.getId(), Map.of("version", "0", "examinedOn", java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(),
                "conclusion", "WORK_RECORDED", "findings", "Bridge fixed", "evidence", "Inspected", "limitations", "No other work"));
        var transfer = transfers.propose(owner, instrument.getId(), outsider.getUsername(), true);
        assertThatThrownBy(() -> transfers.resolve(outsider, transfer.getId(), InstrumentTransfer.Status.ACCEPTED)).isInstanceOf(ResponseStatusException.class);
        act(luthier, "ready", Map.of("message", "Ready")); act(luthier, "collect", Map.of());
        transfers.resolve(outsider, transfer.getId(), InstrumentTransfer.Status.ACCEPTED);
        mvc.perform(get(path()).with(user(outsider.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get(path()).with(user(owner.getEmail()))).andExpect(status().isOk());
    }
    @Test void cancellationRetainsIntakeAndQuote() throws Exception {
        act(luthier, "receive", Map.of("message", "Received")); quote("100", "Work");
        act(owner, "cancel", Map.of("message", "Returning the instrument unchanged"));
        assertThat(job().getStage()).isEqualTo(WorkshopJob.Stage.CANCELLED);
        assertThat(intervention.getStatus()).isEqualTo(LuthierIntervention.Status.CANCELLED);
        assertThat(events.findByJobIdOrderByIdAsc(job().getId())).hasSize(3);
        assertConflict(luthier, "start", version());
    }
    @Test void anInstrumentCannotBeReceivedIntoTwoActiveJobs() {
        var other = luthiers.request(owner, instrument.getId(), Map.of("luthierId", workshop.getId().toString(), "kind", "SETUP", "description", "Other work"));
        act(luthier, "receive", Map.of("message", "Received"));
        assertThatThrownBy(() -> service.act(luthier, other.getId(), -1, "receive", Map.of("message", "Second intake")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(jobs.findByInterventionId(other.getId())).isEmpty();
    }
    @Test void inactiveParticipantCannotContinueAndUnpublishingPreservesExistingWork() throws Exception {
        act(luthier, "receive", Map.of("message", "Received"));
        workshop.update("Repair workshop", "Paris", "Repairs", "About", "Contact", false); profiles.flush();
        quote("100", "Still working together");
        owner.setEnabled(false); users.flush();
        assertConflict(luthier, "quote", version());
        assertThat(events.findByJobIdOrderByIdAsc(job().getId())).hasSize(2);
    }
    @Test void additionalWorkPausesRepairAndApprovalPreservesBothAgreements() throws Exception {
        started(); long originalVersion = version();
        postAction(luthier, "request-change", originalVersion, change("180", "Bridge and damaged brace"));
        assertThat(job().getStage()).isEqualTo(WorkshopJob.Stage.CHANGE_PENDING);
        assertThat(job().getAmount()).isEqualByComparingTo("120");
        assertThat(job().getScope()).isEqualTo("Bridge repair");
        for (String action : List.of("update", "ready", "collect", "start")) assertConflict(luthier, action, version());
        for (String lang : List.of("en", "fr")) mvc.perform(get(path()).with(user(owner.getEmail())).param("lang", lang))
                .andExpect(status().isOk()).andExpect(content().string(containsString("180.00 EUR")))
                .andExpect(content().string(containsString("60.00 EUR")))
                .andExpect(content().string(not(containsString("??"))));
        postAction(owner, "approve-change", version(), Map.of());
        assertThat(job().getStage()).isEqualTo(WorkshopJob.Stage.IN_PROGRESS);
        assertThat(job().getAmount()).isEqualByComparingTo("180");
        assertThat(job().getScope()).isEqualTo("Bridge and damaged brace");
        assertThat(job().getEstimatedCompletionOn()).isEqualTo(java.time.LocalDate.parse(completion()));
        var history = events.findByJobIdOrderByIdAsc(job().getId());
        assertThat(history.stream().filter(e -> e.getAction().equals("approve")).findFirst().orElseThrow().getAmount()).isEqualByComparingTo("120");
        var approval = history.getLast();
        assertThat(approval.getAmount()).isEqualByComparingTo("180");
        assertThat(approval.getScope()).isEqualTo("Bridge and damaged brace");
        act(luthier, "ready", Map.of("message", "Finished approved work"));
    }
    @Test void refusalKeepsRepairPausedUntilLuthierExplicitlyResumesOriginalScope() throws Exception {
        started(); act(luthier, "request-change", change("180", "Bridge and brace"));
        act(owner, "decline-change", Map.of());
        assertThat(job().getStage()).isEqualTo(WorkshopJob.Stage.CHANGE_DECLINED);
        assertThat(job().getAmount()).isEqualByComparingTo("120");
        assertConflict(luthier, "ready", version()); assertConflict(owner, "approve-change", version());
        postAction(luthier, "resume-original", version(), Map.of("message", "Brace work can be deferred; bridge repair only"));
        assertThat(job().getStage()).isEqualTo(WorkshopJob.Stage.IN_PROGRESS);
        assertThat(job().getScope()).isEqualTo("Bridge repair");
        assertThat(job().getAmount()).isEqualByComparingTo("120");
        assertThat(events.findByJobIdOrderByIdAsc(job().getId()).getLast().getAmount()).isEqualByComparingTo("120");
        assertConflict(owner, "approve-change", version());
    }
    @Test void revisedChangeRequiresFreshOwnerApprovalAndRejectsForgedRolesAndCsrf() throws Exception {
        started(); act(luthier, "request-change", change("180", "First scope")); long oldVersion = version();
        act(luthier, "request-change", change("200", "Revised scope"));
        mvc.perform(post(path() + "/job/approve-change").with(user(owner.getEmail())).with(csrf()).param("version", "" + oldVersion))
                .andExpect(status().isConflict());
        for (String action : List.of("request-change", "resume-original")) assertConflict(owner, action, version());
        for (String action : List.of("approve-change", "decline-change")) assertConflict(luthier, action, version());
        for (User actor : List.of(outsider, admin)) mvc.perform(post(path() + "/job/approve-change").with(user(actor.getEmail())).with(csrf()).param("version", "" + version()))
                .andExpect(status().isNotFound());
        mvc.perform(post(path() + "/job/approve-change").with(user(owner.getEmail())).param("version", "" + version())).andExpect(status().isForbidden());
        act(owner, "approve-change", Map.of());
        assertThat(job().getAmount()).isEqualByComparingTo("200");
        assertConflict(owner, "approve-change", version());
        assertThat(events.findByJobIdOrderByIdAsc(job().getId()).stream().filter(e -> e.getAction().equals("request-change")).map(WorkshopJobEvent::getScope))
                .containsExactly("First scope", "Revised scope");
    }
    @Test void invalidAdditionalWorkLeavesAgreementAndActivityUntouchedAndPreservesForm() throws Exception {
        started(); int count = events.findByJobIdOrderByIdAsc(job().getId()).size();
        for (var entry : List.of(Map.entry("message", " "), Map.entry("scope", " "), Map.entry("scope", "x".repeat(3001)),
                Map.entry("amount", "-60"), Map.entry("amount", "180.001"), Map.entry("completionOn", "not-a-date"), Map.entry("completionOn", "2020-01-01"))) {
            var form = new HashMap<>(change("180", "Bridge and brace")); form.put(entry.getKey(), entry.getValue());
            assertThatThrownBy(() -> service.act(luthier, intervention.getId(), version(), "request-change", form)).isInstanceOf(IllegalArgumentException.class);
        }
        mvc.perform(post(path() + "/job/request-change").with(user(luthier.getEmail())).with(csrf()).param("version", "" + version())
                        .param("message", "Damaged brace").param("amount", "180").param("scope", "Keep these terms").param("completionOn", "invalid").param("lang", "en"))
                .andExpect(status().isOk()).andExpect(model().attribute("error", "job.change.invalid"))
                .andExpect(content().string(containsString("Keep these terms")));
        assertThat(job().getStage()).isEqualTo(WorkshopJob.Stage.IN_PROGRESS);
        assertThat(job().getAmount()).isEqualByComparingTo("120");
        assertThat(events.findByJobIdOrderByIdAsc(job().getId())).hasSize(count);
    }
    @Test void pausedWorkCanBeCancelledAndCannotBeTransferredEvenWithASignedReport() {
        started();
        luthiers.assess(luthier, intervention.getId(), Map.of("version", "0", "examinedOn", java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(),
                "conclusion", "WORK_RECORDED", "findings", "Bridge fixed", "evidence", "Inspected", "limitations", "Brace work pending"));
        act(luthier, "request-change", change("180", "Bridge and brace"));
        var transfer = transfers.propose(owner, instrument.getId(), outsider.getUsername(), true);
        assertThatThrownBy(() -> transfers.resolve(outsider, transfer.getId(), InstrumentTransfer.Status.ACCEPTED)).isInstanceOf(ResponseStatusException.class);
        act(luthier, "cancel", Map.of("message", "Return instrument; no extra work performed"));
        assertConflict(owner, "approve-change", version());
        assertThat(job().getAmount()).isEqualByComparingTo("120");
    }
    private void started() {
        act(luthier, "receive", Map.of("message", "Received")); quote("120", "Bridge repair"); act(owner, "approve", Map.of()); act(luthier, "start", Map.of());
    }
    private String completion() { return java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(7).toString(); }
    private Map<String,String> change(String amount, String scope) {
        return Map.of("amount", amount, "scope", scope, "message", "Damaged brace discovered", "completionOn", completion());
    }
    private void quote(String amount, String scope) { act(luthier, "quote", Map.of("amount", amount, "currency", "EUR", "message", scope)); }
    private WorkshopJob job() { return jobs.findByInterventionId(intervention.getId()).orElseThrow(); }
    private long version() { return job().getVersion(); }
    private void act(User actor, String action, Map<String,String> form) { service.act(actor, intervention.getId(), action.equals("receive") ? -1 : version(), action, form); }
    private void postAction(User actor, String action, long version, Map<String,String> form) throws Exception {
        var request = post(path() + "/job/" + action).with(user(actor.getEmail())).with(csrf()).param("version", "" + version);
        form.forEach(request::param); mvc.perform(request).andExpect(redirectedUrl(path()));
    }
    private void assertConflict(User actor, String action, long version) {
        assertThatThrownBy(() -> service.act(actor, intervention.getId(), version, action, Map.of("message", "Action")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
    }
    private String path() { return "/interventions/" + intervention.getId(); }
    private User account(String name) { var u = new User(); u.setUsername(name); u.setEmail(name + "@example.org"); u.setPasswordHash("unused"); return users.saveAndFlush(u); }
}
