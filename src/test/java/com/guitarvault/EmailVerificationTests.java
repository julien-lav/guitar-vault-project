package com.guitarvault;

import java.time.Instant;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class EmailVerificationTests {
    @Autowired UserRepository users;
    @Autowired RegistrationService registration;
    @Autowired WebApplicationContext context;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void sendsExpiringSingleUseVerificationLinkAndStoresOnlyItsHash() throws Exception {
        User user = newUser();
        registration.register(user);
        var sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        SimpleMailMessage message = sent.getValue();
        assertThat(message.getTo()).containsExactly(user.getEmail());
        assertThat(message.getFrom()).isEqualTo("Guitar Vault <no-reply@guitar-vault.local>");
        assertThat(message.getSubject()).contains("Vérifiez");
        assertThat(message.getText()).contains("http://localhost:8081/verify-email?token=", "24 heures");
        String token = Pattern.compile("token=([A-Za-z0-9_-]{43})").matcher(message.getText())
                .results().findFirst().orElseThrow().group(1);
        assertThat(user.isEnabled()).isFalse();
        assertThat(user.getVerificationTokenHash()).isEqualTo(RegistrationService.hash(token)).isNotEqualTo(token);
        assertThat(user.getVerificationExpiresAt()).isBetween(Instant.now().plusSeconds(86000), Instant.now().plusSeconds(86400));
        mvc.perform(get("/verify-email").param("token", token))
                .andExpect(redirectedUrl("/login")).andExpect(flash().attributeExists("success"));
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getVerificationTokenHash()).isNull();
        assertThat(user.getVerificationExpiresAt()).isNull();
        mvc.perform(get("/verify-email").param("token", token))
                .andExpect(flash().attributeExists("verificationError"));
    }

    @Test
    void rejectsExpiredUnknownMalformedAndMissingTokensWithoutActivatingAccount() throws Exception {
        User user = newUser();
        String expiredToken = "a".repeat(43);
        user.setEnabled(false);
        user.setVerificationTokenHash(RegistrationService.hash(expiredToken));
        user.setVerificationExpiresAt(Instant.now().minusSeconds(1));
        users.saveAndFlush(user);
        for (String token : new String[]{expiredToken, "b".repeat(43), "invalid", ""}) {
            mvc.perform(get("/verify-email").param("token", token))
                    .andExpect(redirectedUrl("/login")).andExpect(flash().attributeExists("verificationError"));
        }
        var result = mvc.perform(get("/verify-email"))
                .andExpect(flash().attributeExists("verificationError")).andReturn();
        mvc.perform(get("/login").flashAttrs(result.getFlashMap()))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ce lien de vérification est invalide")));
        assertThat(user.isEnabled()).isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void mailFailureRollsBackRegistrationAndLetsTheUserRetry() throws Exception {
        doThrow(new MailSendException("SMTP unavailable")).when(mail).send(any(SimpleMailMessage.class));
        mvc.perform(post("/signup").with(csrf()).param("fullName", "Mail failure test")
                        .param("username", "mailfailure").param("email", "mailfailure@example.com")
                        .param("password", "Secret123!").param("confirmPassword", "Secret123!"))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Votre inscription n’a pas été enregistrée")));
        assertThat(users.findByEmail("mailfailure@example.com")).isEmpty();
    }

    private User newUser() {
        User user = new User();
        user.setFullName("Verification Test");
        user.setUsername("verificationtest");
        user.setEmail("verify@example.com");
        user.setPasswordHash("unused-test-hash");
        return user;
    }
}
