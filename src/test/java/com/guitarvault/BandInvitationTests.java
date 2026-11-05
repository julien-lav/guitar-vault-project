package com.guitarvault;

import java.util.List;
import java.util.Map;
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
class BandInvitationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired BandRepository bands;
    @Autowired BandService service;
    @Autowired BandInvitationRepository invitations;
    @Autowired EntityManager entityManager;
    @MockitoBean JavaMailSender mail;
    private MockMvc mvc;
    private User alice;
    private User bob;
    private User outsider;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        alice = account("invite-alice");
        bob = account("invite-bob");
        outsider = account("invite-outsider");
    }

    @Test
    void creationInvitesRatherThanAddingMembersAndAcceptancePersistsMembership() throws Exception {
        mvc.perform(post("/bands").with(user(alice.getEmail())).with(csrf()).param("name", "Consent band")
                        .param("members", "invite-bob, invite-bob, invite-alice"))
                .andExpect(status().is3xxRedirection());
        Band band = bands.findAllByOrderByIdDesc().getFirst();
        BandInvitation invitation = invitations.findByBandIdAndInviteeId(band.getId(), bob.getId()).orElseThrow();
        assertThat(band.getMembers()).extracting(User::getId).containsExactly(alice.getId());
        assertThat(invitations.count()).isEqualTo(1);
        mvc.perform(get("/dashboard").with(user(bob.getEmail())))
                .andExpect(model().attribute("invitationCount", 1L)).andExpect(model().attribute("myBands", hasSize(0)));
        mvc.perform(get("/bands/" + band.getId()).with(user(bob.getEmail())))
                .andExpect(content().string(containsString("/invitations/" + invitation.getId() + "/accept")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(post("/invitations/" + invitation.getId() + "/accept").with(user(bob.getEmail())).with(csrf())
                        .param("inviteeId", outsider.getId().toString()))
                .andExpect(redirectedUrl("/invitations")).andExpect(flash().attribute("success", "invitation.accepted"));
        bands.flush();
        entityManager.clear();
        assertThat(service.find(band.getId()).getMembers()).extracting(User::getId).containsExactlyInAnyOrder(alice.getId(), bob.getId());
        assertThat(invitations.findById(invitation.getId()).orElseThrow().getStatus()).isEqualTo(BandInvitation.Status.ACCEPTED);
        mvc.perform(get("/dashboard").with(user(bob.getEmail())))
                .andExpect(model().attribute("invitationCount", 0L)).andExpect(model().attribute("myBands", hasSize(1)));
        mvc.perform(post("/invitations/" + invitation.getId() + "/accept").with(user(bob.getEmail())).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void invitationsArePrivateToCreatorAndRecipient() throws Exception {
        Band band = create("Private invitation", bob.getUsername());
        mvc.perform(get("/invitations").with(user(bob.getEmail())).param("ownerId", alice.getId().toString()))
                .andExpect(model().attribute("received", hasSize(1))).andExpect(model().attribute("sent", hasSize(0)))
                .andExpect(content().string(not(containsString(alice.getEmail()))));
        mvc.perform(get("/invitations").with(user(alice.getEmail())))
                .andExpect(model().attribute("received", hasSize(0))).andExpect(model().attribute("sent", hasSize(1)));
        mvc.perform(get("/invitations").with(user(outsider.getEmail())))
                .andExpect(model().attribute("received", hasSize(0))).andExpect(model().attribute("sent", hasSize(0)))
                .andExpect(content().string(not(containsString("Private invitation"))));
        mvc.perform(get("/bands/" + band.getId()).with(user(outsider.getEmail())))
                .andExpect(model().attribute("pendingInvitations", hasSize(0)))
                .andExpect(content().string(not(containsString(bob.getUsername()))));
        mvc.perform(get("/invitations").with(user(bob.getEmail())).param("lang", "en"))
                .andExpect(content().string(containsString("Received invitations")))
                .andExpect(content().string(not(containsString("??"))));
    }

    @Test
    void declineAndCancellationPreventAcceptanceUntilAnExplicitNewInvitation() throws Exception {
        Band band = create("Band", bob.getUsername());
        Long id = invitation(band, bob).getId();
        mvc.perform(post("/invitations/" + id + "/decline").with(user(bob.getEmail())).with(csrf()))
                .andExpect(redirectedUrl("/invitations"));
        assertThat(invitation(band, bob).getStatus()).isEqualTo(BandInvitation.Status.DECLINED);
        mvc.perform(post("/invitations/" + id + "/accept").with(user(bob.getEmail())).with(csrf())).andExpect(status().isConflict());
        service.invite(alice, band.getId(), bob.getUsername());
        assertThat(invitation(band, bob).getId()).isEqualTo(id);
        mvc.perform(post("/invitations/" + id + "/cancel").with(user(alice.getEmail())).with(csrf()))
                .andExpect(redirectedUrl("/invitations"));
        assertThat(invitation(band, bob).getStatus()).isEqualTo(BandInvitation.Status.CANCELLED);
        mvc.perform(post("/invitations/" + id + "/accept").with(user(bob.getEmail())).with(csrf())).andExpect(status().isConflict());
        assertThat(band.getMembers()).hasSize(1);
        service.invite(alice, band.getId(), bob.getUsername());
        service.respond(bob, id, true);
        assertThat(band.getMembers()).hasSize(2);
    }

    @Test
    void existingMembershipsSurviveProfileEditsAndNewNamesStillRequireConsent() throws Exception {
        Band band = create("Legacy members", "");
        band.getMembers().add(bob);
        bands.saveAndFlush(band);
        mvc.perform(post("/bands/" + band.getId()).with(user(alice.getEmail())).with(csrf())
                        .param("name", "Updated").param("members", outsider.getUsername()))
                .andExpect(status().is3xxRedirection());
        bands.flush();
        entityManager.clear();
        assertThat(service.find(band.getId()).getMembers()).extracting(User::getId).containsExactlyInAnyOrder(alice.getId(), bob.getId());
        assertThat(invitation(band, outsider).getStatus()).isEqualTo(BandInvitation.Status.PENDING);
        mvc.perform(get("/bands/" + band.getId() + "/edit").with(user(alice.getEmail())))
                .andExpect(model().attribute("form", hasEntry("members", "")));
    }

    @Test
    void memberCanLeaveAfterConfirmationAndCreatorCanRemoveAnotherMember() throws Exception {
        Band band = create("Band", bob.getUsername());
        service.respond(bob, invitation(band, bob).getId(), true);
        String leavePath = "/bands/" + band.getId() + "/leave";
        mvc.perform(get(leavePath).with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("name=\"_csrf\"")));
        assertThat(band.getMembers()).hasSize(2);
        mvc.perform(post(leavePath).with(user(bob.getEmail())).with(csrf())).andExpect(redirectedUrl("/invitations"));
        bands.flush();
        entityManager.clear();
        assertThat(service.find(band.getId()).getMembers()).extracting(User::getId).containsExactly(alice.getId());
        mvc.perform(post("/invitations/" + invitation(band, bob).getId() + "/accept").with(user(bob.getEmail())).with(csrf()))
                .andExpect(status().isConflict());
        service.invite(alice, band.getId(), bob.getUsername());
        service.respond(bob, invitation(band, bob).getId(), true);
        String removePath = "/bands/" + band.getId() + "/members/" + bob.getId() + "/remove";
        mvc.perform(get(removePath).with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString(bob.getUsername())));
        mvc.perform(post(removePath).with(user(alice.getEmail())).with(csrf())).andExpect(redirectedUrl("/bands/" + band.getId()));
        bands.flush();
        entityManager.clear();
        assertThat(service.find(band.getId()).getMembers()).extracting(User::getId).containsExactly(alice.getId());
    }

    @Test
    void onlyRecipientCanRespondAndOnlyCreatorCanInviteCancelOrRemove() throws Exception {
        Band band = create("Band", bob.getUsername());
        Long id = invitation(band, bob).getId();
        for (User actor : List.of(alice, outsider)) {
            for (String action : List.of("accept", "decline")) {
                mvc.perform(post("/invitations/" + id + "/" + action).with(user(actor.getEmail())).with(csrf()))
                        .andExpect(status().isNotFound());
            }
        }
        for (User actor : List.of(bob, outsider)) {
            mvc.perform(post("/invitations/" + id + "/cancel").with(user(actor.getEmail())).with(csrf()))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/bands/" + band.getId() + "/invitations").with(user(actor.getEmail())).with(csrf())
                            .param("username", outsider.getUsername())).andExpect(status().isNotFound());
            mvc.perform(post("/bands/" + band.getId() + "/members/" + alice.getId() + "/remove")
                            .with(user(actor.getEmail())).with(csrf())).andExpect(status().isNotFound());
        }
        mvc.perform(post("/bands/" + band.getId() + "/leave").with(user(alice.getEmail())).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/bands/" + band.getId() + "/members/" + alice.getId() + "/remove").with(user(alice.getEmail())).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(post("/bands/" + band.getId() + "/leave").with(user(outsider.getEmail())).with(csrf())).andExpect(status().isNotFound());
        assertThat(invitation(band, bob).getStatus()).isEqualTo(BandInvitation.Status.PENDING);
    }

    @Test
    void duplicatesDoNotCreateExtraInvitationsAndInvalidBulkInputsAreAtomic() throws Exception {
        Band band = create("Band", bob.getUsername());
        service.invite(alice, band.getId(), bob.getUsername() + "," + bob.getUsername());
        assertThat(invitations.count()).isEqualTo(1);
        User disabled = account("invite-disabled");
        disabled.setEnabled(false);
        users.flush();
        for (String names : List.of("missing-user", disabled.getUsername(), outsider.getUsername() + ",missing-user", "x".repeat(10001))) {
            mvc.perform(post("/bands/" + band.getId()).with(user(alice.getEmail())).with(csrf())
                            .param("name", "Invalid update").param("members", names))
                    .andExpect(status().isOk()).andExpect(model().attribute("error", "band.membersError"));
        }
        assertThat(band.getName()).isEqualTo("Band");
        assertThat(invitations.count()).isEqualTo(1);
        service.respond(bob, invitation(band, bob).getId(), true);
        service.invite(alice, band.getId(), bob.getUsername());
        assertThat(invitation(band, bob).getStatus()).isEqualTo(BandInvitation.Status.ACCEPTED);
    }

    @Test
    void pendingInvitationsReservePlacesAndDecliningFreesAPlace() throws Exception {
        Band band = create("Capacity", "");
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < 49; i++) names.append(account("reserved-" + i).getUsername()).append('\n');
        service.invite(alice, band.getId(), names.toString());
        assertThat(invitations.findByBandIdAndStatusOrderByIdDesc(band.getId(), BandInvitation.Status.PENDING)).hasSize(49);
        mvc.perform(post("/bands/" + band.getId() + "/invitations").with(user(alice.getEmail())).with(csrf())
                        .param("username", bob.getUsername()))
                .andExpect(flash().attribute("error", "band.membersError"));
        var declined = invitations.findByBandIdAndStatusOrderByIdDesc(band.getId(), BandInvitation.Status.PENDING).getFirst();
        service.respond(declined.getInvitee(), declined.getId(), false);
        service.invite(alice, band.getId(), bob.getUsername());
        service.respond(bob, invitation(band, bob).getId(), true);
        assertThat(band.getMembers()).hasSize(2);
        assertThat(invitations.findByBandIdAndStatusOrderByIdDesc(band.getId(), BandInvitation.Status.PENDING)).hasSize(48);
    }

    @Test
    void inactiveCreatorCannotBeJoinedButInvitationCanStillBeDeclined() throws Exception {
        Band band = create("Band", bob.getUsername());
        alice.setEnabled(false);
        users.flush();
        mvc.perform(post("/invitations/" + invitation(band, bob).getId() + "/accept").with(user(bob.getEmail())).with(csrf()))
                .andExpect(flash().attribute("error", "invitation.inactiveCreator"));
        assertThat(band.getMembers()).hasSize(1);
        mvc.perform(post("/invitations/" + invitation(band, bob).getId() + "/decline").with(user(bob.getEmail())).with(csrf()))
                .andExpect(flash().attribute("success", "invitation.declined"));
    }

    @Test
    void requiresLoginAndCsrfAndHandlesUnknownInvitations() throws Exception {
        Band band = create("Band", bob.getUsername());
        Long id = invitation(band, bob).getId();
        mvc.perform(get("/invitations")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/bands/" + band.getId() + "/leave")).andExpect(redirectedUrl("/login"));
        for (String path : List.of("/invitations/" + id + "/accept", "/invitations/" + id + "/decline", "/invitations/" + id + "/cancel",
                "/bands/" + band.getId() + "/invitations", "/bands/" + band.getId() + "/leave", "/bands/" + band.getId() + "/members/" + bob.getId() + "/remove")) {
            mvc.perform(post(path).with(csrf())).andExpect(redirectedUrl("/login"));
            mvc.perform(post(path).with(user(bob.getEmail()))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/invitations/9223372036854775807/accept").with(user(bob.getEmail())).with(csrf()))
                .andExpect(status().isNotFound());
    }

    private BandInvitation invitation(Band band, User user) {
        return invitations.findByBandIdAndInviteeId(band.getId(), user.getId()).orElseThrow();
    }
    private Band create(String name, String members) {
        return service.save(alice, null, Map.of("name", name, "genre", "", "city", "", "description", "",
                "spotifyUrl", "", "deezerUrl", "", "websiteUrl", "", "members", members));
    }
    private User account(String name) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@example.org");
        user.setPasswordHash("unused-test-hash");
        user.setEnabled(true);
        return users.saveAndFlush(user);
    }
}
