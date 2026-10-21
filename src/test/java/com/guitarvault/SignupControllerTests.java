package com.guitarvault;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class SignupControllerTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.mail.javamail.JavaMailSender mail;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void displaysSignupFormAndNavigationLink() throws Exception {
        mvc.perform(get("/signup"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Créer mon compte")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("autocomplete=\"new-password\"")));
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/signup\"")));
    }

    @Test
    void savesNormalizedAccountWithHashedPasswordAndConfirmsSuccess() throws Exception {
        var result = mvc.perform(request(Map.of("email", "  JULIEN@EXAMPLE.COM ", "fullName", " Julien ")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"))
                .andExpect(flash().attributeExists("success"))
                .andReturn();
        User saved = users.findByEmail("julien@example.com").orElseThrow();
        assertThat(saved.getFullName()).isEqualTo("Julien");
        assertThat(saved.isEnabled()).isFalse();
        assertThat(saved.getVerificationTokenHash()).hasSize(64);
        assertThat(saved.getVerificationExpiresAt()).isAfter(java.time.Instant.now());
        assertThat(new BCryptPasswordEncoder().matches("Secret123!", saved.getPasswordHash())).isTrue();
        mvc.perform(get("/login").flashAttrs(result.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Votre compte a bien été créé")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Se connecter")));
    }

    @ParameterizedTest
    @CsvSource({"fullName, ' '", "username, ab", "email, invalid", "password, short", "password, Secret123", "password, Secreté12", "password, 'Secret 123'", "confirmPassword, different", "instrument, Invalid", "level, Invalid"})
    void rejectsInvalidInputWithoutSaving(String field, String value) throws Exception {
        long before = users.count();
        Map<String, String> input = field.equals("password")
                ? Map.of("password", value, "confirmPassword", value) : Map.of(field, value);
        mvc.perform(request(input))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error", "form"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("role=\"alert\"")));
        assertThat(users.count()).isEqualTo(before);
    }

    @Test
    void rejectsMissingFieldsAndOversizedInputs() throws Exception {
        mvc.perform(post("/signup").with(csrf())).andExpect(model().attributeExists("error"));
        for (var invalid : Map.of("bio", "b".repeat(1001), "city", "c".repeat(101),
                "password", "é".repeat(37)).entrySet()) {
            mvc.perform(request(Map.of(invalid.getKey(), invalid.getValue())))
                    .andExpect(model().attributeExists("error"));
        }
        assertThat(users.count()).isZero();
    }

    @Test
    void rejectsDuplicateEmailAndUsernameRegardlessOfCase() throws Exception {
        mvc.perform(request(Map.of())).andExpect(status().is3xxRedirection());
        mvc.perform(request(Map.of("username", "another", "email", "JULIEN@EXAMPLE.COM")))
                .andExpect(model().attribute("error", "signup.emailUsed"));
        mvc.perform(request(Map.of("username", "JULIEN", "email", "other@example.com")))
                .andExpect(model().attribute("error", "signup.usernameUsed"));
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void preservesProfileButNeverReturnsPasswordsAfterValidationError() throws Exception {
        var result = mvc.perform(request(Map.of("confirmPassword", "wrong", "city", "Paris")))
                .andExpect(model().attributeExists("error"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"Paris\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Secret123!"))))
                .andReturn();
        Map<?, ?> form = (Map<?, ?>) result.getModelAndView().getModel().get("form");
        assertThat(form.containsKey("password")).isFalse();
        assertThat(form.containsKey("confirmPassword")).isFalse();
    }

    private MockHttpServletRequestBuilder request(Map<String, String> overrides) {
        Map<String, String> params = new HashMap<>(Map.of("fullName", "Julien", "username", "julien",
                "email", "julien@example.com", "password", "Secret123!", "confirmPassword", "Secret123!"));
        params.putAll(overrides);
        var request = post("/signup").with(csrf());
        params.forEach(request::param);
        return request;
    }
}
