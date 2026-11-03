package com.guitarvault;

import java.util.List;
import org.springframework.mock.web.MockMultipartFile;
import jakarta.persistence.EntityManager;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
class DashboardTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired InstrumentRepository instruments;
    @MockitoBean JavaMailSender mail;
    @Autowired EntityManager entityManager;
    private MockMvc mvc;
    private User alice;
    private User bob;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        alice = account("alice");
        bob = account("bob");
    }

    @Test
    void newUserSeesEmptyCollectionAndZeroCounters() throws Exception {
        var result = mvc.perform(get("/dashboard").with(user(alice.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alice")))
                .andExpect(content().string(containsString("Votre collection commence ici")))
                .andExpect(content().string(containsString("/instruments/new")))
                .andExpect(model().attribute("instruments", hasSize(0))).andReturn();
        List<?> stats = (List<?>) result.getModelAndView().getModel().get("stats");
        for (Object stat : stats) {
            assertThat(((Number) ((Map<?, ?>) stat).get("value")).longValue()).isZero();
        }
    }

    @Test
    void dashboardAndCollectionShowOnlyCurrentUsersInstrumentsAndRealCounts() throws Exception {
        for (String status : List.of("Actif", "À réparer", "En vente", "Actif", "Actif", "Actif")) {
            instruments.saveAndFlush(new Instrument("Alice instrument " + instruments.count(), "Fender", 2020, alice, status));
        }
        instruments.saveAndFlush(new Instrument("Bob private guitar", "Gibson", 1970, bob, "En vente"));
        var result = mvc.perform(get("/dashboard").with(user(alice.getEmail())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("instruments", hasSize(5)))
                .andExpect(content().string(not(containsString("Bob private guitar"))))
                .andExpect(content().string(not(containsString("Alice instrument 0")))).andReturn();
        List<?> stats = (List<?>) result.getModelAndView().getModel().get("stats");
        assertThat(stats.stream().map(stat -> ((Number) ((Map<?, ?>) stat).get("value")).longValue()).toList())
                .containsExactly(6L, 4L, 1L, 1L);
        mvc.perform(get("/instruments").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("instruments", hasSize(6)))
                .andExpect(content().string(not(containsString("Bob private guitar"))));
        mvc.perform(get("/instruments").with(user(bob.getEmail())))
                .andExpect(model().attribute("instruments", hasSize(1)))
                .andExpect(content().string(containsString("Bob private guitar")))
                .andExpect(content().string(not(containsString("Alice instrument"))));
    }

    @Test
    void addingInstrumentUsesSessionOwnerAndDisplaysItOnDashboard() throws Exception {
        mvc.perform(get("/instruments/new").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("name=\"_csrf\"")));
        var result = mvc.perform(post("/instruments").with(user(alice.getEmail())).with(csrf())
                        .param("name", "  Stratocaster ").param("brand", " Fender ").param("year", "1987")
                        .param("status", "À réparer").param("owner", bob.getId().toString())
                        .param("ownerId", bob.getId().toString()))
                .andExpect(redirectedUrl("/dashboard")).andExpect(flash().attributeExists("success")).andReturn();
        List<Instrument> saved = instruments.findByOwnerIdOrderByIdDesc(alice.getId());
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getName()).isEqualTo("Stratocaster");
        assertThat(saved.getFirst().getBrand()).isEqualTo("Fender");
        assertThat(saved.getFirst().getYear()).isEqualTo(1987);
        assertThat(instruments.findByOwnerIdOrderByIdDesc(bob.getId())).isEmpty();
        mvc.perform(get("/dashboard").with(user(alice.getEmail())).flashAttrs(result.getFlashMap()))
                .andExpect(content().string(containsString("Stratocaster")))
                .andExpect(content().string(containsString("Votre instrument a bien été ajouté")));
    }

    @Test
    void yearIsOptionalAndOutputEscapesUserInput() throws Exception {
        mvc.perform(post("/instruments").with(user(alice.getEmail())).with(csrf())
                        .param("name", "<script>test</script>").param("brand", "Fender").param("status", "Actif"))
                .andExpect(redirectedUrl("/dashboard"));
        assertThat(instruments.findByOwnerIdOrderByIdDesc(alice.getId()).getFirst().getYear()).isNull();
        mvc.perform(get("/dashboard").with(user(alice.getEmail())))
                .andExpect(content().string(containsString("&lt;script&gt;test&lt;/script&gt;")))
                .andExpect(content().string(containsString("Non renseignée")));
    }

    @ParameterizedTest
    @CsvSource({"name, ' '", "brand, ' '", "year, invalid", "year, 9999", "year, 0", "status, Invalid"})
    void rejectsInvalidInstrumentAndPreservesForm(String field, String value) throws Exception {
        var request = post("/instruments").with(user(alice.getEmail())).with(csrf());
        var form = new java.util.HashMap<>(Map.of("name", "Stratocaster", "brand", "Fender", "year", "1987", "status", "Actif"));
        form.put(field, value);
        form.forEach(request::param);
        mvc.perform(request).andExpect(status().isOk()).andExpect(model().attributeExists("error", "form"))
                .andExpect(content().string(containsString("role=\"alert\"")));
        assertThat(instruments.findByOwnerIdOrderByIdDesc(alice.getId())).isEmpty();
    }

    @Test
    void requiresAuthenticationAndCsrf() throws Exception {
        for (String path : List.of("/dashboard", "/instruments", "/instruments/new")) {
            mvc.perform(get(path)).andExpect(redirectedUrl("/login"));
        }
        mvc.perform(post("/instruments").with(csrf())).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/instruments").with(user(alice.getEmail()))).andExpect(status().isForbidden());
    }

    @Test
    void savesPhotosStoryAndOrderedListsAndShowsThemOnlyToTheirOwner() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        byte[] png = output.toByteArray();
        mvc.perform(multipart("/instruments")
                        .file(new MockMultipartFile("photos", "guitar.png", "image/png", png))
                        .file(new MockMultipartFile("photos", "detail.png", "image/png", png))
                        .with(user(alice.getEmail())).with(csrf())
                        .param("name", "Studio guitar").param("brand", "Fender").param("status", "Actif")
                        .param("history", "  First guitar\n<script>history</script>  ")
                        .param("notes", "Changed pickups")
                        .param("serialNumber", "  001-SN-1987 ").param("previousOwner", " Alex <Martin> ")
                        .param("recordings", " First track, live version ", "", "Second track")
                        .param("concerts", "Paris, 2024", "Lyon, 2025"))
                .andExpect(redirectedUrl("/dashboard"));
        instruments.flush();
        entityManager.clear();
        var saved = instruments.findByOwnerIdOrderByIdDesc(alice.getId()).getFirst();
        assertThat(saved.getHistory()).isEqualTo("First guitar\n<script>history</script>");
        assertThat(saved.getNotes()).isEqualTo("Changed pickups");
        assertThat(saved.getSerialNumber()).isEqualTo("001-SN-1987");
        assertThat(saved.getPreviousOwner()).isEqualTo("Alex <Martin>");
        assertThat(saved.getRecordings()).containsExactly("First track, live version", "Second track");
        assertThat(saved.getConcerts()).containsExactly("Paris, 2024", "Lyon, 2025");
        assertThat(saved.getPhotos()).hasSize(2);
        String path = "/instruments/" + saved.getId();
        String photoPath = path + "/photos/" + saved.getPhotos().getFirst().getId();
        mvc.perform(get(path).with(user(alice.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;script&gt;history&lt;/script&gt;")))
                .andExpect(content().string(containsString("001-SN-1987")))
                .andExpect(content().string(containsString("Alex &lt;Martin&gt;")))
                .andExpect(content().string(containsString("First track, live version")))
                .andExpect(content().string(containsString("Lyon, 2025")))
                .andExpect(content().string(containsString(photoPath)));
        mvc.perform(get(photoPath).with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(png)).andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(get(path).with(user(bob.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get(photoPath).with(user(bob.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get(photoPath)).andExpect(redirectedUrl("/login"));
        mvc.perform(get(path + "/photos/999999").with(user(alice.getEmail()))).andExpect(status().isNotFound());
    }

    @Test
    void singleListEntryPreservesCommasAndBlankEntriesAreIgnored() throws Exception {
        mvc.perform(post("/instruments").with(user(alice.getEmail())).with(csrf())
                        .param("name", "Guitar").param("brand", "Fender").param("status", "Actif")
                        .param("recordings", "Song, artist, album").param("concerts", "  "))
                .andExpect(redirectedUrl("/dashboard"));
        var saved = instruments.findByOwnerIdOrderByIdDesc(alice.getId()).getFirst();
        assertThat(saved.getRecordings()).containsExactly("Song, artist, album");
        assertThat(saved.getConcerts()).isEmpty();
    }

    @Test
    void rejectsFakeImagesAndPreservesStoryAndListsOnValidationError() throws Exception {
        mvc.perform(multipart("/instruments")
                        .file(new MockMultipartFile("photos", "fake.png", "image/png", "<svg>not a photo</svg>".getBytes()))
                        .with(user(alice.getEmail())).with(csrf())
                        .param("name", "Guitar").param("brand", "Fender").param("status", "Actif")
                        .param("history", "My story").param("notes", "My notes")
                        .param("serialNumber", "001-SN").param("previousOwner", "Alex Martin")
                        .param("recordings", "First track", "Second track").param("concerts", "Paris"))
                .andExpect(status().isOk()).andExpect(model().attribute("error", "instrument.photosError"))
                .andExpect(content().string(containsString("My story")))
                .andExpect(content().string(containsString("My notes")))
                .andExpect(content().string(containsString("001-SN")))
                .andExpect(content().string(containsString("Alex Martin")))
                .andExpect(content().string(containsString("Second track")))
                .andExpect(content().string(containsString("Paris")));
        assertThat(instruments.findByOwnerIdOrderByIdDesc(alice.getId())).isEmpty();
    }

    @Test
    void enforcesStoryListAndPhotoLimitsWithoutSavingPartialInstruments() throws Exception {
        for (String field : List.of("history", "notes", "recordings", "concerts", "serialNumber", "previousOwner")) {
            int max = switch (field) {
                case "history" -> 10000;
                case "notes" -> 5000;
                case "serialNumber" -> 100;
                case "previousOwner" -> 200;
                default -> 500;
            };
            mvc.perform(post("/instruments").with(user(alice.getEmail())).with(csrf())
                            .param("name", "Guitar").param("brand", "Fender").param("status", "Actif")
                            .param(field, "x".repeat(max + 1)))
                    .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
        }
        mvc.perform(post("/instruments").with(user(alice.getEmail())).with(csrf())
                        .param("name", "Guitar").param("brand", "Fender").param("status", "Actif")
                        .param("concerts", java.util.Collections.nCopies(51, "Concert").toArray(String[]::new)))
                .andExpect(model().attribute("error", "instrument.entriesError"));
        mvc.perform(multipart("/instruments")
                        .file(new MockMultipartFile("photos", "large.png", "image/png", new byte[5 * 1024 * 1024 + 1]))
                        .with(user(alice.getEmail())).with(csrf())
                        .param("name", "Guitar").param("brand", "Fender").param("status", "Actif"))
                .andExpect(model().attribute("error", "instrument.photosError"));
        var upload = multipart("/instruments");
        for (int index = 0; index < 6; index++) {
            upload.file(new MockMultipartFile("photos", "photo.png", "image/png", new byte[]{1}));
        }
        mvc.perform(upload.with(user(alice.getEmail())).with(csrf())
                        .param("name", "Guitar").param("brand", "Fender").param("status", "Actif"))
                .andExpect(model().attribute("error", "instrument.photosError"));
        assertThat(instruments.findByOwnerIdOrderByIdDesc(alice.getId())).isEmpty();
    }

    private User account(String name) {
        User account = new User();
        account.setUsername(name);
        account.setFullName(name.equals("alice") ? "Alice" : "Bob");
        account.setEmail(name + "@example.com");
        account.setPasswordHash("unused-test-hash");
        account.setEnabled(true);
        return users.saveAndFlush(account);
    }
}
