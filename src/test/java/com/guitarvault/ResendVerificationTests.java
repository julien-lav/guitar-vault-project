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
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class ResendVerificationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired RegistrationService registration;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    User account;
    final String previous = "a".repeat(43);

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        account = new User();
        account.setFullName("Resend Test"); account.setUsername("resendtest");
        account.setEmail("resend@example.com"); account.setPasswordHash("unused-test-hash");
        account.setEnabled(false);
        account.setVerificationTokenHash(RegistrationService.hash(previous));
        account.setVerificationExpiresAt(Instant.now().plusSeconds(3600));
        account.setVerificationRequestedAt(Instant.now().minusSeconds(61));
        users.saveAndFlush(account);
    }

    @Test
    void resendsByEmailRotatesTokenAndNewLinkActivatesAccountOnlyOnce() throws Exception {
        mvc.perform(get("/login")).andExpect(content().string(containsString("/resend-verification")));
        mvc.perform(get("/resend-verification"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("name=\"_csrf\"")));
        var result = mvc.perform(post("/resend-verification").with(csrf()).param("email", " RESEND@EXAMPLE.COM "))
                .andExpect(redirectedUrl("/resend-verification"))
                .andExpect(flash().attribute("success", "resend.sent")).andReturn();
        var sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly(account.getEmail());
        String token = Pattern.compile("token=([A-Za-z0-9_-]{43})").matcher(sent.getValue().getText())
                .results().findFirst().orElseThrow().group(1);
        assertThat(token).isNotEqualTo(previous);
        assertThat(account.getVerificationTokenHash()).isEqualTo(RegistrationService.hash(token));
        assertThat(account.getVerificationExpiresAt()).isAfter(Instant.now().plusSeconds(86000));
        assertThat(account.isEnabled()).isFalse();
        assertThat(registration.verify(previous)).isFalse();
        mvc.perform(get("/resend-verification").flashAttrs(result.getFlashMap()))
                .andExpect(content().string(containsString("Si un compte attend la vérification")));
        mvc.perform(get("/verify-email").param("token", token)).andExpect(flash().attribute("success", "verify.success"));
        assertThat(account.isEnabled()).isTrue();
        assertThat(registration.verify(token)).isFalse();
    }

    @Test
    void expiredActivationLinkCanBeReplaced() {
        account.setVerificationExpiresAt(Instant.now().minusSeconds(1));
        registration.resend(account.getEmail());
        verify(mail).send(any(SimpleMailMessage.class));
        assertThat(account.getVerificationExpiresAt()).isAfter(Instant.now());
        assertThat(account.getVerificationTokenHash()).isNotEqualTo(RegistrationService.hash(previous));
    }

    @Test
    void ignoresUnknownVerifiedAndDisabledAccountsWithoutRevealingWhichExists() throws Exception {
        mvc.perform(post("/resend-verification").with(csrf()).param("email", "unknown@example.com"))
                .andExpect(flash().attribute("success", "resend.sent"));
        account.setEnabled(true);
        mvc.perform(post("/resend-verification").with(csrf()).param("email", account.getEmail()))
                .andExpect(flash().attribute("success", "resend.sent"));
        account.setEnabled(false); account.setVerificationTokenHash(null);
        mvc.perform(post("/resend-verification").with(csrf()).param("email", account.getEmail()))
                .andExpect(flash().attribute("success", "resend.sent"));
        verifyNoInteractions(mail);
    }

    @Test
    void limitsRequestsToOnePerMinuteWithoutInvalidatingCurrentLink() {
        account.setVerificationRequestedAt(Instant.now());
        registration.resend(account.getEmail());
        verifyNoInteractions(mail);
        assertThat(account.getVerificationTokenHash()).isEqualTo(RegistrationService.hash(previous));
    }

    @Test
    void sendsEmailInSelectedLanguageAndProtectsPostWithCsrf() throws Exception {
        mvc.perform(post("/resend-verification")).andExpect(status().isForbidden());
        var english = mvc.perform(get("/resend-verification?lang=en"))
                .andExpect(content().string(containsString("Resend verification link"))).andReturn();
        mvc.perform(post("/resend-verification").with(csrf())
                        .cookie(english.getResponse().getCookie("guitar-vault-language"))
                        .param("email", account.getEmail()))
                .andExpect(redirectedUrl("/resend-verification"));
        var sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getSubject()).contains("Verify your email address");
        assertThat(sent.getValue().getText()).contains("&lang=en");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedDeliveryPreservesPreviousWorkingLink() throws Exception {
        try {
            doThrow(new MailSendException("SMTP unavailable")).when(mail).send(any(SimpleMailMessage.class));
            mvc.perform(post("/resend-verification").with(csrf()).param("email", account.getEmail()))
                    .andExpect(flash().attribute("success", "resend.sent"));
            User saved = users.findById(account.getId()).orElseThrow();
            assertThat(saved.getVerificationTokenHash()).isEqualTo(RegistrationService.hash(previous));
            assertThat(saved.getVerificationRequestedAt()).isBefore(Instant.now().minusSeconds(60));
        } finally { users.deleteById(account.getId()); }
    }
}
