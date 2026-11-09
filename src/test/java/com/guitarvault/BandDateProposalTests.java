package com.guitarvault;

import jakarta.persistence.EntityManager;
import java.time.*;
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
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class BandDateProposalTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired BandRepository bands;
    @Autowired BandConnectionRepository connections;
    @Autowired BandDateProposalRepository proposals;
    @Autowired BandDateRevisionRepository revisions;
    @Autowired NotificationRepository notifications;
    @Autowired ConcertRepository concerts;
    @Autowired EntityManager em;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    User alice, bob, outsider, member;
    Band first, second;
    String date = LocalDate.now(ZoneOffset.UTC).plusDays(10).toString();
    String otherDate = LocalDate.now(ZoneOffset.UTC).plusDays(12).toString();

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        alice = account("date-alice"); bob = account("date-bob"); outsider = account("date-outsider"); member = account("date-member");
        first = new Band(alice); first.setName("First date band"); first.getMembers().add(alice); first.getMembers().add(member); bands.saveAndFlush(first);
        second = new Band(bob); second.setName("Second date band"); second.getMembers().add(bob); bands.saveAndFlush(second);
        BandConnection connection = new BandConnection(first, second, BandConnection.Type.FRIEND);
        connection.request(first.getId(), null, ""); connection.setStatus(BandConnection.Status.ACCEPTED); connections.saveAndFlush(connection);
    }

    @Test void friendProfilesAndNavigationOfferPrivateDateProposals() throws Exception {
        mvc.perform(get("/bands/" + second.getId()).with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("canProposeDate", true));
        mvc.perform(get("/bands/" + first.getId()).with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("/date-proposals/new?senderId=")));
        mvc.perform(get("/bands/" + second.getId()).with(user(member.getEmail())))
                .andExpect(model().attribute("canProposeDate", false));
        mvc.perform(get(formPath()).with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Proposer une date")))
                .andExpect(content().string(containsString("name=\"_csrf\""))).andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(get(formPath()).with(user(alice.getEmail())).param("lang", "en"))
                .andExpect(content().string(containsString("Suggest a date"))).andExpect(content().string(not(containsString("??proposal."))));
    }

    @Test void creationNotifiesRecipientAndRequiresNoExistingConcertOrVenue() throws Exception {
        Long id = create();
        var proposal = proposals.findById(id).orElseThrow();
        assertThat(proposal.getStatus()).isEqualTo(BandDateProposal.Status.PENDING);
        assertThat(proposal.getRevision()).isEqualTo(1); assertThat(proposal.getVenue()).isEmpty();
        assertThat(revisions.findByProposalIdOrderByNumberAsc(id)).hasSize(1);
        assertThat(concerts.count()).isZero();
        var notice = notifications.findAll().getFirst();
        assertThat(notice.getRecipient().getId()).isEqualTo(bob.getId());
        assertThat(notice.getKind()).isEqualTo(Notification.Kind.DATE_PROPOSAL);
        assertThat(notice.getTargetUrl()).isEqualTo("/date-proposals/" + id);
        mvc.perform(get("/notifications").with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("??notification."))))
                .andExpect(content().string(not(containsString("Private project"))));
        mvc.perform(post("/notifications/" + notice.getId() + "/open").with(user(bob.getEmail())).with(csrf()))
                .andExpect(redirectedUrl("/date-proposals/" + id));
    }

    @Test void discussionAndHistoryAreRestrictedToBothManagers() throws Exception {
        Long id = create();
        for (User actor : java.util.List.of(alice, bob)) {
            mvc.perform(get("/date-proposals/" + id).with(user(actor.getEmail())))
                    .andExpect(status().isOk()).andExpect(content().string(containsString("Private project")));
            mvc.perform(get("/date-proposals").with(user(actor.getEmail())))
                    .andExpect(status().isOk()).andExpect(content().string(containsString("First date band ↔ Second date band")))
                    .andExpect(content().string(not(containsString("??proposal."))));
        }
        for (User actor : java.util.List.of(outsider, member)) {
            mvc.perform(get("/date-proposals/" + id).with(user(actor.getEmail()))).andExpect(status().isNotFound());
            mvc.perform(get("/date-proposals").with(user(actor.getEmail())))
                    .andExpect(status().isOk()).andExpect(content().string(not(containsString("First date band ↔"))));
            mvc.perform(post("/date-proposals/" + id + "/accept").with(user(actor.getEmail())).with(csrf()).param("revision", "1"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/date-proposals/" + id).with(user(outsider.getEmail()).roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get("/bands/" + second.getId()).with(user(outsider.getEmail())))
                .andExpect(content().string(not(containsString("Private project"))));
    }

    @Test void counterproposalAlternatesTurnsAndAcceptanceKeepsHistory() throws Exception {
        Long id = create();
        action(id, bob, "counter", 1, otherDate, "Venue <script>x</script>", "Another date");
        var proposal = proposals.findById(id).orElseThrow();
        assertThat(proposal.getRevision()).isEqualTo(2); assertThat(proposal.getResponder().getId()).isEqualTo(first.getId());
        assertThat(proposal.getStatus()).isEqualTo(BandDateProposal.Status.PENDING);
        mvc.perform(get("/date-proposals/" + id).with(user(alice.getEmail())))
                .andExpect(model().attribute("canRespond", true)).andExpect(content().string(containsString("Venue &lt;script&gt;x&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>x</script>"))));
        mvc.perform(post("/date-proposals/" + id + "/accept").with(user(bob.getEmail())).with(csrf()).param("revision", "2"))
                .andExpect(status().isNotFound());
        action(id, alice, "accept", 2, "", "", "Agreed");
        assertThat(proposals.findById(id).orElseThrow().getStatus()).isEqualTo(BandDateProposal.Status.ACCEPTED);
        assertThat(revisions.findByProposalIdOrderByNumberAsc(id)).extracting(BandDateRevision::getProposedDate)
                .containsExactly(LocalDate.parse(date), LocalDate.parse(otherDate));
        assertThat(notifications.count()).isEqualTo(3); assertThat(concerts.count()).isZero();
        mvc.perform(get("/date-proposals/" + id).with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("canRespond", false));
    }

    @Test void staleRepliesAndClosedDiscussionCannotMutateOrNotifyAgain() throws Exception {
        Long id = create(); action(id, bob, "counter", 1, otherDate, "", "");
        mvc.perform(post("/date-proposals/" + id + "/accept").with(user(alice.getEmail())).with(csrf()).param("revision", "1"))
                .andExpect(redirectedUrl("/date-proposals/" + id)).andExpect(flash().attribute("error", "proposal.changedError"));
        assertThat(notifications.count()).isEqualTo(2);
        action(id, alice, "accept", 2, "", "", "");
        mvc.perform(post("/date-proposals/" + id + "/counter").with(user(bob.getEmail())).with(csrf()).param("revision", "2").param("date", date))
                .andExpect(redirectedUrl("/date-proposals/" + id)).andExpect(flash().attribute("error", "proposal.changedError"));
        assertThat(notifications.count()).isEqualTo(3);
    }

    @Test void onlyRecipientCanDeclineAndOriginalSenderCanCancel() throws Exception {
        Long id = create();
        mvc.perform(post("/date-proposals/" + id + "/decline").with(user(alice.getEmail())).with(csrf()).param("revision", "1"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/date-proposals/" + id + "/cancel").with(user(bob.getEmail())).with(csrf()).param("revision", "1"))
                .andExpect(status().isNotFound());
        action(id, bob, "decline", 1, "", "", "Unavailable");
        assertThat(proposals.findById(id).orElseThrow().getDecisionMessage()).isEqualTo("Unavailable");
        Long next = create(); action(next, alice, "cancel", 1, "", "", "");
        assertThat(proposals.findById(next).orElseThrow().getStatus()).isEqualTo(BandDateProposal.Status.CANCELLED);
    }

    @Test void friendshipAndManagerOwnershipAreEnforcedOnCreation() throws Exception {
        mvc.perform(post(createPath()).with(user(member.getEmail())).with(csrf()).param("senderId", first.getId().toString()).param("date", date))
                .andExpect(status().isNotFound());
        connections.findAll().getFirst().setStatus(BandConnection.Status.PENDING); em.flush(); em.clear();
        mvc.perform(get(formPath()).with(user(alice.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(post(createPath()).with(user(alice.getEmail())).with(csrf()).param("senderId", first.getId().toString()).param("date", date))
                .andExpect(flash().attribute("error", "proposal.friendError"));
        assertThat(proposals.count()).isZero(); assertThat(notifications.count()).isZero();
    }

    @Test void removingFriendshipPreventsAgreementButAllowsClosingDiscussion() throws Exception {
        Long id = create(); connections.findAll().getFirst().setStatus(BandConnection.Status.REMOVED); em.flush(); em.clear();
        mvc.perform(get("/date-proposals/" + id).with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("canAgree", false));
        mvc.perform(post("/date-proposals/" + id + "/accept").with(user(bob.getEmail())).with(csrf()).param("revision", "1"))
                .andExpect(flash().attribute("error", "proposal.friendError"));
        em.flush(); em.clear(); action(id, bob, "decline", 1, "", "", "");
        assertThat(proposals.findById(id).orElseThrow().getStatus()).isEqualTo(BandDateProposal.Status.DECLINED);
    }

    @Test void hiddenOrInactiveBandsCannotStartOrAgreeToNewPlans() throws Exception {
        Long id = create();
        bands.findById(second.getId()).orElseThrow().setModerationHidden(true); em.flush(); em.clear();
        mvc.perform(get(formPath()).with(user(alice.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(post("/date-proposals/" + id + "/counter").with(user(bob.getEmail())).with(csrf()).param("revision", "1").param("date", otherDate))
                .andExpect(flash().attribute("error", "proposal.inactiveError"));
        em.flush(); em.clear();
        assertThat(revisions.count()).isEqualTo(1); assertThat(notifications.count()).isEqualTo(1);
        bands.findById(second.getId()).orElseThrow().setModerationHidden(false);
        users.findById(bob.getId()).orElseThrow().setEnabled(false); em.flush(); em.clear();
        mvc.perform(post(createPath()).with(user(alice.getEmail())).with(csrf()).param("senderId", first.getId().toString()).param("date", otherDate))
                .andExpect(flash().attribute("error", "proposal.inactiveError"));
    }

    @Test void validationDuplicateProtectionAndCsrfDoNotProduceExtraNotifications() throws Exception {
        mvc.perform(post(createPath()).with(user(alice.getEmail())).param("senderId", first.getId().toString()).param("date", date))
                .andExpect(status().isForbidden());
        for (String invalid : new String[]{"", "2027-02-30", "0000-01-01", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()}) {
            mvc.perform(post(createPath()).with(user(alice.getEmail())).with(csrf()).param("senderId", first.getId().toString()).param("date", invalid))
                    .andExpect(flash().attribute("error", "proposal.dateError"));
        }
        mvc.perform(post(createPath()).with(user(alice.getEmail())).with(csrf()).param("senderId", first.getId().toString()).param("date", date).param("message", "x".repeat(2001)))
                .andExpect(flash().attribute("error", "proposal.fieldsError"));
        Long id = create();
        mvc.perform(post("/bands/" + first.getId() + "/date-proposals").with(user(bob.getEmail())).with(csrf()).param("senderId", second.getId().toString()).param("date", date))
                .andExpect(flash().attribute("error", "proposal.duplicateError"));
        mvc.perform(post("/date-proposals/" + id + "/counter").with(user(bob.getEmail())).with(csrf()).param("revision", "1").param("date", date))
                .andExpect(flash().attribute("error", "proposal.unchangedError"));
        assertThat(proposals.count()).isEqualTo(1); assertThat(notifications.count()).isEqualTo(1);
        mvc.perform(get("/date-proposals").with(user(bob.getEmail())).param("page", "-1")).andExpect(status().isBadRequest());
    }

    @Test void privateListIsPaginatedAndCannotIncludeAnotherManagersProjects() throws Exception {
        for (int i = 0; i < 21; i++) proposals.save(new BandDateProposal(first, second, LocalDate.parse(date).plusDays(i), "", ""));
        em.flush(); em.clear();
        mvc.perform(get("/date-proposals").with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("page=1")))
                .andExpect(result -> {
                    var page = (org.springframework.data.domain.Page<?>) result.getModelAndView().getModel().get("proposalPage");
                    assertThat(page.getContent()).hasSize(20); assertThat(page.getTotalElements()).isEqualTo(21);
                });
        mvc.perform(get("/date-proposals").with(user(bob.getEmail())).param("page", "1"))
                .andExpect(status().isOk()).andExpect(result -> {
                    var page = (org.springframework.data.domain.Page<?>) result.getModelAndView().getModel().get("proposalPage");
                    assertThat(page.getContent()).hasSize(1);
                });
    }

    private Long create() throws Exception {
        em.flush(); em.clear();
        String redirect = mvc.perform(post(createPath()).with(user(alice.getEmail())).with(csrf()).param("senderId", first.getId().toString())
                        .param("date", date).param("message", "Private project"))
                .andExpect(status().is3xxRedirection()).andExpect(result -> assertThat(result.getFlashMap().containsKey("error")).isFalse())
                .andReturn().getResponse().getRedirectedUrl();
        em.flush(); em.clear();
        return Long.valueOf(redirect.substring(redirect.lastIndexOf('/') + 1));
    }
    private void action(Long id, User actor, String action, int revision, String date, String venue, String message) throws Exception {
        em.flush(); em.clear();
        mvc.perform(post("/date-proposals/" + id + "/" + action).with(user(actor.getEmail())).with(csrf())
                        .param("revision", Integer.toString(revision)).param("date", date).param("venue", venue).param("message", message))
                .andExpect(status().is3xxRedirection()).andExpect(result -> assertThat(result.getFlashMap().containsKey("error")).isFalse());
        em.flush(); em.clear();
    }
    private String createPath() { return "/bands/" + second.getId() + "/date-proposals"; }
    private String formPath() { return createPath() + "/new"; }
    private User account(String name) {
        User user = new User(); user.setUsername(name); user.setFullName(name); user.setEmail(name + "@example.test");
        user.setPasswordHash("unused"); user.setEnabled(true); return users.saveAndFlush(user);
    }
}
