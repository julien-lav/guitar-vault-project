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
class BandTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired BandRepository bands;
    @Autowired BandService service;
    @Autowired EntityManager entityManager;
    @MockitoBean JavaMailSender mail;
    private MockMvc mvc;
    private User alice;
    private User bob;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        alice = account("band-alice", true);
        bob = account("band-bob", true);
    }

    @Test
    void rendersEmptyListAndLocalizedCreationForm() throws Exception {
        mvc.perform(get("/bands").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Créer un groupe")))
                .andExpect(model().attribute("myBands", hasSize(0)));
        mvc.perform(get("/bands/new").param("lang", "en").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Create a band")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("??"))));
    }

    @Test
    void savesBandWithMultipleMusiciansAndLinksAndShowsBothDirections() throws Exception {
        mvc.perform(post("/bands").with(user(alice.getEmail())).with(csrf())
                        .param("name", "  The Vaults ").param("genre", " Rock ").param("city", " Paris ")
                        .param("description", "Live <script>alert(1)</script>")
                        .param("spotifyUrl", "https://open.spotify.com/artist/example")
                        .param("deezerUrl", "https://www.deezer.com/fr/artist/123")
                        .param("websiteUrl", "https://example.org/band")
                        .param("members", "band-bob\nband-alice, band-bob")
                        .param("creatorId", bob.getId().toString()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("success", "band.saved"));
        bands.flush();
        entityManager.clear();
        Band band = bands.findAllByOrderByIdDesc().getFirst();
        assertThat(band.getName()).isEqualTo("The Vaults");
        assertThat(band.getGenre()).isEqualTo("Rock");
        assertThat(band.getCity()).isEqualTo("Paris");
        assertThat(band.getCreator().getId()).isEqualTo(alice.getId());
        assertThat(band.getMembers()).extracting(User::getId).containsExactlyInAnyOrder(alice.getId(), bob.getId());
        mvc.perform(get("/bands/" + band.getId()).with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("canEditBand", true))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(content().string(containsString("https://open.spotify.com/artist/example")))
                .andExpect(content().string(containsString("https://www.deezer.com/fr/artist/123")))
                .andExpect(content().string(containsString("https://example.org/band")))
                .andExpect(content().string(containsString("/musicians/" + bob.getId())))
                .andExpect(content().string(not(containsString(bob.getEmail()))));
        mvc.perform(get("/musicians/" + bob.getId()).with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("The Vaults")))
                .andExpect(content().string(not(containsString(bob.getEmail()))));
        mvc.perform(get("/bands").with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("myBands", hasSize(1)));
    }

    @Test
    void musicianCanBelongToSeveralBandsAndEditingUpdatesMembership() throws Exception {
        Band first = create("First band", "band-bob");
        create("Second band", "band-bob");
        assertThat(bands.findByMembersIdOrderByNameAsc(bob.getId())).hasSize(2);
        mvc.perform(get("/bands/" + first.getId() + "/edit").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("band-bob")));
        mvc.perform(post("/bands/" + first.getId()).with(user(alice.getEmail())).with(csrf())
                        .param("name", "Renamed band").param("members", ""))
                .andExpect(redirectedUrl("/bands/" + first.getId()));
        bands.flush();
        entityManager.clear();
        Band updated = bands.findById(first.getId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("Renamed band");
        assertThat(updated.getMembers()).extracting(User::getId).containsExactly(alice.getId());
        assertThat(bands.findByMembersIdOrderByNameAsc(bob.getId())).hasSize(1);
        assertThat(bands.findByMembersIdOrderByNameAsc(alice.getId())).hasSize(2);
    }

    @Test
    void deduplicatesCreatorByAccountIdWhenAccountIsDetached() {
        entityManager.clear();
        Band band = create("Solo project", "band-alice,band-alice");
        bands.flush();
        entityManager.clear();
        assertThat(service.find(band.getId()).getMembers()).extracting(User::getId).containsExactly(alice.getId());
    }

    @Test
    void onlyCreatorCanEditEvenWhenAnotherUserIsAMember() throws Exception {
        Band band = create("Protected band", "band-bob");
        String path = "/bands/" + band.getId();
        mvc.perform(get(path).with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("canEditBand", false));
        mvc.perform(get(path + "/edit").with(user(bob.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(post(path).with(user(bob.getEmail())).with(csrf()).param("name", "Hijacked"))
                .andExpect(status().isNotFound());
        assertThat(service.find(band.getId()).getName()).isEqualTo("Protected band");
    }

    @Test
    void rejectsUnsafeOrWrongPlatformLinksWithoutSaving() throws Exception {
        for (String field : List.of("spotifyUrl", "deezerUrl", "websiteUrl")) {
            for (String value : List.of("javascript:alert(1)", "http://example.org", "https://user:pass@example.org",
                    "https://example.org:444", "//example.org", "not a url", "https://example.org/" + "x".repeat(2000))) {
                invalid(field, value, "band.linksError");
            }
        }
        invalid("spotifyUrl", "https://open.spotify.com.evil.example/artist/1", "band.linksError");
        invalid("spotifyUrl", "https://www.deezer.com/artist/1", "band.linksError");
        invalid("deezerUrl", "https://open.spotify.com/artist/1", "band.linksError");
        assertThat(bands.count()).isZero();
    }

    @Test
    void rejectsInvalidMembersAndFieldsAndPreservesExistingBand() throws Exception {
        account("band-disabled", false);
        invalid("members", "unknown-musician", "band.membersError");
        invalid("members", "band-disabled", "band.membersError");
        invalid("members", "x".repeat(10001), "band.membersError");
        for (String field : List.of("name", "genre", "city", "description")) {
            invalid(field, "x".repeat(field.equals("description") ? 5001 : 101), "band.fieldsError");
        }
        invalid("name", " ", "band.fieldsError");
        assertThat(bands.count()).isZero();
        Band band = create("Unchanged", "band-bob");
        mvc.perform(post("/bands/" + band.getId()).with(user(alice.getEmail())).with(csrf())
                        .param("name", "Changed").param("members", "unknown-musician").param("description", "Keep this text"))
                .andExpect(status().isOk()).andExpect(model().attribute("error", "band.membersError"))
                .andExpect(content().string(containsString("Keep this text")));
        assertThat(service.find(band.getId()).getName()).isEqualTo("Unchanged");
        assertThat(band.getMembers()).hasSize(2);
    }

    @Test
    void enforcesMemberLimit() throws Exception {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < 50; i++) names.append(account("limit-musician-" + i, true).getUsername()).append('\n');
        invalid("members", names.toString(), "band.membersError");
        assertThat(bands.count()).isZero();
    }

    @Test
    void requiresAuthenticationAndCsrfAndReturns404ForMissingProfiles() throws Exception {
        Band band = create("Band", "");
        for (String path : List.of("/bands", "/bands/new", "/bands/" + band.getId(),
                "/bands/" + band.getId() + "/edit", "/musicians/" + bob.getId())) {
            mvc.perform(get(path)).andExpect(redirectedUrl("/login"));
        }
        for (String path : List.of("/bands", "/bands/" + band.getId())) {
            mvc.perform(post(path).with(csrf())).andExpect(redirectedUrl("/login"));
            mvc.perform(post(path).with(user(alice.getEmail()))).andExpect(status().isForbidden());
        }
        for (String path : List.of("/bands/9223372036854775807", "/musicians/9223372036854775807")) {
            mvc.perform(get(path).with(user(alice.getEmail()))).andExpect(status().isNotFound());
        }
    }

    private void invalid(String field, String value, String error) throws Exception {
        var request = post("/bands").with(user(alice.getEmail())).with(csrf());
        if (!field.equals("name")) request.param("name", "Test band");
        mvc.perform(request.param(field, value)).andExpect(status().isOk())
                .andExpect(model().attribute("error", error))
                .andExpect(content().string(containsString("role=\"alert\"")));
    }

    private Band create(String name, String members) {
        return service.save(alice, null, Map.of("name", name, "genre", "", "city", "", "description", "",
                "spotifyUrl", "", "deezerUrl", "", "websiteUrl", "", "members", members));
    }

    private User account(String name, boolean enabled) {
        User account = new User();
        account.setUsername(name);
        account.setEmail(name + "@example.com");
        account.setPasswordHash("unused-test-hash");
        account.setEnabled(enabled);
        return users.saveAndFlush(account);
    }
}
