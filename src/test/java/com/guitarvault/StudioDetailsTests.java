package com.guitarvault;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
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
class StudioDetailsTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired StudioRepository studios;
    @Autowired StudioPhotoRepository photos;
    @Autowired StudioService service;
    @Autowired EntityManager entityManager;
    @MockitoBean JavaMailSender mail;
    private MockMvc mvc;
    private User alice;
    private User bob;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        alice = account("details-alice");
        bob = account("details-bob");
    }

    @Test
    void persistsAndDisplaysCapacityServiceRatesNotesAndValidatedPhotos() throws Exception {
        byte[] png = image(2, 2);
        mvc.perform(multipart("/studios").file(upload(png)).file(upload(png))
                        .with(user(alice.getEmail())).with(csrf()).param("name", "Live room")
                        .param("capacity", " 8 ").param("hourlyRate", "50")
                        .param("mixingRate", " 200,50 ").param("masteringRate", "75.25").param("dayRate", "350")
                        .param("pricingNotes", "  8 hours <script>details</script> "))
                .andExpect(status().is3xxRedirection());
        studios.flush();
        photos.flush();
        entityManager.clear();
        Studio saved = studios.findByCreatorIdOrderByNameAsc(alice.getId()).getFirst();
        assertThat(saved.getCapacity()).isEqualTo(8);
        assertThat(saved.getMixingRate()).isEqualByComparingTo("200.50");
        assertThat(saved.getMasteringRate()).isEqualByComparingTo("75.25");
        assertThat(saved.getDayRate()).isEqualByComparingTo("350");
        assertThat(saved.getPricingNotes()).isEqualTo("8 hours <script>details</script>");
        var metadata = photos.findByStudioIdOrderByIdAsc(saved.getId());
        assertThat(metadata).hasSize(2);
        String path = "/studios/" + saved.getId();
        String photoPath = path + "/photos/" + metadata.getFirst().getId();
        mvc.perform(get(path).with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("photos", hasSize(2)))
                .andExpect(content().string(containsString("Jusqu’à 8 personne(s)")))
                .andExpect(content().string(containsString("200,50 €/titre")))
                .andExpect(content().string(containsString("75,25 €/titre")))
                .andExpect(content().string(containsString("350,00 €/jour")))
                .andExpect(content().string(containsString("&lt;script&gt;details&lt;/script&gt;")))
                .andExpect(content().string(containsString(photoPath)));
        mvc.perform(get(photoPath).with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(png)).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get(path + "/edit").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("value=\"200.50\"")))
                .andExpect(content().string(containsString("name=\"removePhotos\"")))
                .andExpect(content().string(containsString("multipart/form-data")));
    }

    @Test
    void editingKeepsPhotosUnlessSelectedAndSupportsReplacement() throws Exception {
        Studio studio = create(alice, "Studio");
        byte[] png = image(2, 2);
        StudioPhoto first = photos.saveAndFlush(new StudioPhoto(studio, "image/png", png));
        StudioPhoto second = photos.saveAndFlush(new StudioPhoto(studio, "image/png", png));
        String path = "/studios/" + studio.getId();
        mvc.perform(post(path).with(user(alice.getEmail())).with(csrf()).param("name", "Renamed"))
                .andExpect(redirectedUrl(path));
        assertThat(photos.findByStudioIdOrderByIdAsc(studio.getId())).hasSize(2);
        mvc.perform(multipart(path).file(upload(image(3, 3))).with(user(alice.getEmail())).with(csrf())
                        .param("name", "Updated").param("removePhotos", first.getId().toString()))
                .andExpect(redirectedUrl(path));
        photos.flush();
        entityManager.clear();
        var ids = photos.findByStudioIdOrderByIdAsc(studio.getId()).stream().map(StudioPhotoRepository.Summary::getId).toList();
        assertThat(ids).hasSize(2).contains(second.getId()).doesNotContain(first.getId());
        mvc.perform(get(path + "/photos/" + first.getId()).with(user(bob.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get(path + "/photos/" + second.getId()).with(user(bob.getEmail()))).andExpect(status().isOk());
    }

    @Test
    void limitsGalleryToFiveAndAllowsRemovalAndAdditionInOneSave() throws Exception {
        Studio studio = create(alice, "Studio");
        byte[] png = image(2, 2);
        for (int index = 0; index < 5; index++) photos.saveAndFlush(new StudioPhoto(studio, "image/png", png));
        String path = "/studios/" + studio.getId();
        mvc.perform(multipart(path).file(upload(png)).with(user(alice.getEmail())).with(csrf()).param("name", "Invalid change"))
                .andExpect(status().isOk()).andExpect(model().attribute("error", "studio.photosError"));
        assertThat(studio.getName()).isEqualTo("Studio");
        Long removeId = photos.findByStudioIdOrderByIdAsc(studio.getId()).getFirst().getId();
        mvc.perform(multipart(path).file(upload(png)).with(user(alice.getEmail())).with(csrf())
                        .param("name", "Replacement").param("removePhotos", removeId.toString()))
                .andExpect(redirectedUrl(path));
        photos.flush();
        assertThat(photos.findByStudioIdOrderByIdAsc(studio.getId())).hasSize(5);
        assertThat(photos.existsById(removeId)).isFalse();
        // A separate request with six files must not create a partial studio.
        var tooMany = multipart("/studios");
        for (int index = 0; index < 6; index++) tooMany.file(upload(png));
        mvc.perform(tooMany.with(user(alice.getEmail())).with(csrf()).param("name", "Too many"))
                .andExpect(model().attribute("error", "studio.photosError"));
        assertThat(studios.count()).isEqualTo(1);
    }

    @Test
    void invalidUploadsDoNotChangeTextRatesOrRemoveSavedPhotos() throws Exception {
        Studio studio = create(alice, "Original");
        StudioPhoto existing = photos.saveAndFlush(new StudioPhoto(studio, "image/png", image(2, 2)));
        for (byte[] bytes : List.of("<svg>fake</svg>".getBytes(), new byte[5 * 1024 * 1024 + 1], image(10001, 1))) {
            mvc.perform(multipart("/studios/" + studio.getId()).file(upload(bytes))
                            .with(user(alice.getEmail())).with(csrf()).param("name", "Keep my form")
                            .param("mixingRate", "100").param("pricingNotes", "My pricing details")
                            .param("removePhotos", existing.getId().toString()))
                    .andExpect(status().isOk()).andExpect(model().attribute("error", "studio.photosError"))
                    .andExpect(model().attribute("selectedPhotos", contains(existing.getId())))
                    .andExpect(content().string(containsString("Keep my form")))
                    .andExpect(content().string(containsString("My pricing details")));
        }
        assertThat(studio.getName()).isEqualTo("Original");
        assertThat(studio.getMixingRate()).isNull();
        assertThat(photos.existsById(existing.getId())).isTrue();
        assertThat(photos.count()).isEqualTo(1);
    }

    @Test
    void onlyCreatorCanManagePhotosAndPhotoIdsMustBelongToTheStudio() throws Exception {
        Studio own = create(alice, "Own");
        Studio other = create(bob, "Other");
        byte[] png = image(2, 2);
        StudioPhoto photo = photos.saveAndFlush(new StudioPhoto(other, "image/png", png));
        mvc.perform(multipart("/studios/" + other.getId()).file(upload(png)).with(user(alice.getEmail())).with(csrf())
                        .param("name", "Hijacked"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/studios/" + own.getId()).with(user(alice.getEmail())).with(csrf())
                        .param("name", "Changed").param("removePhotos", photo.getId().toString()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/studios/" + own.getId() + "/photos/" + photo.getId()).with(user(alice.getEmail())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/studios/" + other.getId() + "/photos/" + photo.getId())).andExpect(redirectedUrl("/login"));
        mvc.perform(multipart("/studios/" + own.getId()).file(upload(png)).with(user(alice.getEmail())).param("name", "No CSRF"))
                .andExpect(status().isForbidden());
        assertThat(photos.count()).isEqualTo(1);
        assertThat(own.getName()).isEqualTo("Own");
    }

    @Test
    void validatesCapacityPricesAndNotesWithoutPartialCreation() throws Exception {
        for (String capacity : List.of("0", "-1", "1.5", "1001", "999999999999", "abc")) {
            invalid("capacity", capacity, "studio.capacityError");
        }
        for (String field : List.of("mixingRate", "masteringRate", "dayRate")) {
            for (String value : List.of("-1", "1.234", "1e2", "NaN", "1000000")) invalid(field, value, "studio.serviceRateError");
        }
        invalid("pricingNotes", "x".repeat(1001), "studio.pricingNotesError");
        assertThat(studios.count()).isZero();
        assertThat(photos.count()).isZero();
    }

    @Test
    void supportsBoundsAndClearingNewOptionalFields() throws Exception {
        mvc.perform(post("/studios").with(user(alice.getEmail())).with(csrf()).param("name", "Bounds")
                        .param("capacity", "1000").param("mixingRate", "0").param("masteringRate", "999999.99")
                        .param("dayRate", "0").param("pricingNotes", "Details"))
                .andExpect(status().is3xxRedirection());
        Studio studio = studios.findByCreatorIdOrderByNameAsc(alice.getId()).getFirst();
        assertThat(studio.getCapacity()).isEqualTo(1000);
        assertThat(studio.getMixingRate()).isEqualByComparingTo("0");
        assertThat(studio.getMasteringRate()).isEqualByComparingTo("999999.99");
        mvc.perform(post("/studios/" + studio.getId()).with(user(alice.getEmail())).with(csrf()).param("name", "Cleared"))
                .andExpect(status().is3xxRedirection());
        studios.flush();
        entityManager.clear();
        Studio saved = service.find(studio.getId());
        assertThat(saved.getCapacity()).isNull();
        assertThat(saved.getMixingRate()).isNull();
        assertThat(saved.getMasteringRate()).isNull();
        assertThat(saved.getDayRate()).isNull();
        assertThat(saved.getPricingNotes()).isEmpty();
    }

    @Test
    void newPricesDoNotChangeHourlySearchAndLegacyStudiosStillRender() throws Exception {
        Studio cheapHour = create(alice, "Hourly match");
        cheapHour.setHourlyRate(new java.math.BigDecimal("50"));
        cheapHour.setDayRate(new java.math.BigDecimal("500"));
        Studio expensiveHour = create(alice, "Hourly exclusion");
        expensiveHour.setHourlyRate(new java.math.BigDecimal("100"));
        expensiveHour.setMixingRate(new java.math.BigDecimal("20"));
        Studio legacy = create(alice, "Legacy");
        studios.flush();
        mvc.perform(get("/studios").with(user(bob.getEmail())).param("maxPrice", "60"))
                .andExpect(model().attribute("studios", contains(cheapHour)));
        mvc.perform(get("/studios/" + legacy.getId()).with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("photos", hasSize(0)));
        mvc.perform(get("/studios/new").with(user(alice.getEmail())).param("lang", "en"))
                .andExpect(content().string(containsString("Mixing (€/track)")))
                .andExpect(content().string(not(containsString("??"))));
    }

    private void invalid(String field, String value, String error) throws Exception {
        mvc.perform(post("/studios").with(user(alice.getEmail())).with(csrf()).param("name", "Studio").param(field, value))
                .andExpect(status().isOk()).andExpect(model().attribute("error", error));
    }
    private Studio create(User owner, String name) {
        return service.save(owner, null, Map.of("name", name, "city", "", "country", "", "description", "",
                "services", "", "equipment", "", "websiteUrl", "", "contactEmail", ""));
    }
    private static MockMultipartFile upload(byte[] bytes) { return new MockMultipartFile("photos", "studio.png", "text/plain", bytes); }
    private static byte[] image(int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
    private User account(String name) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@example.org");
        user.setPasswordHash("unused-test-hash");
        user.setEnabled(true);
        return users.saveAndFlush(user);
    }
}
