package com.guitarvault;

import java.time.Instant;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class PasswordResetTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordResetService reset;
    @Autowired PasswordEncoder encoder;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    User account;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        account = new User();
        account.setUsername("resettest");
        account.setFullName("Reset Test");
        account.setEmail("reset@example.com");
        account.setEnabled(true);
        account.setPasswordHash(encoder.encode("Original123!"));
        users.saveAndFlush(account);
    }

    @Test
    void recoveryLinkChangesPasswordOnceAndExpiresExistingSessions() throws Exception {
        var login = mvc.perform(post("/login").with(csrf()).param("email", account.getEmail()).param("password", "Original123!"))
                .andExpect(authenticated()).andReturn();
        MockHttpSession oldSession = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/login")).andExpect(content().string(containsString("/forgot-password")))
                .andExpect(content().string(containsString("class=\"password-toggle\"")))
                .andExpect(content().string(containsString("aria-controls=\"password\"")));
        mvc.perform(post("/forgot-password").with(csrf()).param("email", " RESET@EXAMPLE.COM "))
                .andExpect(redirectedUrl("/forgot-password")).andExpect(flash().attribute("success", "forgot.sent"));
        String token = sentToken();
        assertThat(account.getPasswordResetHash()).isEqualTo(RegistrationService.hash(token)).isNotEqualTo(token);
        assertThat(account.getPasswordResetExpiresAt()).isBetween(Instant.now().plusSeconds(1700), Instant.now().plusSeconds(1800));
        mvc.perform(get("/reset-password").param("token", token))
                .andExpect(status().isOk()).andExpect(model().attribute("validToken", true))
                .andExpect(content().string(containsString("aria-controls=\"confirmPassword\"")));
        mvc.perform(post("/reset-password").with(csrf()).param("token", token)
                        .param("password", "Replacement123!").param("confirmPassword", "Replacement123!"))
                .andExpect(redirectedUrl("/login")).andExpect(flash().attribute("success", "reset.success"));
        assertThat(encoder.matches("Replacement123!", account.getPasswordHash())).isTrue();
        assertThat(account.getPasswordResetHash()).isNull();
        assertThat(reset.valid(token)).isFalse();
        mvc.perform(get("/dashboard").session(oldSession)).andExpect(redirectedUrl("/login?expired"));
        mvc.perform(post("/login").with(csrf()).param("email", account.getEmail()).param("password", "Original123!"))
                .andExpect(unauthenticated());
        mvc.perform(post("/login").with(csrf()).param("email", account.getEmail()).param("password", "Replacement123!"))
                .andExpect(authenticated());
        mvc.perform(post("/reset-password").with(csrf()).param("token", token)
                        .param("password", "Another123!").param("confirmPassword", "Another123!"))
                .andExpect(model().attribute("validToken", false));
    }

    @Test
    void unknownAndInactiveAccountsGetSameResponseWithoutAnEmail() throws Exception {
        account.setEnabled(false);
        users.saveAndFlush(account);
        for (String email : new String[]{"missing@example.com", account.getEmail(), "invalid"}) {
            mvc.perform(post("/forgot-password").with(csrf()).param("email", email))
                    .andExpect(redirectedUrl("/forgot-password")).andExpect(flash().attribute("success", "forgot.sent"));
        }
        verifyNoInteractions(mail);
        assertThat(account.getPasswordResetHash()).isNull();
    }

    @Test
    void malformedAndExpiredTokensCannotChangePassword() throws Exception {
        account.setPasswordResetHash(RegistrationService.hash("a".repeat(43)));
        account.setPasswordResetExpiresAt(Instant.now().minusSeconds(1));
        users.saveAndFlush(account);
        for (String token : new String[]{"a".repeat(43), "b".repeat(43), "invalid", ""}) {
            mvc.perform(get("/reset-password").param("token", token)).andExpect(model().attribute("validToken", false));
            mvc.perform(post("/reset-password").with(csrf()).param("token", token)
                            .param("password", "Replacement123!").param("confirmPassword", "Replacement123!"))
                    .andExpect(model().attribute("validToken", false));
        }
        assertThat(encoder.matches("Original123!", account.getPasswordHash())).isTrue();
        mvc.perform(get("/reset-password")).andExpect(model().attribute("validToken", false));
    }

    @Test
    void passwordRulesAndConfirmationApplyWithoutConsumingToken() throws Exception {
        reset.request(account.getEmail());
        String token = sentToken();
        for (String password : new String[]{"short!", "NoSpecial123", "é".repeat(37) + "!"}) {
            mvc.perform(post("/reset-password").with(csrf()).param("token", token)
                            .param("password", password).param("confirmPassword", password))
                    .andExpect(model().attributeExists("error")).andExpect(model().attribute("validToken", true));
        }
        mvc.perform(post("/reset-password").with(csrf()).param("token", token)
                        .param("password", "Replacement123!").param("confirmPassword", "different"))
                .andExpect(model().attribute("error", "password.mismatch"));
        assertThat(reset.valid(token)).isTrue();
        assertThat(encoder.matches("Original123!", account.getPasswordHash())).isTrue();
    }

    @Test
    void throttlesRequestsAndInvalidatesPreviousTokenWhenReissued() {
        reset.request(account.getEmail());
        String first = sentToken();
        reset.request(account.getEmail());
        verify(mail, times(1)).send(any(SimpleMailMessage.class));
        account.setPasswordResetRequestedAt(Instant.now().minusSeconds(61));
        reset.request(account.getEmail());
        var sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, times(2)).send(sent.capture());
        String second = token(sent.getAllValues().getLast());
        assertThat(reset.valid(first)).isFalse();
        assertThat(reset.valid(second)).isTrue();
    }

    @Test
    void requiresCsrfForBothForms() throws Exception {
        mvc.perform(post("/forgot-password")).andExpect(status().isForbidden());
        mvc.perform(post("/reset-password")).andExpect(status().isForbidden());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void smtpFailureKeepsGenericResponseAndRollsBackResetToken() throws Exception {
        try {
            doThrow(new org.springframework.mail.MailSendException("SMTP unavailable"))
                    .when(mail).send(any(SimpleMailMessage.class));
            mvc.perform(post("/forgot-password").with(csrf()).param("email", account.getEmail()))
                    .andExpect(redirectedUrl("/forgot-password"))
                    .andExpect(flash().attribute("success", "forgot.sent"));
            User saved = users.findById(account.getId()).orElseThrow();
            assertThat(saved.getPasswordResetHash()).isNull();
            assertThat(saved.getPasswordResetRequestedAt()).isNull();
        } finally {
            users.deleteById(account.getId());
        }
    }

    private String sentToken() {
        var sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        return token(sent.getValue());
    }

    private String token(SimpleMailMessage message) {
        return Pattern.compile("token=([A-Za-z0-9_-]{43})").matcher(message.getText())
                .results().findFirst().orElseThrow().group(1);
    }
}
