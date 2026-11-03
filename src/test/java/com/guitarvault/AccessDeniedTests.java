package com.guitarvault;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
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
class AccessDeniedTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;

    @BeforeEach
    void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test
    void deniedAdministrationUsesTheRecoveryPageAndStillRequiresAdmin() throws Exception {
        mvc.perform(get("/admin/articles").with(user("member@example.com")))
                .andExpect(status().isForbidden()).andExpect(forwardedUrl("/access-denied"));
        mvc.perform(post("/admin/articles").with(user("member@example.com")).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(forwardedUrl("/access-denied"));
        mvc.perform(get("/admin/articles")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/access-denied").with(user("member@example.com")))
                .andExpect(status().isForbidden()).andExpect(content().string(containsString("Accès réservé")))
                .andExpect(content().string(containsString("member@example.com")))
                .andExpect(content().string(containsString("Se déconnecter pour changer de compte")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("Whitelabel"))));
    }

    @Test
    void missingAndInvalidTokensDoNotCreateAccountsAndUseTheRecoveryPage() throws Exception {
        long before = users.count();
        mvc.perform(post("/signup").param("email", "recovery@example.com"))
                .andExpect(status().isForbidden()).andExpect(forwardedUrl("/access-denied"));
        mvc.perform(post("/signup").with(csrf().useInvalidToken()).param("email", "recovery@example.com"))
                .andExpect(status().isForbidden()).andExpect(forwardedUrl("/access-denied"));
        assertThat(users.count()).isEqualTo(before);
        mvc.perform(get("/access-denied")
                        .requestAttr(WebAttributes.ACCESS_DENIED_403, new MissingCsrfTokenException(null))
                        .requestAttr(RequestDispatcher.FORWARD_REQUEST_URI, "/signup"))
                .andExpect(status().isForbidden()).andExpect(model().attribute("recoveryUrl", "/signup"))
                .andExpect(content().string(containsString("Le formulaire doit être rechargé")))
                .andExpect(content().string(containsString("Cette demande n’a pas été exécutée")))
                .andExpect(content().string(containsString("href=\"/signup\"")));
    }

    @Test
    void freshFormAfterTokenFailureCanBeSubmittedNormally() throws Exception {
        mvc.perform(post("/signup").param("_csrf", "old-session-token"))
                .andExpect(status().isForbidden());
        var fresh = mvc.perform(get("/signup")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) fresh.getRequest().getSession(false);
        var token = (CsrfToken) fresh.getRequest().getAttribute(CsrfToken.class.getName());
        mvc.perform(post("/signup").session(session).param(token.getParameterName(), token.getToken())
                        .param("fullName", "Recovery test").param("username", "recovery")
                        .param("email", "recovery@example.com").param("password", "Secret123!")
                        .param("confirmPassword", "Secret123!"))
                .andExpect(redirectedUrl("/login"));
        assertThat(users.findByEmail("recovery@example.com")).isPresent();
    }

    @Test
    void recoveryDestinationsAreLocalAndRespectApplicationContext() throws Exception {
        mvc.perform(get("/vault/access-denied").contextPath("/vault")
                        .requestAttr(WebAttributes.ACCESS_DENIED_403, new MissingCsrfTokenException(null))
                        .requestAttr(RequestDispatcher.FORWARD_REQUEST_URI, "/vault/signup"))
                .andExpect(status().isForbidden()).andExpect(content().string(containsString("href=\"/vault/signup\"")));
        mvc.perform(get("/access-denied")
                        .requestAttr(WebAttributes.ACCESS_DENIED_403, new MissingCsrfTokenException(null))
                        .requestAttr(RequestDispatcher.FORWARD_REQUEST_URI, "https://example.com/untrusted"))
                .andExpect(status().isForbidden()).andExpect(model().attribute("recoveryUrl", "/dashboard"))
                .andExpect(content().string(not(containsString("https://example.com/untrusted"))));
    }

    @Test
    void expiredFormPageIsTranslatedAndDoesNotEchoSubmittedSecrets() throws Exception {
        mvc.perform(get("/access-denied").param("lang", "en").param("password", "private-test-password")
                        .param("token", "private-test-token")
                        .requestAttr(WebAttributes.ACCESS_DENIED_403, new MissingCsrfTokenException(null))
                        .requestAttr(RequestDispatcher.FORWARD_REQUEST_URI, "/reset-password"))
                .andExpect(status().isForbidden()).andExpect(model().attribute("recoveryUrl", "/forgot-password"))
                .andExpect(content().string(containsString("Reload this form")))
                .andExpect(content().string(not(containsString("private-test-password"))))
                .andExpect(content().string(not(containsString("private-test-token"))))
                .andExpect(content().string(not(containsString("??"))));
    }
}
