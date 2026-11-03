package com.guitarvault;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.admin-emails= EDITOR@example.com,second@example.com ")
@Transactional
class ArticleTests {
    @Autowired WebApplicationContext context;
    @Autowired ArticleRepository articles;
    @Autowired ArticleCoverRepository covers;
    @Autowired ArticleService service;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired AdminAccess admins;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    User editor;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        editor = new User(); editor.setFullName("La rédaction"); editor.setUsername("editor");
        editor.setEmail("editor@example.com"); editor.setPasswordHash(passwords.encode("Secret123!"));
        users.saveAndFlush(editor);
    }

    @Test
    void administratorIsAssignedOnlyToConfiguredVerifiedAccountsAndLoginGetsTheRole() throws Exception {
        assertThat(admins.allows(editor)).isTrue();
        assertThat(new AdminAccess("").allows(editor)).isFalse();
        editor.setEnabled(false); assertThat(admins.allows(editor)).isFalse();
        mvc.perform(post("/login").with(csrf()).param("email", editor.getEmail()).param("password", "Secret123!"))
                .andExpect(redirectedUrl("/login?error"));
        editor.setEnabled(true);
        mvc.perform(post("/login").with(csrf()).param("email", editor.getEmail()).param("password", "Secret123!"))
                .andExpect(authenticated().withRoles("USER", "ADMIN"));
        mvc.perform(get("/").with(user(editor.getEmail()).roles("ADMIN")))
                .andExpect(content().string(containsString("href=\"/admin/articles\"")));
        mvc.perform(get("/").with(user("member@example.com")))
                .andExpect(content().string(not(containsString("href=\"/admin/articles\""))));
    }

    @Test
    void allAdministrationPagesAndActionsRequireAdminAndCsrf() throws Exception {
        Article draft = saved("Private draft", false);
        for (String path : java.util.List.of("/admin/articles", "/admin/articles/new", "/admin/articles/" + draft.getId() + "/edit",
                "/admin/articles/" + draft.getId() + "/preview", "/admin/articles/" + draft.getId() + "/cover", "/admin/articles/" + draft.getId() + "/delete")) {
            mvc.perform(get(path)).andExpect(redirectedUrl("/login"));
            mvc.perform(get(path).with(user("member@example.com"))).andExpect(status().isForbidden());
        }
        for (String path : java.util.List.of("/admin/articles", "/admin/articles/" + draft.getId(),
                "/admin/articles/" + draft.getId() + "/publish", "/admin/articles/" + draft.getId() + "/unpublish", "/admin/articles/" + draft.getId() + "/delete")) {
            mvc.perform(post(path).with(csrf()).param("version", "0")).andExpect(redirectedUrl("/login"));
            mvc.perform(post(path).with(user("member@example.com")).with(csrf()).param("version", "0")).andExpect(status().isForbidden());
            mvc.perform(post(path).with(user(editor.getEmail()).roles("ADMIN"))).andExpect(status().isForbidden());
        }
        assertThat(articles.count()).isEqualTo(1);
    }

    @Test
    void draftIsPrivateAndPublishingMakesItAppearBelowTheWelcomeBlock() throws Exception {
        mvc.perform(adminPost("/admin/articles", Map.of("title", "Accorder sa guitare", "action", "draft")))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("success", "article.savedSuccess"));
        Article article = articles.findAll().getFirst();
        assertThat(article.isPublished()).isFalse();
        assertThat(article.getAuthor().getId()).isEqualTo(editor.getId());
        mvc.perform(get("/articles/" + article.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/articles/" + article.getId()).with(user(editor.getEmail()).roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get("/")).andExpect(content().string(not(containsString(article.getTitle()))));
        mvc.perform(get("/admin/articles/" + article.getId() + "/preview").with(user(editor.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Aperçu privé")))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(adminPost("/admin/articles/" + article.getId(), Map.of("version", "" + article.getVersion(), "title", article.getTitle(), "action", "publish")))
                .andExpect(status().is3xxRedirection());
        assertThat(article.isPublished()).isTrue(); assertThat(article.getPublishedAt()).isNotNull();
        String homepage = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(homepage.indexOf("Créer mon compte Guitar Vault")).isLessThan(homepage.indexOf("Accorder sa guitare"));
        mvc.perform(get("/articles/" + article.getId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>Accorder sa guitare — Guitar Vault</title>")));
    }

    @Test
    void editCanBeSavedPublishedOrReturnedToDraftAndDeleteRequiresConfirmation() throws Exception {
        Article article = saved("Original", true);
        mvc.perform(adminPost("/admin/articles/" + article.getId(), Map.of("title", "Version corrigée", "action", "publish", "version", "" + article.getVersion())))
                .andExpect(status().is3xxRedirection());
        assertThat(article.getTitle()).isEqualTo("Version corrigée");
        mvc.perform(post("/admin/articles/" + article.getId() + "/unpublish").with(user(editor.getEmail()).roles("ADMIN")).with(csrf()).param("version", "" + article.getVersion()))
                .andExpect(redirectedUrl("/admin/articles"));
        mvc.perform(get("/articles/" + article.getId())).andExpect(status().isNotFound());
        mvc.perform(post("/admin/articles/" + article.getId() + "/publish").with(user(editor.getEmail()).roles("ADMIN")).with(csrf()).param("version", "" + article.getVersion()))
                .andExpect(flash().attribute("success", "article.publishedSuccess"));
        mvc.perform(get("/admin/articles/" + article.getId() + "/delete").with(user(editor.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Supprimer définitivement")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        assertThat(articles.existsById(article.getId())).isTrue();
        mvc.perform(post("/admin/articles/" + article.getId() + "/delete").with(user(editor.getEmail()).roles("ADMIN")).with(csrf()).param("version", "" + article.getVersion()))
                .andExpect(flash().attribute("success", "article.deletedSuccess"));
        assertThat(articles.existsById(article.getId())).isFalse();
        mvc.perform(get("/articles/" + article.getId())).andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidContentAndPreservesTypedValuesWithoutPartialSave() throws Exception {
        for (var invalid : Map.of("title", " ", "summary", "s".repeat(501), "body", "b".repeat(50001),
                "category", "unknown", "coverAlt", "a".repeat(201), "action", "invalid").entrySet()) {
            mvc.perform(adminPost("/admin/articles", Map.of(invalid.getKey(), invalid.getValue())))
                    .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
        }
        mvc.perform(adminPost("/admin/articles", Map.of("body", "", "action", "publish", "title", "Mon titre conservé")))
                .andExpect(model().attribute("error", "article.error.publish"))
                .andExpect(content().string(containsString("Mon titre conservé")));
        assertThat(articles.count()).isZero();
        mvc.perform(adminPost("/admin/articles", Map.of("summary", "", "body", "", "action", "draft")))
                .andExpect(status().is3xxRedirection());
        Article draft = articles.findAll().getFirst();
        mvc.perform(post("/admin/articles/" + draft.getId() + "/publish").with(user(editor.getEmail()).roles("ADMIN")).with(csrf()).param("version", "" + draft.getVersion()))
                .andExpect(flash().attribute("error", "article.error.publish"));
        assertThat(draft.isPublished()).isFalse();
    }

    @Test
    void escapesContentInPublicPagesAndAdminForms() throws Exception {
        Article article = saved("<script>alert('title')</script>", true);
        article.setBody("First line\n\n<script>alert('body')</script>"); articles.flush();
        for (String path : java.util.List.of("/", "/articles", "/articles/" + article.getId())) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(content().string(containsString("&lt;script&gt;")))
                    .andExpect(content().string(not(containsString("<script>alert"))));
        }
        mvc.perform(get("/admin/articles/" + article.getId() + "/edit").with(user(editor.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("&lt;script&gt;")));
    }

    @Test
    void listSearchCategoryStatusPaginationAndHomeLimitExcludeDrafts() throws Exception {
        for (int i = 0; i < 11; i++) saved("Guide guitare " + i, true);
        Article bass = saved("Entretien basse", true); bass.setCategory("gear");
        saved("Private guitare", false); articles.flush();
        mvc.perform(get("/articles")).andExpect(status().isOk()).andExpect(model().attribute("articlePage", hasProperty("totalElements", is(12L))))
                .andExpect(content().string(containsString("Page 1 sur 2")))
                .andExpect(content().string(not(containsString("Private guitare"))));
        mvc.perform(get("/articles?page=1")).andExpect(model().attribute("articlePage", hasProperty("numberOfElements", is(3))));
        mvc.perform(get("/articles?q=GUITARE&category=guides")).andExpect(model().attribute("articlePage", hasProperty("totalElements", is(11L))));
        mvc.perform(get("/articles?category=gear")).andExpect(model().attribute("articlePage", hasProperty("totalElements", is(1L))));
        mvc.perform(get("/articles?q=%25")).andExpect(model().attribute("articlePage", hasProperty("totalElements", is(0L))));
        mvc.perform(get("/articles?page=-1")).andExpect(status().isOk());
        mvc.perform(get("/")).andExpect(model().attribute("articles", hasSize(3)));
        mvc.perform(get("/admin/articles?state=draft").with(user(editor.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(model().attribute("articlePage", hasProperty("totalElements", is(1L))))
                .andExpect(content().string(containsString("Private guitare")));
        mvc.perform(get("/admin/articles").with(user(editor.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Page 1 sur 2")));
    }

    @Test
    void genuineCoverIsPrivateUntilPublicationThenCanBeReplacedRemovedAndDeleted() throws Exception {
        byte[] png = png();
        Article article = service.save(null, form(Map.of("action", "draft", "coverAlt", "Guitare sur son support")),
                new MockMultipartFile("cover", "guitar.png", "image/png", png), editor.getEmail());
        String publicPath = "/articles/" + article.getId() + "/cover";
        String privatePath = "/admin/articles/" + article.getId() + "/cover";
        mvc.perform(get(publicPath)).andExpect(status().isNotFound());
        mvc.perform(get(privatePath).with(user(editor.getEmail()).roles("ADMIN")))
                .andExpect(content().bytes(png)).andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        service.publication(article.getId(), "" + article.getVersion(), true);
        mvc.perform(get(publicPath)).andExpect(content().bytes(png)).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/articles/" + article.getId())).andExpect(content().string(containsString("alt=\"Guitare sur son support\"")));
        service.save(article.getId(), form(Map.of("version", "" + article.getVersion(), "coverAlt", "Nouvelle description")),
                new MockMultipartFile("cover", "new.png", "image/png", png), editor.getEmail());
        assertThat(covers.count()).isEqualTo(1);
        service.save(article.getId(), form(Map.of("version", "" + article.getVersion(), "removeCover", "true")), null, editor.getEmail());
        assertThat(covers.existsById(article.getId())).isFalse(); assertThat(article.isCoverPresent()).isFalse();
        service.save(article.getId(), form(Map.of("version", "" + article.getVersion(), "coverAlt", "Couverture")),
                new MockMultipartFile("cover", "guitar.png", "image/png", png), editor.getEmail());
        service.delete(article.getId(), "" + article.getVersion());
        assertThat(covers.count()).isZero();
        mvc.perform(get(publicPath)).andExpect(status().isNotFound());
    }

    @Test
    void multipartUploadValidatesImageContentsAndRequiresAccessibleDescription() throws Exception {
        for (MockMultipartFile image : java.util.List.of(
                new MockMultipartFile("cover", "fake.png", "image/png", "<svg>fake</svg>".getBytes()),
                new MockMultipartFile("cover", "large.png", "image/png", new byte[5 * 1024 * 1024 + 1]))) {
            var request = multipart("/admin/articles").file(image).with(user(editor.getEmail()).roles("ADMIN")).with(csrf());
            form(Map.of("coverAlt", "Description")).forEach(request::param);
            mvc.perform(request).andExpect(model().attribute("error", "article.error.cover"));
        }
        var missingAlt = multipart("/admin/articles").file(new MockMultipartFile("cover", "real.png", "image/png", png()))
                .with(user(editor.getEmail()).roles("ADMIN")).with(csrf());
        form(Map.of()).forEach(missingAlt::param);
        mvc.perform(missingAlt).andExpect(model().attribute("error", "article.error.alt"));
        assertThat(articles.count()).isZero(); assertThat(covers.count()).isZero();
        var valid = multipart("/admin/articles").file(new MockMultipartFile("cover", "real.png", "image/png", png()))
                .with(user(editor.getEmail()).roles("ADMIN")).with(csrf());
        form(Map.of("coverAlt", "Une guitare")).forEach(valid::param);
        mvc.perform(valid).andExpect(status().is3xxRedirection());
        assertThat(covers.count()).isEqualTo(1);
    }

    @Test
    void staleEditsPublicationAndDeletionDoNotOverwriteNewerWork() throws Exception {
        Article article = saved("Original", true);
        String oldVersion = "" + article.getVersion();
        service.save(article.getId(), form(Map.of("version", oldVersion, "title", "Newer work")), null, editor.getEmail());
        mvc.perform(adminPost("/admin/articles/" + article.getId(), Map.of("version", oldVersion, "title", "Stale work")))
                .andExpect(model().attribute("error", "article.error.conflict"))
                .andExpect(content().string(containsString("Stale work")));
        for (String action : java.util.List.of("publish", "unpublish", "delete")) {
            mvc.perform(post("/admin/articles/" + article.getId() + "/" + action).with(user(editor.getEmail()).roles("ADMIN")).with(csrf()).param("version", oldVersion))
                    .andExpect(flash().attribute("error", "article.error.conflict"));
        }
        assertThat(articles.findById(article.getId()).orElseThrow().getTitle()).isEqualTo("Newer work");
    }

    @Test
    void emptyStatesAndEnglishTemplatesRenderWithoutMissingMessages() throws Exception {
        mvc.perform(get("/")).andExpect(content().string(containsString("Les premiers articles arrivent bientôt")));
        mvc.perform(get("/articles?lang=en")).andExpect(content().string(containsString("All things guitar")));
        Article article = saved("English article", true);
        for (String path : java.util.List.of("/admin/articles", "/admin/articles/new", "/admin/articles/" + article.getId() + "/edit",
                "/admin/articles/" + article.getId() + "/preview", "/admin/articles/" + article.getId() + "/delete")) {
            mvc.perform(get(path).param("lang", "en").with(user(editor.getEmail()).roles("ADMIN")))
                    .andExpect(status().isOk()).andExpect(content().string(not(containsString("??"))))
                    .andExpect(content().string(not(containsString("Enregistrer"))));
        }
        mvc.perform(get("/articles/999999")).andExpect(status().isNotFound());
    }

    private Article saved(String title, boolean published) {
        return service.save(null, form(Map.of("title", title, "action", published ? "publish" : "draft")), null, editor.getEmail());
    }
    private Map<String, String> form(Map<String, String> overrides) {
        Map<String, String> values = new HashMap<>(Map.of("title", "Un article", "summary", "Introduction guitare",
                "body", "Première partie\n\nDeuxième partie", "category", "guides", "action", "publish", "version", "0"));
        values.putAll(overrides); return values;
    }
    private MockHttpServletRequestBuilder adminPost(String path, Map<String, String> overrides) {
        var request = post(path).with(user(editor.getEmail()).roles("ADMIN")).with(csrf());
        form(overrides).forEach(request::param); return request;
    }
    private byte[] png() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes); return bytes.toByteArray();
    }
}
