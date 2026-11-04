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
class StudioTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired StudioRepository studios;
    @Autowired StudioService service;
    @Autowired EntityManager entityManager;
    @MockitoBean JavaMailSender mail;
    private MockMvc mvc;
    private User alice;
    private User bob;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        alice = account("studio-alice");
        bob = account("studio-bob");
    }

    @Test
    void rendersEmptyStateLocalizedFormAndDashboardEntry() throws Exception {
        mvc.perform(get("/studios").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("myStudios", hasSize(0)))
                .andExpect(content().string(containsString("Faites découvrir votre studio")));
        mvc.perform(get("/studios/new").param("lang", "en").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Add a studio")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/dashboard").with(user(alice.getEmail())))
                .andExpect(content().string(containsString("href=\"/studios\"")));
    }

    @Test
    void persistsStudioAndConnectsItToCreatorsProfile() throws Exception {
        mvc.perform(post("/studios").with(user(alice.getEmail())).with(csrf())
                        .param("name", "  Vault Studio  ").param("city", " Paris ").param("country", " France ")
                        .param("description", "Live <script>alert(1)</script>")
                        .param("services", "Enregistrement\nMixage").param("equipment", "Console, micros")
                        .param("websiteUrl", "https://example.org/studio")
                        .param("contactEmail", " studio-contact@example.org ")
                        .param("creatorId", bob.getId().toString()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("success", "studio.saved"));
        studios.flush();
        entityManager.clear();
        Studio studio = studios.findByCreatorIdOrderByNameAsc(alice.getId()).getFirst();
        assertThat(studio.getName()).isEqualTo("Vault Studio");
        assertThat(studio.getCity()).isEqualTo("Paris");
        assertThat(studio.getCountry()).isEqualTo("France");
        assertThat(studio.getServices()).isEqualTo("Enregistrement\nMixage");
        assertThat(studio.getEquipment()).isEqualTo("Console, micros");
        assertThat(studio.getContactEmail()).isEqualTo("studio-contact@example.org");
        assertThat(studios.findByCreatorIdOrderByNameAsc(bob.getId())).isEmpty();
        mvc.perform(get("/studios/" + studio.getId()).with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("canEditStudio", false))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(content().string(containsString("https://example.org/studio")))
                .andExpect(content().string(containsString("studio-contact@example.org")))
                .andExpect(content().string(containsString("/musicians/" + alice.getId())))
                .andExpect(content().string(not(containsString(alice.getEmail()))));
        mvc.perform(get("/musicians/" + alice.getId()).with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Vault Studio")))
                .andExpect(content().string(not(containsString(alice.getEmail()))));
    }

    @Test
    void usersCanCreateSeveralStudiosAndTheirListsRemainSeparate() throws Exception {
        create(alice, "Alice studio one");
        create(alice, "Alice studio two");
        create(bob, "Bob studio");
        mvc.perform(get("/studios").with(user(alice.getEmail())))
                .andExpect(model().attribute("myStudios", hasSize(2)))
                .andExpect(model().attribute("studios", hasSize(3)));
        mvc.perform(get("/studios").with(user(bob.getEmail())))
                .andExpect(model().attribute("myStudios", hasSize(1)));
        mvc.perform(get("/musicians/" + bob.getId()).with(user(alice.getEmail())))
                .andExpect(content().string(containsString("Bob studio")))
                .andExpect(content().string(not(containsString("Alice studio one"))));
    }

    @Test
    void onlyCreatorCanEditAndCanClearOptionalFields() throws Exception {
        Studio studio = create(alice, "Original studio");
        String path = "/studios/" + studio.getId();
        mvc.perform(get(path + "/edit").with(user(bob.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(post(path).with(user(bob.getEmail())).with(csrf()).param("name", "Hijacked"))
                .andExpect(status().isNotFound());
        assertThat(service.find(studio.getId()).getName()).isEqualTo("Original studio");
        mvc.perform(get(path + "/edit").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Original studio")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(post(path).with(user(alice.getEmail())).with(csrf()).param("name", "Updated studio"))
                .andExpect(redirectedUrl(path));
        studios.flush();
        entityManager.clear();
        Studio updated = service.find(studio.getId());
        assertThat(updated.getName()).isEqualTo("Updated studio");
        assertThat(updated.getDescription()).isEmpty();
        assertThat(updated.getContactEmail()).isEmpty();
        assertThat(updated.getCreator().getId()).isEqualTo(alice.getId());
    }

    @Test
    void onlyNameIsRequiredAndAccountEmailIsNotPublished() throws Exception {
        mvc.perform(post("/studios").with(user(alice.getEmail())).with(csrf()).param("name", "Minimal studio"))
                .andExpect(status().is3xxRedirection());
        Studio studio = studios.findByCreatorIdOrderByNameAsc(alice.getId()).getFirst();
        assertThat(studio.getContactEmail()).isEmpty();
        mvc.perform(get("/studios/" + studio.getId()).with(user(bob.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(alice.getEmail()))))
                .andExpect(content().string(containsString("Ce studio n’a pas encore ajouté de présentation")));
    }

    @Test
    void validatesFieldsLinksAndEmailAndPreservesExistingData() throws Exception {
        for (String field : List.of("name", "city", "country", "description", "services", "equipment")) {
            invalid(field, "x".repeat(List.of("name", "city", "country").contains(field) ? 101 : 5001));
        }
        invalid("name", " ");
        for (String url : List.of("javascript:alert(1)", "http://example.org", "//example.org", "not a url",
                "https://user:pass@example.org", "https://example.org:444", "https://example.org/" + "x".repeat(2000))) {
            invalid("websiteUrl", url);
        }
        for (String email : List.of("invalid", "a@example.org\r\nBcc:x@example.org", "a@b?subject=hello.org", "a".repeat(255) + "@example.org")) {
            invalid("contactEmail", email);
        }
        assertThat(studios.count()).isZero();
        Studio studio = create(alice, "Original");
        mvc.perform(post("/studios/" + studio.getId()).with(user(alice.getEmail())).with(csrf())
                        .param("name", "Changed").param("description", "Keep this text").param("websiteUrl", "javascript:alert(1)"))
                .andExpect(status().isOk()).andExpect(model().attribute("error", "studio.linkError"))
                .andExpect(content().string(containsString("Keep this text")));
        assertThat(service.find(studio.getId()).getName()).isEqualTo("Original");
        assertThat(studio.getDescription()).isEqualTo("Studio description");
    }

    @Test
    void savesDisplaysEditsAndClearsAnExactHourlyRate() throws Exception {
        mvc.perform(post("/studios").with(user(alice.getEmail())).with(csrf())
                        .param("name", "Priced studio").param("hourlyRate", " 49,95 "))
                .andExpect(status().is3xxRedirection());
        studios.flush();
        entityManager.clear();
        Studio studio = studios.findByCreatorIdOrderByNameAsc(alice.getId()).getFirst();
        assertThat(studio.getHourlyRate()).isEqualByComparingTo("49.95");
        String path = "/studios/" + studio.getId();
        mvc.perform(get(path).with(user(bob.getEmail())))
                .andExpect(content().string(containsString("49,95 €/h")));
        mvc.perform(get(path + "/edit").with(user(alice.getEmail())))
                .andExpect(content().string(containsString("value=\"49.95\"")));
        mvc.perform(post(path).with(user(alice.getEmail())).with(csrf())
                        .param("name", "Priced studio").param("hourlyRate", "0"))
                .andExpect(redirectedUrl(path));
        assertThat(service.find(studio.getId()).getHourlyRate()).isEqualByComparingTo("0");
        mvc.perform(post(path).with(user(alice.getEmail())).with(csrf()).param("name", "Priced studio"))
                .andExpect(redirectedUrl(path));
        assertThat(service.find(studio.getId()).getHourlyRate()).isNull();
    }

    @Test
    void combinesCaseInsensitiveLocationAndInclusivePriceBoundsInDatabase() throws Exception {
        Studio match = pricedStudio("Paris match", "Paris", "France", "50.25");
        pricedStudio("Expensive", "Paris", "France", "100");
        pricedStudio("Other city", "Lyon", "France", "50.25");
        pricedStudio("Other country", "Paris", "USA", "50.25");
        pricedStudio("Unknown price", "Paris", "France", null);
        mvc.perform(get("/studios").with(user(bob.getEmail()))
                        .param("city", "  pAR  ").param("country", "frAN")
                        .param("minPrice", "50.25").param("maxPrice", "50.25"))
                .andExpect(status().isOk()).andExpect(model().attribute("studios", hasSize(1)))
                .andExpect(model().attribute("studios", contains(match)))
                .andExpect(model().attribute("filters", hasEntry("city", "pAR")))
                .andExpect(content().string(containsString("1 studio(s) trouvé(s)")));
        mvc.perform(get("/studios").with(user(bob.getEmail())))
                .andExpect(model().attribute("studios", hasSize(5)));
    }

    @Test
    void supportsIndividualFiltersAndExcludesUnknownPricesOnlyWhenPriceIsFiltered() throws Exception {
        pricedStudio("Free", "Paris", "France", "0");
        pricedStudio("Affordable", "Paris", "France", "49.95");
        pricedStudio("Expensive", "Lyon", "France", "100");
        pricedStudio("On request", "Paris", "Belgium", null);
        mvc.perform(get("/studios").with(user(bob.getEmail())).param("city", "Paris"))
                .andExpect(model().attribute("studios", hasSize(3)));
        mvc.perform(get("/studios").with(user(bob.getEmail())).param("country", "Belgium"))
                .andExpect(model().attribute("studios", hasSize(1)));
        mvc.perform(get("/studios").with(user(bob.getEmail())).param("maxPrice", "49,95"))
                .andExpect(model().attribute("studios", hasSize(2)));
        mvc.perform(get("/studios").with(user(bob.getEmail())).param("minPrice", "100"))
                .andExpect(model().attribute("studios", hasSize(1)));
        mvc.perform(get("/studios").with(user(bob.getEmail())).param("maxPrice", "0"))
                .andExpect(model().attribute("studios", hasSize(1)));
    }

    @Test
    void locationSearchTreatsSqlWildcardsLiterally() throws Exception {
        Studio match = pricedStudio("Literal city", "City_50%!", "France", null);
        pricedStudio("Other", "CityX50test!", "France", null);
        mvc.perform(get("/studios").with(user(bob.getEmail())).param("city", "_50%!"))
                .andExpect(status().isOk()).andExpect(model().attribute("studios", contains(match)));
    }

    @Test
    void showsNoResultsAndKeepsMyStudiosSeparateFromSearch() throws Exception {
        pricedStudio("My studio", "Paris", "France", "50");
        mvc.perform(get("/studios").with(user(alice.getEmail())).param("city", "Lyon"))
                .andExpect(status().isOk()).andExpect(model().attribute("studios", hasSize(0)))
                .andExpect(model().attribute("myStudios", hasSize(1)))
                .andExpect(content().string(containsString("Aucun studio ne correspond")))
                .andExpect(content().string(containsString("Effacer les filtres")))
                .andExpect(content().string(not(containsString("Faites découvrir votre studio"))));
    }

    @Test
    void rejectsInvalidSearchBoundsAndPreservesFilters() throws Exception {
        pricedStudio("Studio", "Paris", "France", "50");
        for (String field : List.of("minPrice", "maxPrice")) {
            for (String value : List.of("-1", "abc", "NaN", "1.234", "1000000", "1e2", "9".repeat(100))) {
                mvc.perform(get("/studios").with(user(bob.getEmail())).param(field, value).param("city", "Paris"))
                        .andExpect(status().isOk()).andExpect(model().attribute("searchError", "studio.searchPriceError"))
                        .andExpect(model().attribute("filters", hasEntry("city", "Paris")))
                        .andExpect(model().attribute("studios", hasSize(0)))
                        .andExpect(content().string(containsString("role=\"alert\"")));
            }
        }
        mvc.perform(get("/studios").with(user(bob.getEmail())).param("minPrice", "100").param("maxPrice", "50"))
                .andExpect(model().attribute("searchError", "studio.priceRangeError"));
        for (String field : List.of("city", "country")) {
            mvc.perform(get("/studios").with(user(bob.getEmail())).param(field, "x".repeat(101)))
                    .andExpect(model().attribute("searchError", "studio.locationError"));
        }
    }

    @Test
    void rejectsInvalidRatesWithoutChangingExistingStudio() throws Exception {
        Studio studio = pricedStudio("Original", "Paris", "France", "20.50");
        for (String value : List.of("-1", "abc", "NaN", "1.234", "1000000", "1e2")) {
            invalid("hourlyRate", value);
            mvc.perform(post("/studios/" + studio.getId()).with(user(alice.getEmail())).with(csrf())
                            .param("name", "Changed").param("hourlyRate", value))
                    .andExpect(status().isOk()).andExpect(model().attribute("error", "studio.rateError"));
        }
        assertThat(studios.count()).isEqualTo(1);
        assertThat(service.find(studio.getId()).getName()).isEqualTo("Original");
        assertThat(studio.getHourlyRate()).isEqualByComparingTo("20.50");
    }

    private Studio pricedStudio(String name, String city, String country, String rate) {
        Studio studio = create(alice, name);
        studio.setCity(city);
        studio.setCountry(country);
        studio.setHourlyRate(rate == null ? null : new java.math.BigDecimal(rate));
        return studios.saveAndFlush(studio);
    }

    @Test
    void requiresAuthenticationAndCsrfAndHandlesMissingStudios() throws Exception {
        Studio studio = create(alice, "Studio");
        for (String path : List.of("/studios", "/studios/new", "/studios/" + studio.getId(), "/studios/" + studio.getId() + "/edit")) {
            mvc.perform(get(path)).andExpect(redirectedUrl("/login"));
        }
        for (String path : List.of("/studios", "/studios/" + studio.getId())) {
            mvc.perform(post(path).with(csrf())).andExpect(redirectedUrl("/login"));
            mvc.perform(post(path).with(user(alice.getEmail()))).andExpect(status().isForbidden());
        }
        for (String path : List.of("/studios/9223372036854775807", "/studios/9223372036854775807/edit")) {
            mvc.perform(get(path).with(user(alice.getEmail()))).andExpect(status().isNotFound());
        }
        mvc.perform(post("/studios/9223372036854775807").with(user(alice.getEmail())).with(csrf()).param("name", "Unknown"))
                .andExpect(status().isNotFound());
    }

    private void invalid(String field, String value) throws Exception {
        var request = post("/studios").with(user(alice.getEmail())).with(csrf());
        if (!field.equals("name")) request.param("name", "Test studio");
        mvc.perform(request.param(field, value)).andExpect(status().isOk())
                .andExpect(model().attributeExists("error", "form"))
                .andExpect(content().string(containsString("role=\"alert\"")));
    }

    private Studio create(User actor, String name) {
        return service.save(actor, null, Map.of("name", name, "city", "", "country", "", "description", "Studio description",
                "services", "", "equipment", "", "websiteUrl", "", "contactEmail", ""));
    }

    private User account(String name) {
        User account = new User();
        account.setUsername(name);
        account.setEmail(name + "@example.com");
        account.setPasswordHash("unused-test-hash");
        account.setEnabled(true);
        return users.saveAndFlush(account);
    }
}
