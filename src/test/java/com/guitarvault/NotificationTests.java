package com.guitarvault;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
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
class NotificationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired BandRepository bands;
    @Autowired BandService bandService;
    @Autowired BandInvitationRepository invitations;
    @Autowired BandConnectionService connections;
    @Autowired StudioRepository studios;
    @Autowired StudioQuoteService quotes;
    @Autowired ConcertService concerts;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationService service;
    @Autowired EntityManager em;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    User alice, bob, outsider;
    Band first, second;
    Studio studio;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        alice = account("notice-alice"); bob = account("notice-bob"); outsider = account("notice-other");
        first = band(alice, "First band"); second = band(bob, "Second band");
        studio = new Studio(bob); studio.setName("Studio"); studios.saveAndFlush(studio);
    }
    @Test void bandInvitationNotifiesOnceAndBellShowsUnreadCountWithoutReadingOnGet() throws Exception {
        mvc.perform(get("/")).andExpect(content().string(not(containsString("data-notification-bell"))));
        mvc.perform(get("/dashboard").with(user(bob.getEmail())))
                .andExpect(content().string(containsString("data-unread=\"0\"")));
        mvc.perform(post("/bands/" + first.getId() + "/invitations").with(user(alice.getEmail())).with(csrf())
                        .param("username", bob.getUsername()))
                .andExpect(flash().attribute("success", "invitation.sent"));
        bandService.invite(alice, first.getId(), bob.getUsername() + "," + bob.getUsername());
        assertThat(forUser(bob)).hasSize(1); assertThat(forUser(alice)).isEmpty();
        Notification notification = forUser(bob).getFirst();
        assertThat(notification.getKind()).isEqualTo(Notification.Kind.BAND_INVITATION);
        assertThat(notification.getTargetUrl()).isEqualTo("/bands/" + first.getId());
        mvc.perform(get("/dashboard").with(user(bob.getEmail())))
                .andExpect(model().attribute("unreadNotificationCount", 1L))
                .andExpect(content().string(containsString("data-unread=\"1\"")))
                .andExpect(content().string(containsString("href=\"/notifications\"")));
        mvc.perform(get("/notifications").with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().string(containsString("First band")));
        assertThat(notifications.countByRecipientIdAndReadAtIsNull(bob.getId())).isEqualTo(1);
        mvc.perform(post("/notifications/" + notification.getId() + "/read").with(user(bob.getEmail())).with(csrf()))
                .andExpect(redirectedUrl("/notifications"));
        assertThat(invitations.findByBandIdAndInviteeId(first.getId(), bob.getId()).orElseThrow().getStatus()).isEqualTo(BandInvitation.Status.PENDING);
    }
    @Test void renewedInvitationNotifiesAgainButInvalidOrDuplicateInvitesDoNot() {
        bandService.invite(alice, first.getId(), bob.getUsername());
        Long id = invitations.findByBandIdAndInviteeId(first.getId(), bob.getId()).orElseThrow().getId();
        bandService.respond(bob, id, false);
        bandService.invite(alice, first.getId(), bob.getUsername());
        assertThat(forUser(bob)).hasSize(2);
        assertThatThrownBy(() -> bandService.invite(alice, first.getId(), "unknown-user"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(forUser(bob)).hasSize(2);
    }
    @Test void connectionRequestNotifiesOtherManagerAndDoesNotNotifySelfOrDuplicateCrossedRequests() throws Exception {
        connections.request(alice, first.getId(), second.getId(), "FRIEND", "", "");
        Notification notification = forUser(bob).getFirst();
        assertThat(notification.getKind()).isEqualTo(Notification.Kind.BAND_CONNECTION_REQUEST);
        assertThat(notification.getSubject()).isEqualTo(first.getName());
        assertThat(notification.getTargetUrl()).isEqualTo("/bands/" + second.getId() + "#band-network");
        mvc.perform(post("/bands/" + first.getId() + "/connections").with(user(bob.getEmail())).with(csrf())
                        .param("senderId", second.getId().toString()).param("type", "FRIEND"))
                .andExpect(flash().attribute("error", "connection.existsError"));
        assertThat(forUser(bob)).hasSize(1); assertThat(forUser(alice)).isEmpty();
        Band own = band(alice, "Another owned band");
        connections.request(alice, first.getId(), own.getId(), "FRIEND", "", "");
        assertThat(forUser(alice)).isEmpty();
    }
    @Test void newQuoteAndEachMessageNotifyOnlyTheOtherParticipant() throws Exception {
        StudioQuote quote = quotes.create(alice, studio.getId(), Map.of("title", "My EP", "description", "A project", "startDate", "", "endDate", "", "budget", ""));
        assertThat(forUser(bob)).extracting(Notification::getKind).containsExactly(Notification.Kind.QUOTE_REQUEST);
        quotes.reply(bob, quote.getId(), Map.of("message", "Private studio answer", "amount", "250"));
        assertThat(forUser(alice)).extracting(Notification::getKind).containsExactly(Notification.Kind.QUOTE_MESSAGE);
        quotes.reply(alice, quote.getId(), Map.of("message", "Private follow-up", "amount", ""));
        assertThat(forUser(bob)).hasSize(2); assertThat(forUser(alice)).hasSize(1);
        assertThat(forUser(bob).getFirst().getTargetUrl()).isEqualTo("/quotes/" + quote.getId());
        mvc.perform(get("/notifications").with(user(bob.getEmail())))
                .andExpect(content().string(containsString("My EP")))
                .andExpect(content().string(not(containsString("Private follow-up"))));
        mvc.perform(post("/quotes/" + quote.getId() + "/reply").with(user(bob.getEmail())).with(csrf()).param("message", " "))
                .andExpect(model().attribute("error", "quote.replyError"));
        mvc.perform(post("/quotes/" + quote.getId() + "/reply").with(user(outsider.getEmail())).with(csrf()).param("message", "Forged"))
                .andExpect(status().isNotFound());
        assertThat(forUser(bob)).hasSize(2); assertThat(forUser(alice)).hasSize(1); assertThat(forUser(outsider)).isEmpty();
    }
    @Test void concertInvitationNotifiesInvitedBandManagerWithoutDuplicates() throws Exception {
        Concert concert = concerts.save(alice, first.getId(), null, Map.of("title", "Shared show", "date", LocalDate.now().plusDays(2).toString(),
                "venue", "Club", "city", "", "description", ""), null, false);
        concerts.invite(alice, concert.getId(), second.getId());
        Notification notification = forUser(bob).getFirst();
        assertThat(notification.getKind()).isEqualTo(Notification.Kind.CONCERT_INVITATION);
        assertThat(notification.getTargetUrl()).isEqualTo("/concerts/" + concert.getId());
        mvc.perform(post("/concerts/" + concert.getId() + "/invitations").with(user(alice.getEmail())).with(csrf()).param("bandId", second.getId().toString()))
                .andExpect(flash().attribute("error", "concert.existsError"));
        assertThat(forUser(bob)).hasSize(1); assertThat(forUser(alice)).isEmpty();
    }
    @Test void openingAndMarkingReadAreOwnerScopedIdempotentAndIgnoreForgedRedirect() throws Exception {
        send(alice, bob, "For Bob"); send(bob, alice, "For Alice");
        Long bobId = forUser(bob).getFirst().getId(), aliceId = forUser(alice).getFirst().getId();
        for (String action : List.of("read", "open")) {
            mvc.perform(post("/notifications/" + aliceId + "/" + action).with(user(bob.getEmail())).with(csrf()))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/notifications/9223372036854775807/" + action).with(user(bob.getEmail())).with(csrf()))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(post("/notifications/" + bobId + "/open").with(user(bob.getEmail())).with(csrf()).param("target", "https://evil.example"))
                .andExpect(redirectedUrl("/bands/" + first.getId()));
        notifications.flush(); em.clear();
        var readAt = notifications.findById(bobId).orElseThrow().getReadAt(); assertThat(readAt).isNotNull();
        mvc.perform(post("/notifications/" + bobId + "/read").with(user(bob.getEmail())).with(csrf())).andExpect(redirectedUrl("/notifications"));
        em.clear(); assertThat(notifications.findById(bobId).orElseThrow().getReadAt()).isEqualTo(readAt);
        assertThat(notifications.countByRecipientIdAndReadAtIsNull(bob.getId())).isZero();
        assertThat(notifications.countByRecipientIdAndReadAtIsNull(alice.getId())).isEqualTo(1);
    }
    @Test void paginatesNewestFirstAndMarkAllReadOnlyChangesCurrentAccountsNotifications() throws Exception {
        for (int i = 0; i < 25; i++) send(alice, bob, "Notice " + i);
        send(bob, alice, "Alice only");
        var result = mvc.perform(get("/notifications").with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("unreadNotificationCount", 25L)).andReturn();
        var page = (org.springframework.data.domain.Page<?>) result.getModelAndView().getModel().get("notificationPage");
        assertThat(page.getContent()).hasSize(20); assertThat(page.getTotalElements()).isEqualTo(25);
        assertThat(((Notification) page.getContent().getFirst()).getSubject()).isEqualTo("Notice 24");
        mvc.perform(get("/notifications").with(user(bob.getEmail())).param("page", "1"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Notice 0")));
        mvc.perform(post("/notifications/read-all").with(user(bob.getEmail())).with(csrf())).andExpect(redirectedUrl("/notifications"));
        em.clear();
        mvc.perform(get("/notifications").with(user(bob.getEmail())).param("unread", "true"))
                .andExpect(model().attribute("unreadNotificationCount", 0L))
                .andExpect(content().string(containsString("Vous avez lu toutes vos notifications.")));
        assertThat(notifications.countByRecipientIdAndReadAtIsNull(alice.getId())).isEqualTo(1);
        assertThat(forUser(bob)).hasSize(25);
    }
    @Test void counterEndpointIsPrivateAndBellAndTextsAreLocalizedAndEscaped() throws Exception {
        send(alice, bob, "<script>unsafe</script>");
        mvc.perform(get("/notifications/unread-count").with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(1))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(get("/notifications/unread-count").with(user(outsider.getEmail())))
                .andExpect(jsonPath("$.unread").value(0));
        mvc.perform(get("/notifications").with(user(bob.getEmail())).param("lang", "en"))
                .andExpect(content().string(containsString("Mark all as read")))
                .andExpect(content().string(containsString("&lt;script&gt;unsafe&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>unsafe</script>"))))
                .andExpect(content().string(not(containsString("??"))));
        for (String path : List.of("/notifications", "/notifications/unread-count")) mvc.perform(get(path)).andExpect(redirectedUrl("/login"));
    }
    @Test void disabledRecipientsAndSelfActionsDoNotCreateNotificationsAndOnlyLocalTargetsAreAccepted() {
        bob.setEnabled(false); users.flush(); send(alice, bob, "Inactive"); send(alice, alice, "Self");
        assertThat(notifications.count()).isZero();
        assertThatThrownBy(() -> service.send(alice, outsider, Notification.Kind.BAND_INVITATION, "Bad", "https://evil.example"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(notifications.count()).isZero();
    }
    @Test void csrfAndPostOnlyReadActionsAndNavigationValidationProtectNotificationState() throws Exception {
        send(alice, bob, "New"); Long id = forUser(bob).getFirst().getId();
        for (String path : List.of("/notifications/" + id + "/read", "/notifications/" + id + "/open", "/notifications/read-all")) {
            mvc.perform(post(path).with(user(bob.getEmail()))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user(bob.getEmail()))).andExpect(status().isMethodNotAllowed());
        }
        mvc.perform(get("/notifications").with(user(bob.getEmail())).param("page", "-1")).andExpect(status().isBadRequest());
        assertThat(notifications.countByRecipientIdAndReadAtIsNull(bob.getId())).isEqualTo(1);
    }
    @Test void archivingLegacyQuoteReplyDoesNotCreateAnExtraMessageNotification() {
        StudioQuote quote = quotes.create(alice, studio.getId(), Map.of("title", "Legacy project", "description", "A project", "startDate", "", "endDate", "", "budget", ""));
        quote.answer("Old reply", null);
        quotes.reply(alice, quote.getId(), Map.of("message", "New follow-up", "amount", ""));
        assertThat(forUser(bob)).extracting(Notification::getKind).containsExactly(Notification.Kind.QUOTE_MESSAGE, Notification.Kind.QUOTE_REQUEST);
        assertThat(forUser(alice)).isEmpty();
    }
    List<Notification> forUser(User user) { return notifications.findByRecipientIdOrderByIdDesc(user.getId(), PageRequest.of(0, 100)).getContent(); }
    void send(User actor, User recipient, String subject) { service.send(actor, recipient, Notification.Kind.BAND_INVITATION, subject, "/bands/" + first.getId()); }
    Band band(User owner, String name) { Band band = new Band(owner); band.setName(name); band.getMembers().add(owner); return bands.saveAndFlush(band); }
    User account(String name) {
        User user = new User(); user.setUsername(name); user.setEmail(name + "@example.org"); user.setPasswordHash("unused-test-hash"); user.setEnabled(true); return users.saveAndFlush(user);
    }
}
