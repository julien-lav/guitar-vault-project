package com.guitarvault;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@EnabledIfSystemProperty(named = "mailpit.integration", matches = "true")
class MailpitIntegrationTests {
    @Autowired RegistrationService registration;
    @Autowired PasswordResetService reset;

    @Test
    void realVerificationAndResetEmailsArriveInMailpit() throws Exception {
        String identifier = UUID.randomUUID().toString();
        User user = new User();
        user.setFullName("Mailpit delivery test (temporary account)");
        user.setUsername("mailpit-" + identifier);
        user.setEmail("mailpit-" + identifier + "@example.com");
        user.setPasswordHash("unused-test-hash");
        registration.register(user);
        assertThat(user.isEnabled()).isFalse();
        user.setVerificationRequestedAt(java.time.Instant.now().minusSeconds(61));
        registration.resend(user.getEmail());
        user.setEnabled(true);
        reset.request(user.getEmail());
        try (HttpClient client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:18025/api/v1/search?query=to%3A" + user.getEmail())).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(user.getEmail());
            var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body());
            assertThat(json.path("messages").size()).isEqualTo(3);
        }
    }
}
