package com.guitarvault;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
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
class ProfileTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired ProfilePhotoRepository photos;
    @Autowired ProfileService profiles;
    @Autowired EntityManager entityManager;
    private MockMvc mvc;
    private User alice;
    private User bob;
    @MockitoBean JavaMailSender mail;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        alice = account("profile-alice");
        bob = account("profile-bob");
        alice.setFullName("Private real name");
        alice.setInstrument("Guitare");
        alice.setLevel("Intermédiaire");
        alice.setCity("Paris");
        alice.setCountry("France");
        alice.setBio("Existing bio");
        users.flush();
    }

    @Test
    void existingAccountsKeepSignupInformationAndHaveAnEditableProfile() throws Exception {
        mvc.perform(get("/profile").with(user(alice.getEmail())))
                .andExpect(redirectedUrl("/musicians/" + alice.getId()));
        mvc.perform(get("/profile/edit").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Existing bio")))
                .andExpect(content().string(containsString("value=\"Guitare\"")))
                .andExpect(content().string(containsString("value=\"Paris\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(get("/musicians/" + alice.getId()).with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("canEditProfile", false))
                .andExpect(content().string(containsString("Existing bio")))
                .andExpect(content().string(not(containsString(alice.getEmail()))))
                .andExpect(content().string(not(containsString("Private real name"))))
                .andExpect(content().string(not(containsString("unused-test-hash"))));
    }

    @Test
    void updatesOnlyCurrentUsersProfileAndNeverAccountPrivileges() throws Exception {
        String originalHash = alice.getPasswordHash();
        mvc.perform(post("/profile").with(user(alice.getEmail())).with(csrf())
                        .param("displayName", "  Alice Studio  ").param("instrument", " Guitare, batterie ")
                        .param("level", "Avancé").param("city", " Lyon ").param("country", " France ")
                        .param("bio", " My story ").param("activities", "MUSICIAN", "SOUND_ENGINEER")
                        .param("spotifyUrl", "https://open.spotify.com/artist/abc")
                        .param("deezerUrl", "https://www.deezer.com/fr/artist/123")
                        .param("websiteUrl", "https://example.org/alice")
                        .param("id", bob.getId().toString()).param("ownerId", bob.getId().toString())
                        .param("username", "hijacked").param("email", "changed@example.org")
                        .param("passwordHash", "hacked").param("roles", "ROLE_ADMIN").param("enabled", "false"))
                .andExpect(redirectedUrl("/musicians/" + alice.getId()))
                .andExpect(flash().attribute("success", "profile.saved"));
        users.flush();
        entityManager.clear();
        var view = profiles.view(alice.getId());
        assertThat(view.displayName()).isEqualTo("Alice Studio");
        assertThat(view.instrument()).isEqualTo("Guitare, batterie");
        assertThat(view.city()).isEqualTo("Lyon");
        assertThat(view.bio()).isEqualTo("My story");
        assertThat(view.activities()).containsExactly(ProfileActivity.MUSICIAN, ProfileActivity.SOUND_ENGINEER);
        User saved = users.findById(alice.getId()).orElseThrow();
        assertThat(saved.getUsername()).isEqualTo("profile-alice");
        assertThat(saved.getEmail()).isEqualTo(alice.getEmail());
        assertThat(saved.getPasswordHash()).isEqualTo(originalHash);
        assertThat(saved.getRoles()).containsExactly(Role.ROLE_USER);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(users.findById(bob.getId()).orElseThrow().getDisplayName()).isNull();
        mvc.perform(get("/dashboard").with(user(alice.getEmail())))
                .andExpect(content().string(containsString("Alice Studio")));
        mvc.perform(get("/musicians/" + alice.getId()).with(user(bob.getEmail())))
                .andExpect(content().string(containsString("Ingénieur du son")))
                .andExpect(content().string(containsString("https://open.spotify.com/artist/abc")))
                .andExpect(content().string(containsString("https://www.deezer.com/fr/artist/123")));
    }

    @Test
    void rendersEscapedProfileAndEnglishLabels() throws Exception {
        mvc.perform(post("/profile").with(user(alice.getEmail())).with(csrf())
                        .param("displayName", "<script>name</script>").param("bio", "<img src=x onerror=alert(1)>")
                        .param("instrument", "Guitar & bass").param("activities", "SOUND_ENGINEER"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/musicians/" + alice.getId()).with(user(bob.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("&lt;script&gt;name&lt;/script&gt;")))
                .andExpect(content().string(containsString("&lt;img src=x onerror=alert(1)&gt;")))
                .andExpect(content().string(containsString("Sound engineer")))
                .andExpect(content().string(containsString("Guitar &amp; bass")));
        mvc.perform(get("/profile/edit").with(user(alice.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Edit my profile")))
                .andExpect(content().string(not(containsString("??"))));
    }

    @Test
    void addsReplacesAndRemovesPhotoUsingValidatedImageType() throws Exception {
        byte[] first = image(2, 2, "png");
        byte[] second = image(3, 3, "jpg");
        String photoPath = "/musicians/" + alice.getId() + "/photo";
        for (byte[] bytes : List.of(first, second)) {
            mvc.perform(multipart("/profile").file(new MockMultipartFile("photo", "avatar.bin", "text/plain", bytes))
                            .with(user(alice.getEmail())).with(csrf()).param("displayName", "Alice"))
                    .andExpect(status().is3xxRedirection());
            photos.flush();
            entityManager.clear();
            mvc.perform(get(photoPath).with(user(bob.getEmail())))
                    .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                    .andExpect(content().contentType(bytes == first ? "image/png" : "image/jpeg"))
                    .andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
            assertThat(photos.count()).isEqualTo(1);
        }
        assertThat(profiles.view(alice.getId()).hasPhoto()).isTrue();
        mvc.perform(get("/musicians/" + alice.getId()).with(user(bob.getEmail())))
                .andExpect(content().string(containsString(photoPath)));
        mvc.perform(post("/profile").with(user(alice.getEmail())).with(csrf()).param("removePhoto", "true"))
                .andExpect(status().is3xxRedirection());
        photos.flush();
        assertThat(profiles.view(alice.getId()).hasPhoto()).isFalse();
        mvc.perform(get(photoPath).with(user(bob.getEmail()))).andExpect(status().isNotFound());
    }

    @Test
    void invalidPhotoDoesNotChangeExistingProfileOrPhotoAndKeepsFormValues() throws Exception {
        byte[] valid = image(2, 2, "png");
        photos.saveAndFlush(new ProfilePhoto(alice, "image/png", valid));
        for (byte[] bytes : List.of("<svg>fake</svg>".getBytes(), new byte[5 * 1024 * 1024 + 1], image(4097, 1, "png"))) {
            mvc.perform(multipart("/profile").file(new MockMultipartFile("photo", "fake.png", "image/png", bytes))
                            .with(user(alice.getEmail())).with(csrf()).param("displayName", "Keep my text")
                            .param("bio", "New bio").param("activities", "MUSICIAN", "SOUND_ENGINEER"))
                    .andExpect(status().isOk()).andExpect(model().attribute("error", "profile.photoError"))
                    .andExpect(model().attribute("selectedActivities", contains("MUSICIAN", "SOUND_ENGINEER")))
                    .andExpect(content().string(containsString("Keep my text")))
                    .andExpect(content().string(containsString("New bio")));
        }
        assertThat(alice.getBio()).isEqualTo("Existing bio");
        assertThat(photos.findByOwnerId(alice.getId()).orElseThrow().getData()).isEqualTo(valid);
        mvc.perform(multipart("/profile").file(new MockMultipartFile("photo", "valid.png", "image/png", valid))
                        .with(user(alice.getEmail())).with(csrf()).param("removePhoto", "true"))
                .andExpect(model().attribute("error", "profile.photoConflict"));
        assertThat(photos.count()).isEqualTo(1);
    }

    @Test
    void validatesTextLevelAndActivitiesBeforeUpdating() throws Exception {
        for (String field : List.of("displayName", "city", "country", "bio", "instrument")) {
            int limit = field.equals("bio") ? 1000 : field.equals("instrument") ? 255 : 100;
            mvc.perform(post("/profile").with(user(alice.getEmail())).with(csrf()).param(field, "x".repeat(limit + 1)))
                    .andExpect(status().isOk()).andExpect(model().attribute("error", "profile.fieldsError"));
        }
        mvc.perform(post("/profile").with(user(alice.getEmail())).with(csrf()).param("level", "Expert"))
                .andExpect(model().attribute("error", "profile.levelError"));
        mvc.perform(post("/profile").with(user(alice.getEmail())).with(csrf()).param("activities", "MUSICIAN", "ROLE_ADMIN"))
                .andExpect(model().attribute("error", "profile.activityError"));
        assertThat(alice.getBio()).isEqualTo("Existing bio");
        assertThat(alice.getActivities()).isEmpty();
        assertThat(alice.getRoles()).containsExactly(Role.ROLE_USER);
    }

    @Test
    void rejectsUnsafeAndWrongPlatformLinks() throws Exception {
        for (String field : List.of("spotifyUrl", "deezerUrl", "websiteUrl")) {
            for (String value : List.of("javascript:alert(1)", "http://example.org", "https://user:pass@example.org",
                    "https://example.org:444", "//example.org", "https://example.org/" + "x".repeat(2000))) {
                mvc.perform(post("/profile").with(user(alice.getEmail())).with(csrf()).param(field, value))
                        .andExpect(status().isOk()).andExpect(model().attribute("error", "profile.linksError"));
            }
        }
        for (String field : List.of("spotifyUrl", "deezerUrl")) {
            mvc.perform(post("/profile").with(user(alice.getEmail())).with(csrf())
                            .param(field, "https://open.spotify.com.evil.example/artist/1"))
                    .andExpect(model().attribute("error", "profile.linksError"));
        }
        assertThat(alice.getBio()).isEqualTo("Existing bio");
    }

    @Test
    void clearingOptionalProfileFieldsKeepsAccountIdentity() throws Exception {
        alice.setDisplayName("Alice");
        alice.setActivities(Set.of(ProfileActivity.SOUND_ENGINEER));
        mvc.perform(post("/profile").with(user(alice.getEmail())).with(csrf()))
                .andExpect(redirectedUrl("/musicians/" + alice.getId()));
        assertThat(alice.getActivities()).isEmpty();
        users.flush();
        entityManager.clear();
        var profile = profiles.view(alice.getId());
        assertThat(profile.displayName()).isEqualTo("profile-alice");
        assertThat(profile.bio()).isEmpty();
        assertThat(profile.instrument()).isEmpty();
        assertThat(profile.activities()).isEmpty();
        assertThat(users.findById(alice.getId()).orElseThrow().getFullName()).isEqualTo("Private real name");
    }

    @Test
    void activityChangesPersistAcrossRepeatedProfileEdits() throws Exception {
        for (List<String> selection : List.of(List.of("MUSICIAN", "SOUND_ENGINEER"), List.of("SOUND_ENGINEER"), List.<String>of())) {
            var request = post("/profile").with(user(alice.getEmail())).with(csrf());
            if (!selection.isEmpty()) request.param("activities", selection.toArray(String[]::new));
            mvc.perform(request).andExpect(status().is3xxRedirection());
            users.flush();
            entityManager.clear();
            assertThat(profiles.view(alice.getId()).activities().stream().map(Enum::name).toList()).containsExactlyElementsOf(selection);
        }
    }

    @Test
    void requiresAuthenticationCsrfAndHidesDisabledAndMissingProfiles() throws Exception {
        for (String path : List.of("/profile", "/profile/edit", "/musicians/" + alice.getId(), "/musicians/" + alice.getId() + "/photo")) {
            mvc.perform(get(path)).andExpect(redirectedUrl("/login"));
        }
        mvc.perform(post("/profile").with(csrf())).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/profile").with(user(alice.getEmail()))).andExpect(status().isForbidden());
        alice.setEnabled(false);
        for (Long id : List.of(alice.getId(), Long.MAX_VALUE)) {
            mvc.perform(get("/musicians/" + id).with(user(bob.getEmail()))).andExpect(status().isNotFound());
            mvc.perform(get("/musicians/" + id + "/photo").with(user(bob.getEmail()))).andExpect(status().isNotFound());
        }
        mvc.perform(post("/profile").with(user(alice.getEmail())).with(csrf()).param("displayName", "Disabled"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/musicians/" + bob.getId()).with(user(bob.getEmail())).with(csrf()))
                .andExpect(status().isMethodNotAllowed());
    }

    private static byte[] image(int width, int height, String format) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, output);
        return output.toByteArray();
    }

    private User account(String name) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@example.com");
        user.setPasswordHash("unused-test-hash");
        user.setEnabled(true);
        return users.saveAndFlush(user);
    }
}
