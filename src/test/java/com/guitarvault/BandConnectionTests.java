package com.guitarvault;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
class BandConnectionTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired BandRepository bands;
    @Autowired BandConnectionRepository connections;
    @Autowired BandConnectionService service;
    @Autowired EntityManager em;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    User alice, bob, outsider;
    Band first, second;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        alice = account("connection-alice"); bob = account("connection-bob"); outsider = account("connection-other");
        first = band(alice, "First Band"); second = band(bob, "Second Band");
    }

    @Test void requestRequiresConsentAndPublishesSymmetricallyAfterAcceptance() throws Exception {
        mvc.perform(get("/bands/" + second.getId()).with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("name=\"senderId\"")))
                .andExpect(content().string(containsString("First Band")));
        send("FRIEND", "", "");
        var connection = only();
        assertThat(connection.getSender().getId()).isEqualTo(first.getId());
        assertThat(connection.getRecipient().getId()).isEqualTo(second.getId());
        mvc.perform(get("/bands/" + first.getId()).with(user(outsider.getEmail())))
                .andExpect(model().attribute("friendConnections", hasSize(0)))
                .andExpect(model().attribute("pendingConnections", hasSize(0)))
                .andExpect(content().string(not(containsString("Second Band"))));
        mvc.perform(get("/band-connections").with(user(bob.getEmail())))
                .andExpect(model().attribute("incomingConnections", hasSize(1)))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        action(connection.getId(), bob, "accept");
        connections.flush(); em.clear();
        assertThat(only().getStatus()).isEqualTo(BandConnection.Status.ACCEPTED);
        for (Band band : java.util.List.of(first, second)) {
            mvc.perform(get("/bands/" + band.getId()).with(user(outsider.getEmail())))
                    .andExpect(status().isOk()).andExpect(model().attribute("friendConnections", hasSize(1)))
                    .andExpect(content().string(containsString(band.getId().equals(first.getId()) ? "Second Band" : "First Band")));
        }
    }

    @Test void sharedShowPreservesDateAndEscapesVenueAndCanCoexistWithFriendship() throws Exception {
        send("FRIEND", "", ""); action(only().getId(), bob, "accept");
        String date = LocalDate.now(ZoneOffset.UTC).minusDays(2).toString();
        send("PLAYED_TOGETHER", date, "  Venue <script>show</script>  ");
        var show = connections.findAll().stream().filter(c -> c.getType() == BandConnection.Type.PLAYED_TOGETHER).findFirst().orElseThrow();
        action(show.getId(), bob, "accept");
        connections.flush(); em.clear();
        assertThat(connections.findById(show.getId()).orElseThrow().getPlayedOn()).isEqualTo(date);
        mvc.perform(get("/bands/" + first.getId()).with(user(outsider.getEmail())))
                .andExpect(model().attribute("friendConnections", hasSize(1)))
                .andExpect(model().attribute("playedConnections", hasSize(1)))
                .andExpect(content().string(containsString("Venue &lt;script&gt;show&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>show</script>"))));
    }

    @Test void reverseRequestsDoNotDuplicateOrSilentlyAcceptExistingRequest() throws Exception {
        send("FRIEND", "", "");
        mvc.perform(post("/bands/" + first.getId() + "/connections").with(user(bob.getEmail())).with(csrf())
                        .param("senderId", second.getId().toString()).param("type", "FRIEND"))
                .andExpect(flash().attribute("error", "connection.existsError"));
        assertThat(connections.count()).isEqualTo(1);
        assertThat(only().getStatus()).isEqualTo(BandConnection.Status.PENDING);
        action(only().getId(), bob, "accept");
        mvc.perform(post("/bands/" + second.getId() + "/connections").with(user(alice.getEmail())).with(csrf())
                        .param("senderId", first.getId().toString()).param("type", "FRIEND"))
                .andExpect(flash().attribute("error", "connection.existsError"));
        assertThat(connections.count()).isEqualTo(1);
    }

    @Test void onlyRecipientManagerMayAcceptAndMembersCannotForgeRequests() throws Exception {
        first.getMembers().add(outsider); second.getMembers().add(outsider); bands.flush();
        mvc.perform(post("/bands/" + second.getId() + "/connections").with(user(outsider.getEmail())).with(csrf())
                        .param("senderId", first.getId().toString()).param("type", "FRIEND"))
                .andExpect(status().isNotFound());
        send("FRIEND", "", "");
        for (User actor : java.util.List.of(alice, outsider)) {
            mvc.perform(post("/band-connections/" + only().getId() + "/accept").with(user(actor.getEmail())).with(csrf()))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(post("/band-connections/" + only().getId() + "/cancel").with(user(bob.getEmail())).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/band-connections").with(user(outsider.getEmail())))
                .andExpect(model().attribute("incomingConnections", hasSize(0)))
                .andExpect(model().attribute("outgoingConnections", hasSize(0)));
        assertThat(only().getStatus()).isEqualTo(BandConnection.Status.PENDING);
    }

    @Test void declineAndCancellationAllowNewRequestWithFreshConsentAndDetails() throws Exception {
        send("PLAYED_TOGETHER", "2020-01-01", "Old show");
        Long id = only().getId(); action(id, bob, "decline");
        mvc.perform(post("/bands/" + first.getId() + "/connections").with(user(bob.getEmail())).with(csrf())
                        .param("senderId", second.getId().toString()).param("type", "PLAYED_TOGETHER"))
                .andExpect(flash().attribute("success", "connection.sent"));
        assertThat(only().getSender().getId()).isEqualTo(second.getId());
        assertThat(only().getPlayedOn()).isNull(); assertThat(only().getVenue()).isEmpty();
        action(id, bob, "cancel");
        assertThat(only().getStatus()).isEqualTo(BandConnection.Status.CANCELLED);
        send("PLAYED_TOGETHER", "", "");
        action(id, bob, "accept");
        assertThat(connections.count()).isEqualTo(1);
    }

    @Test void eitherManagerCanRemoveWithConfirmationAndStaleActionsAreRejected() throws Exception {
        send("FRIEND", "", ""); Long id = only().getId(); action(id, bob, "accept");
        mvc.perform(get("/band-connections/" + id + "/remove").with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(get("/band-connections/" + id + "/remove").with(user(outsider.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(post("/band-connections/" + id + "/accept").with(user(bob.getEmail())).with(csrf())).andExpect(status().isConflict());
        action(id, alice, "remove");
        for (Band band : java.util.List.of(first, second)) {
            mvc.perform(get("/bands/" + band.getId()).with(user(outsider.getEmail())))
                    .andExpect(model().attribute("friendConnections", hasSize(0)));
        }
        assertThat(bands.count()).isEqualTo(2);
        send("FRIEND", "", ""); action(id, bob, "accept"); action(id, bob, "remove");
        assertThat(only().getStatus()).isEqualTo(BandConnection.Status.REMOVED);
    }

    @Test void validatesRequestBeforePersistingAndPreservesEnteredFields() throws Exception {
        for (String date : java.util.List.of("invalid", "0000-01-01", "2020-02-30", LocalDate.now(ZoneOffset.UTC).plusDays(1).toString())) {
            invalid("PLAYED_TOGETHER", date, "Keep this venue", "connection.dateError");
        }
        invalid("INVALID", "", "", "connection.typeError");
        invalid("FRIEND", "2020-01-01", "", "connection.concertOnly");
        invalid("FRIEND", "", "A venue", "connection.concertOnly");
        invalid("PLAYED_TOGETHER", "", "x".repeat(201), "connection.venueError");
        mvc.perform(post("/bands/" + first.getId() + "/connections").with(user(alice.getEmail())).with(csrf())
                        .param("senderId", first.getId().toString()).param("type", "FRIEND"))
                .andExpect(flash().attribute("error", "connection.selfError"));
        mvc.perform(post("/bands/" + second.getId() + "/connections").with(user(alice.getEmail())).with(csrf()).param("type", "FRIEND"))
                .andExpect(flash().attribute("error", "connection.senderError"));
        assertThat(connections.count()).isZero();
    }

    @Test void disabledManagersCannotReceiveRequestsOrHaveRequestsAccepted() throws Exception {
        bob.setEnabled(false); users.flush();
        invalid("FRIEND", "", "", "connection.inactiveError");
        bob.setEnabled(true); users.flush(); send("FRIEND", "", "");
        alice.setEnabled(false); users.flush();
        mvc.perform(post("/band-connections/" + only().getId() + "/accept").with(user(bob.getEmail())).with(csrf()))
                .andExpect(flash().attribute("error", "connection.inactiveError"));
        assertThat(only().getStatus()).isEqualTo(BandConnection.Status.PENDING);
    }

    @Test void requiresLoginCsrfAndLocalizesPagesAndRejectsMissingTargets() throws Exception {
        mvc.perform(get("/band-connections")).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/bands/" + second.getId() + "/connections").with(user(alice.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(get("/band-connections").with(user(alice.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Band connections")))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/bands/" + second.getId()).with(user(alice.getEmail())).param("lang", "en"))
                .andExpect(content().string(containsString("We played together")))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(post("/band-connections/9223372036854775807/accept").with(user(bob.getEmail())).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/bands/9223372036854775807/connections").with(user(alice.getEmail())).with(csrf())
                        .param("senderId", first.getId().toString()).param("type", "FRIEND"))
                .andExpect(status().isNotFound());
    }

    void send(String type, String date, String venue) throws Exception {
        mvc.perform(post("/bands/" + second.getId() + "/connections").with(user(alice.getEmail())).with(csrf())
                        .param("senderId", first.getId().toString()).param("type", type).param("playedOn", date).param("venue", venue))
                .andExpect(flash().attribute("success", "connection.sent"));
    }
    void invalid(String type, String date, String venue, String error) throws Exception {
        mvc.perform(post("/bands/" + second.getId() + "/connections").with(user(alice.getEmail())).with(csrf())
                        .param("senderId", first.getId().toString()).param("type", type).param("playedOn", date).param("venue", venue))
                .andExpect(flash().attribute("error", error))
                .andExpect(flash().attribute("connectionForm", hasEntry("venue", venue)));
    }
    void action(Long id, User actor, String action) throws Exception {
        mvc.perform(post("/band-connections/" + id + "/" + action).with(user(actor.getEmail())).with(csrf()))
                .andExpect(redirectedUrl("/band-connections")).andExpect(flash().attribute("success", "connection.done." + action));
    }
    BandConnection only() { return connections.findAll().getFirst(); }
    Band band(User owner, String name) {
        Band band = new Band(owner); band.setName(name); band.getMembers().add(owner); return bands.saveAndFlush(band);
    }
    User account(String name) {
        User user = new User(); user.setUsername(name); user.setEmail(name + "@example.org");
        user.setPasswordHash("unused-test-hash"); user.setEnabled(true); return users.saveAndFlush(user);
    }
}
