package com.guitarvault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class LoginTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.mail.javamail.JavaMailSender mail;
    @Autowired PasswordEncoder encoder;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void showsPublicLoginWithCsrfAndSignupLink() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("autocomplete=\"current-password\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("href=\"/signup\"")));
    }

    @Test
    void requiresLoginForDashboard() throws Exception {
        mvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void registersLogsInAndLogsOut() throws Exception {
        mvc.perform(post("/signup").with(csrf())
                        .param("fullName", "Julien").param("username", "julien")
                        .param("email", "julien@example.com").param("password", "Secret123!")
                        .param("confirmPassword", "Secret123!"))
                .andExpect(redirectedUrl("/login"));
        mvc.perform(post("/login").with(csrf())
                        .param("email", "julien@example.com").param("password", "Secret123!"))
                .andExpect(unauthenticated()).andExpect(redirectedUrl("/login?error"));
        var sent = org.mockito.ArgumentCaptor.forClass(org.springframework.mail.SimpleMailMessage.class);
        org.mockito.Mockito.verify(mail).send(sent.capture());
        String token = java.util.regex.Pattern.compile("token=([A-Za-z0-9_-]{43})")
                .matcher(sent.getValue().getText()).results().findFirst().orElseThrow().group(1);
        mvc.perform(get("/verify-email").param("token", token))
                .andExpect(redirectedUrl("/login")).andExpect(flash().attributeExists("success"));
        var result = mvc.perform(post("/login").with(csrf())
                        .param("email", "  JULIEN@EXAMPLE.COM ").param("password", "Secret123!"))
                .andExpect(authenticated().withUsername("julien@example.com"))
                .andExpect(redirectedUrl("/dashboard"))
                .andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mvc.perform(get("/dashboard").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("julien@example.com")))
                .andExpect(content().string(containsString("Se déconnecter")));
        mvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(redirectedUrl("/login?logout"))
                .andExpect(unauthenticated());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/dashboard")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/login?logout"))
                .andExpect(content().string(containsString("Vous êtes bien déconnecté.")));
    }

    @Test
    void rejectsWrongPasswordUnknownEmailAndDisabledAccount() throws Exception {
        User user = new User();
        user.setUsername("disabled");
        user.setEmail("disabled@example.com");
        user.setPasswordHash(encoder.encode("Secret123!"));
        user.setEnabled(false);
        users.saveAndFlush(user);
        for (String email : new String[]{"unknown@example.com", "disabled@example.com"}) {
            mvc.perform(post("/login").with(csrf()).param("email", email).param("password", "Secret123!"))
                    .andExpect(redirectedUrl("/login?error"))
                    .andExpect(unauthenticated());
        }
        user.setEnabled(true);
        users.saveAndFlush(user);
        mvc.perform(post("/login").with(csrf()).param("email", "disabled@example.com").param("password", "wrong"))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
        mvc.perform(get("/login?error"))
                .andExpect(content().string(containsString("Adresse email ou mot de passe incorrect.")));
    }

    @Test
    void rejectsStateChangingRequestsWithoutCsrf() throws Exception {
        mvc.perform(post("/login").param("email", "julien@example.com").param("password", "Secret123!"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/signup")).andExpect(status().isForbidden());
        mvc.perform(post("/logout")).andExpect(status().isForbidden());
    }
}
