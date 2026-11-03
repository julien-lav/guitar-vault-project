package com.guitarvault;

import com.guitarvault.fixtures.AppFixtures;
import com.guitarvault.fixtures.FixtureLoadRepository;
import com.guitarvault.fixtures.FixtureTarget;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:guitarvault-fixtures;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "app.admin-emails=", "app.fixtures.password=DemoGuitar2026!"})
@ActiveProfiles("fixtures")
@Transactional
class FixtureTests {
    @Autowired AppFixtures fixtures;
    @Autowired FixtureLoadRepository loads;
    @Autowired UserRepository users;
    @Autowired ArticleRepository articles;
    @Autowired ArticleService articleService;
    @Autowired InstrumentRepository instruments;
    @Autowired PasswordEncoder passwords;
    @Autowired EntityManager entityManager;
    @Autowired WebApplicationContext context;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;

    @BeforeEach
    void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test
    void seedsFiveVerifiedAccountsWithPersistedRolesAndHashedPasswordsWithoutEmail() {
        assertThat(users.count()).isEqualTo(5);
        for (Role role : Role.values()) {
            String name = role.name().substring(5).replace("_", "").toLowerCase(java.util.Locale.ROOT);
            User account = account(name);
            assertThat(account.isEnabled()).isTrue();
            assertThat(account.getVerificationTokenHash()).isNull();
            assertThat(account.getRoles()).contains(Role.ROLE_USER, role);
            assertThat(account.getPasswordHash()).isNotEqualTo("DemoGuitar2026!");
            assertThat(passwords.matches("DemoGuitar2026!", account.getPasswordHash())).isTrue();
        }
        entityManager.clear();
        assertThat(account("editor").getRoles()).containsExactlyInAnyOrder(Role.ROLE_USER, Role.ROLE_EDITOR);
        assertThat(loads.existsById(AppFixtures.DATASET)).isTrue();
        verifyNoInteractions(mail);
    }

    @Test
    void repeatedLoadingPreservesEditedAccountsContentAndDeliberatelyDeletedRecords() throws Exception {
        User admin = account("admin");
        admin.setFullName("Nom modifié");
        admin.setPasswordHash(passwords.encode("ChangedPassword!"));
        admin.setRoles(Set.of(Role.ROLE_USER));
        Article article = articles.findAll().getFirst();
        article.setTitle("Titre modifié à la main");
        Long deleted = instruments.findAll().getFirst().getId();
        instruments.deleteById(deleted);
        users.flush(); articles.flush(); instruments.flush();
        fixtures.run(new DefaultApplicationArguments());
        entityManager.clear();
        assertThat(users.count()).isEqualTo(5);
        assertThat(articles.count()).isEqualTo(5);
        assertThat(instruments.count()).isEqualTo(6);
        assertThat(account("admin").getFullName()).isEqualTo("Nom modifié");
        assertThat(account("admin").getRoles()).containsExactly(Role.ROLE_USER);
        assertThat(passwords.matches("ChangedPassword!", account("admin").getPasswordHash())).isTrue();
        assertThat(articles.findById(article.getId()).orElseThrow().getTitle()).isEqualTo("Titre modifié à la main");
        assertThat(instruments.existsById(deleted)).isFalse();
    }

    @Test
    void demoHasPublishedArticlesPrivateDraftsAndDistinctOwnedCollections() throws Exception {
        assertThat(articles.countByPublished(true)).isEqualTo(3);
        assertThat(articles.countByPublished(false)).isEqualTo(2);
        assertThat(instruments.count()).isEqualTo(7);
        mvc.perform(get("/")).andExpect(model().attribute("articles", hasSize(3)));
        for (Article draft : articles.findAll().stream().filter(a -> !a.isPublished()).toList()) {
            mvc.perform(get("/articles/" + draft.getId())).andExpect(status().isNotFound());
        }
        var session = login("user");
        mvc.perform(get("/instruments").session(session)).andExpect(status().isOk())
                .andExpect(model().attribute("instruments", hasSize(2)))
                .andExpect(content().string(not(containsString("Jazz Bass de démonstration"))));
        Instrument other = instruments.findByOwnerIdOrderByIdDesc(account("superuser").getId()).getFirst();
        mvc.perform(get("/instruments/" + other.getId()).session(session)).andExpect(status().isNotFound());
    }

    @Test
    void realLoginsUseDatabaseRolesAndOnlyEditorialAccountsReachAdministration() throws Exception {
        for (String name : java.util.List.of("superadmin", "admin", "editor", "superuser", "user")) {
            var session = login(name);
            mvc.perform(get("/admin/articles").session(session))
                    .andExpect(status().is(java.util.Set.of("superadmin", "admin", "editor").contains(name) ? 200 : 403));
        }
        mvc.perform(get("/admin/articles").session(login("editor")))
                .andExpect(model().attribute("articlePage", hasProperty("totalElements", is(1L))))
                .andExpect(content().string(containsString("Préparer sa prochaine répétition")))
                .andExpect(content().string(not(containsString("Choisir les accessoires de sa collection"))));
    }

    @Test
    void editorCanSaveOwnDraftButCannotPublishDeleteOrAccessOtherDraftsOrPublishedArticles() throws Exception {
        var session = login("editor");
        Article own = articles.findAll().stream().filter(a -> a.getAuthor().getEmail().equals("editor@guitar-vault.test")).findFirst().orElseThrow();
        mvc.perform(get("/admin/articles/" + own.getId() + "/edit").session(session))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("value=\"publish\""))));
        mvc.perform(get("/admin/articles/" + own.getId() + "/preview").session(session)).andExpect(status().isOk());
        mvc.perform(post("/admin/articles/" + own.getId()).session(session).with(csrf())
                        .param("version", "" + own.getVersion()).param("title", "Brouillon corrigé")
                        .param("summary", "Résumé").param("body", "Contenu").param("category", "guides").param("action", "draft"))
                .andExpect(status().is3xxRedirection());
        assertThat(own.getTitle()).isEqualTo("Brouillon corrigé");
        mvc.perform(post("/admin/articles/" + own.getId()).session(session).with(csrf())
                        .param("version", "" + own.getVersion()).param("action", "publish"))
                .andExpect(status().isForbidden());
        for (String action : java.util.List.of("publish", "unpublish", "delete")) {
            mvc.perform(post("/admin/articles/" + own.getId() + "/" + action).session(session).with(csrf())
                            .param("version", "" + own.getVersion())).andExpect(status().isForbidden());
        }
        mvc.perform(get("/admin/articles/" + own.getId() + "/delete").session(session)).andExpect(status().isForbidden());
        for (Article other : articles.findAll().stream().filter(a -> !a.getId().equals(own.getId())).toList()) {
            for (String action : java.util.List.of("edit", "preview", "cover")) {
                mvc.perform(get("/admin/articles/" + other.getId() + "/" + action).session(session)).andExpect(status().isForbidden());
            }
            mvc.perform(post("/admin/articles/" + other.getId()).session(session).with(csrf())
                            .param("version", "" + other.getVersion()).param("action", "draft"))
                    .andExpect(status().isForbidden());
        }
        // Ownership and publication checks are repeated inside the mutation transaction.
        Article published = articles.findAll().stream().filter(Article::isPublished).findFirst().orElseThrow();
        assertThatThrownBy(() -> articleService.save(published.getId(),
                Map.of("version", "" + published.getVersion(), "action", "draft"), null,
                account("editor").getEmail(), false)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void editorsCanCreateDraftsAndAdminsCanPublishThem() throws Exception {
        mvc.perform(post("/admin/articles").session(login("editor")).with(csrf())
                        .param("title", "Nouveau brouillon").param("summary", "Résumé").param("body", "Texte")
                        .param("category", "guides").param("action", "draft"))
                .andExpect(status().is3xxRedirection());
        Article draft = articles.findAll().stream().filter(a -> a.getTitle().equals("Nouveau brouillon")).findFirst().orElseThrow();
        assertThat(draft.getAuthor().getEmail()).isEqualTo("editor@guitar-vault.test");
        mvc.perform(post("/admin/articles/" + draft.getId() + "/publish").session(login("admin")).with(csrf())
                        .param("version", "" + draft.getVersion()))
                .andExpect(flash().attribute("success", "article.publishedSuccess"));
        mvc.perform(get("/articles/" + draft.getId())).andExpect(status().isOk());
        mvc.perform(get("/admin/articles/" + draft.getId() + "/edit").session(login("editor")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/articles/" + draft.getId() + "/edit").session(login("superadmin")))
                .andExpect(status().isOk());
    }

    @Test
    void targetGuardRejectsOrdinaryPersistentDatabasesAndProductionProfiles() {
        assertThatCode(() -> FixtureTarget.validate("jdbc:h2:file:./data/guitarvault-demo;DB_CLOSE_ON_EXIT=FALSE", false)).doesNotThrowAnyException();
        assertThatCode(() -> FixtureTarget.validate("jdbc:h2:mem:fixtures", false)).doesNotThrowAnyException();
        for (String url : java.util.List.of("jdbc:h2:file:./data/guitarvault", "jdbc:h2:file:./data/other", "jdbc:postgresql://localhost/prod")) {
            assertThatThrownBy(() -> FixtureTarget.validate(url, false)).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> FixtureTarget.validate("jdbc:h2:mem:fixtures", true)).isInstanceOf(IllegalStateException.class);
    }

    private User account(String name) { return users.findByEmail(name + "@guitar-vault.test").orElseThrow(); }
    private MockHttpSession login(String name) throws Exception {
        var result = mvc.perform(post("/login").with(csrf()).param("email", name + "@guitar-vault.test")
                        .param("password", "DemoGuitar2026!"))
                .andExpect(authenticated()).andExpect(redirectedUrl("/dashboard")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
