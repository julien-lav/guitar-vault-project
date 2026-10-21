package com.guitarvault;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class LanguageTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;

    @BeforeEach
    void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test
    void englishChoicePersistsInCookieAcrossPagesAndCanBeChangedBack() throws Exception {
        var result = mvc.perform(get("/login?lang=en"))
                .andExpect(content().string(containsString("lang=\"en\"")))
                .andExpect(content().string(containsString("Welcome back.")))
                .andExpect(content().string(containsString("Show password")))
                .andExpect(content().string(containsString("Forgot password?")))
                .andExpect(cookie().value("guitar-vault-language", "en")).andReturn();
        var language = result.getResponse().getCookie("guitar-vault-language");
        mvc.perform(get("/signup").cookie(language))
                .andExpect(content().string(containsString("Full name")))
                .andExpect(content().string(containsString("including a special character")))
                .andExpect(content().string(containsString("Beginner")))
                .andExpect(content().string(not(containsString("Mot de passe"))))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/login?lang=fr").cookie(language))
                .andExpect(cookie().value("guitar-vault-language", "fr"))
                .andExpect(content().string(containsString("Heureux de vous retrouver.")));
    }

    @Test
    void defaultsToFrenchAndIgnoresUnsupportedLanguages() throws Exception {
        for (String path : List.of("/login", "/login?lang=de", "/login?lang=../../invalid")) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(content().string(containsString("Heureux de vous retrouver.")));
        }
    }

    @Test
    void languageLinksPreserveResetTokenAndBothRecoveryPagesAreTranslated() throws Exception {
        String token = "a".repeat(43);
        mvc.perform(get("/reset-password").param("token", token).param("lang", "en"))
                .andExpect(content().string(containsString("Choose a new password")))
                .andExpect(content().string(containsString("/reset-password?token=" + token + "&amp;lang=fr")))
                .andExpect(content().string(containsString("Request a new link")));
        mvc.perform(get("/forgot-password?lang=en"))
                .andExpect(content().string(containsString("Send reset link")))
                .andExpect(content().string(containsString("Email address")));
    }

    @Test
    void englishDashboardAndFormUseTranslatedLabelsAndStoredValuesStayStable() throws Exception {
        User account = new User();
        account.setUsername("languagetest"); account.setEmail("language@example.com");
        account.setFullName("Alex"); account.setPasswordHash("unused-test-hash"); account.setEnabled(true);
        users.saveAndFlush(account);
        mvc.perform(get("/dashboard?lang=en").with(user(account.getEmail())))
                .andExpect(content().string(containsString("Hello Alex!")))
                .andExpect(content().string(containsString("Your collection starts here")))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/instruments/new?lang=en").with(user(account.getEmail())))
                .andExpect(content().string(containsString("Name / model")))
                .andExpect(content().string(containsString("Needs repair")))
                .andExpect(content().string(containsString("value=\"À réparer\"")));
    }

    @Test
    void validationAndVerificationEmailsUseTheSelectedLanguage() throws Exception {
        var result = mvc.perform(get("/signup?lang=en")).andReturn();
        var language = result.getResponse().getCookie("guitar-vault-language");
        mvc.perform(post("/signup").cookie(language).with(csrf())
                        .param("fullName", "Alex").param("username", "englishsignup")
                        .param("email", "english@example.com").param("password", "NoSpecial123").param("confirmPassword", "NoSpecial123"))
                .andExpect(content().string(containsString("Your password must contain at least 8 characters")));
        mvc.perform(post("/signup").cookie(language).with(csrf())
                        .param("fullName", "Alex").param("username", "englishsignup")
                        .param("email", "english@example.com").param("password", "Secret123!").param("confirmPassword", "Secret123!"))
                .andExpect(redirectedUrl("/login"));
        var sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getSubject()).contains("Verify your email address");
        assertThat(sent.getValue().getText()).contains("24 hours", "&lang=en", "Hello Alex");
    }
}
