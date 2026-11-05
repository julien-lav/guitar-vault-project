package com.guitarvault;

import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
class BandDiscoveryTests {
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
        alice = account("discovery-alice");
        bob = account("discovery-bob");
    }

    @Test
    void persistsMultipleGenresAndRegionAndRendersBadgesAcrossProfiles() throws Exception {
        mvc.perform(post("/bands").with(user(alice.getEmail())).with(csrf())
                        .param("name", "Crossroads").param("genres", " Rock, Funk, rock,  Indie   pop , , ")
                        .param("region", " Bretagne ").param("city", " Rennes "))
                .andExpect(status().is3xxRedirection());
        bands.flush();
        entityManager.clear();
        Band band = bands.findAllByOrderByIdDesc().getFirst();
        assertThat(band.getGenres()).containsExactlyInAnyOrder("Rock", "Funk", "Indie pop");
        assertThat(band.getRegion()).isEqualTo("Bretagne");
        assertThat(band.getCity()).isEqualTo("Rennes");
        for (String path : List.of("/bands", "/bands/" + band.getId(), "/musicians/" + alice.getId())) {
            mvc.perform(get(path).with(user(bob.getEmail())))
                    .andExpect(status().isOk()).andExpect(content().string(containsString("class=\"genre-tag\"")))
                    .andExpect(content().string(containsString("Indie pop")))
                    .andExpect(content().string(containsString("Bretagne")))
                    .andExpect(content().string(containsString("Rennes")));
        }
        mvc.perform(get("/bands/" + band.getId() + "/edit").with(user(alice.getEmail())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("form", hasEntry("region", "Bretagne")))
                .andExpect(content().string(containsString("name=\"genres\"")))
                .andExpect(content().string(containsString("value=\"Bretagne\"")));
        entityManager.clear();
        mvc.perform(post("/bands/" + band.getId()).with(user(alice.getEmail())).with(csrf())
                        .param("name", "Crossroads").param("genres", "Soul, Funk").param("region", "Normandie"))
                .andExpect(status().is3xxRedirection());
        bands.flush();
        entityManager.clear();
        Band updated = bands.findById(band.getId()).orElseThrow();
        assertThat(updated.getGenres()).containsExactlyInAnyOrder("Soul", "Funk");
        assertThat(updated.getRegion()).isEqualTo("Normandie");
        assertThat(updated.getMembers()).extracting(User::getId).containsExactly(alice.getId());
    }

    @Test
    void combinesExactGenreWithPartialRegionAndCityWithoutDuplicateResultsOrFilteringMembership() throws Exception {
        Band target = band(alice, "Matches", "Rock, Funk", "Bretagne", "Rennes");
        band(bob, "Other style", "Rockabilly", "Bretagne", "Rennes");
        band(bob, "Other region", "Rock", "Normandie", "Rennes");
        band(bob, "Other city", "Rock", "Bretagne", "Brest");
        band(alice, "Own jazz band", "Jazz", "Normandie", "Caen");
        mvc.perform(get("/bands").with(user(alice.getEmail()))
                        .param("genre", " rock ").param("region", " BRETAG ").param("city", " renn "))
                .andExpect(status().isOk()).andExpect(model().attribute("bands", contains(target)))
                .andExpect(model().attribute("myBands", hasSize(2)))
                .andExpect(model().attribute("filters", Map.of("genre", "rock", "region", "BRETAG", "city", "renn")))
                .andExpect(content().string(containsString("1 groupe(s) trouvé(s)")))
                .andExpect(content().string(containsString("value=\"rock\"")));
        mvc.perform(get("/bands").with(user(bob.getEmail())).param("genre", "FUNK"))
                .andExpect(model().attribute("bands", contains(target)));
        mvc.perform(get("/bands").with(user(bob.getEmail())))
                .andExpect(model().attribute("bands", hasSize(5)));
    }

    @Test
    void legacyGenreAndMissingOptionalFieldsRemainVisibleSearchableAndEditable() throws Exception {
        Band legacy = new Band(alice);
        legacy.setName("Legacy band");
        legacy.setGenre("Progressive rock");
        legacy.getMembers().add(alice);
        bands.saveAndFlush(legacy);
        entityManager.clear();
        mvc.perform(get("/bands").with(user(bob.getEmail())).param("genre", "progressive ROCK"))
                .andExpect(status().isOk()).andExpect(model().attribute("bands", hasSize(1)))
                .andExpect(model().attribute("genreOptions", hasItem("Progressive rock")))
                .andExpect(content().string(containsString("class=\"genre-tag\">Progressive rock")));
        mvc.perform(get("/bands/" + legacy.getId() + "/edit").with(user(alice.getEmail())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("form", hasEntry("genres", "Progressive rock")));
        mvc.perform(post("/bands/" + legacy.getId()).with(user(alice.getEmail())).with(csrf())
                        .param("name", "Legacy band").param("genres", "Progressive rock, Jazz").param("region", "Wallonie"))
                .andExpect(status().is3xxRedirection());
        bands.flush();
        entityManager.clear();
        Band saved = bands.findById(legacy.getId()).orElseThrow();
        assertThat(saved.getGenres()).containsExactlyInAnyOrder("Progressive rock", "Jazz");
        assertThat(saved.getRegion()).isEqualTo("Wallonie");
        assertThat(saved.getMembers()).extracting(User::getId).containsExactly(alice.getId());
    }

    @Test
    void clearingGenresAndRegionDoesNotResurrectLegacyStyleOrChangeMembers() throws Exception {
        Band band = band(alice, "Band", "Rock, Jazz", "Bretagne", "Rennes");
        band.getMembers().add(bob);
        bands.flush();
        entityManager.clear();
        mvc.perform(post("/bands/" + band.getId()).with(user(alice.getEmail())).with(csrf())
                        .param("name", "Band").param("genres", " , , ").param("region", ""))
                .andExpect(status().is3xxRedirection());
        bands.flush();
        entityManager.clear();
        Band saved = bands.findById(band.getId()).orElseThrow();
        assertThat(saved.getDisplayGenres()).isEmpty();
        assertThat(saved.getGenre()).isEmpty();
        assertThat(saved.getRegion()).isEmpty();
        assertThat(saved.getMembers()).extracting(User::getId).containsExactlyInAnyOrder(alice.getId(), bob.getId());
        mvc.perform(get("/bands").with(user(alice.getEmail())).param("genre", "Rock"))
                .andExpect(model().attribute("bands", hasSize(0)));
    }

    @Test
    void invalidDiscoveryFieldsPreserveFormAndExistingProfile() throws Exception {
        Band band = band(alice, "Original", "Jazz", "Bretagne", "Rennes");
        for (var invalid : List.of(Map.entry("genres", "x".repeat(101)),
                Map.entry("genres", "Rock,Pop,Jazz,Funk,Metal,Soul,Blues,Punk,Folk"),
                Map.entry("genres", "x".repeat(808)), Map.entry("region", "x".repeat(101)))) {
            String error = invalid.getKey().equals("genres") ? "band.genresError" : "band.fieldsError";
            var request = post("/bands/" + band.getId()).with(user(alice.getEmail())).with(csrf())
                    .param("name", "Changed").param("description", "Keep my description");
            if (!invalid.getKey().equals("genres")) request.param("genres", "Rock, Funk");
            mvc.perform(request.param(invalid.getKey(), invalid.getValue()))
                    .andExpect(status().isOk()).andExpect(model().attribute("error", error))
                    .andExpect(model().attribute("form", hasEntry(invalid.getKey(), invalid.getValue())))
                    .andExpect(content().string(containsString("Keep my description")));
        }
        bands.flush();
        entityManager.clear();
        Band saved = bands.findById(band.getId()).orElseThrow();
        assertThat(saved.getName()).isEqualTo("Original");
        assertThat(saved.getGenres()).containsExactly("Jazz");
        assertThat(saved.getRegion()).isEqualTo("Bretagne");
    }

    @Test
    void genreAndLocationTextIsEscapedAndWildcardSearchCharactersAreLiteral() throws Exception {
        String markup = "<script>genre</script>";
        Band target = band(alice, "Special", markup, "100%_!", "Town_!%");
        band(bob, "Ordinary", "Jazz", "Elsewhere", "Somewhere");
        mvc.perform(get("/bands").with(user(bob.getEmail())).param("region", "%_!").param("city", "_!%"))
                .andExpect(model().attribute("bands", contains(target)))
                .andExpect(content().string(containsString("&lt;script&gt;genre&lt;/script&gt;")))
                .andExpect(content().string(not(containsString(markup))));
        mvc.perform(get("/bands").with(user(bob.getEmail())).param("genre", markup))
                .andExpect(model().attribute("bands", contains(target)));
        mvc.perform(get("/bands").with(user(bob.getEmail())).param("region", "<script>region</script>"))
                .andExpect(content().string(containsString("&lt;script&gt;region&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>region</script>"))));
    }

    @Test
    void showsLocalizedSearchEmptyStateAndRejectsOversizedFilters() throws Exception {
        band(alice, "Band", "Jazz", "Bretagne", "Rennes");
        mvc.perform(get("/bands").with(user(alice.getEmail())).param("genre", "Unknown").param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("No matching bands")))
                .andExpect(content().string(containsString("Clear filters")))
                .andExpect(content().string(not(containsString("??"))));
        for (String criterion : List.of("genre", "region", "city")) {
            mvc.perform(get("/bands").with(user(alice.getEmail())).param(criterion, "x".repeat(101)))
                    .andExpect(status().isOk()).andExpect(model().attribute("error", "band.searchError"))
                    .andExpect(model().attribute("bands", hasSize(0)));
        }
    }

    @Test
    void onlyCreatorMayChangeDiscoveryFieldsAndCsrfRemainsRequired() throws Exception {
        Band band = band(alice, "Protected", "Jazz", "Bretagne", "Rennes");
        band.getMembers().add(bob);
        bands.flush();
        mvc.perform(post("/bands/" + band.getId()).with(user(bob.getEmail())).with(csrf())
                        .param("name", "Protected").param("genres", "Metal").param("region", "Normandie"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/bands/" + band.getId()).with(user(alice.getEmail()))
                        .param("name", "Protected").param("genres", "Metal"))
                .andExpect(status().isForbidden());
        assertThat(band.getGenres()).containsExactly("Jazz");
        assertThat(band.getRegion()).isEqualTo("Bretagne");
    }

    private Band band(User creator, String name, String genres, String region, String city) {
        var form = new LinkedHashMap<>(Map.of("name", name, "genre", "", "genres", genres, "region", region,
                "city", city, "description", "", "spotifyUrl", "", "deezerUrl", "", "websiteUrl", ""));
        return service.save(creator, null, form);
    }

    private User account(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.org");
        user.setPasswordHash("unused-test-hash");
        user.setEnabled(true);
        return users.saveAndFlush(user);
    }
}
