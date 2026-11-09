package com.guitarvault;

import jakarta.persistence.EntityManager;
import java.util.Set;
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
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class ModerationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired BandRepository bands;
    @Autowired ContentReportRepository reports;
    @Autowired ModerationAuditRepository audit;
    @Autowired ModerationService moderation;
    @Autowired ProfilePhotoRepository photos;
    @Autowired StudioRepository studios;
    @Autowired StudioPhotoRepository studioPhotos;
    @Autowired ConcertRepository concerts;
    @Autowired ConcertPosterRepository posters;
    @Autowired ArticleRepository articles;
    @Autowired ArticleCoverRepository covers;
    @Autowired EntityManager em;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    User reporter, owner, admin, superAdmin;
    Band band;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        reporter = account("reporter", Role.ROLE_USER);
        owner = account("owner", Role.ROLE_USER);
        admin = account("moderator", Role.ROLE_ADMIN);
        superAdmin = account("supermoderator", Role.ROLE_SUPER_ADMIN);
        band = new Band(owner); band.setName("Reported band"); band.setDescription("Original description");
        band.getMembers().add(owner); bands.saveAndFlush(band);
    }

    @Test void reportIsPrivateAndDoesNotHideContent() throws Exception {
        mvc.perform(get("/reports/new").param("type", "BAND").param("targetId", band.getId().toString()).with(user(reporter.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("??report."))));
        mvc.perform(post("/reports").with(user(reporter.getEmail())).with(csrf()).param("type", "BAND")
                        .param("targetId", band.getId().toString()).param("reason", "SPAM").param("explanation", "Private complaint"))
                .andExpect(status().is3xxRedirection());
        ContentReport report = reports.findAll().getFirst();
        assertThat(report.getStatus()).isEqualTo(ContentReport.Status.OPEN);
        assertThat(band.isModerationHidden()).isFalse();
        mvc.perform(get("/reports/" + report.getId()).with(user(reporter.getEmail())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(get("/reports/" + report.getId()).with(user(owner.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get("/reports").with(user(owner.getEmail())))
                .andExpect(content().string(not(containsString("Private complaint"))));
        mvc.perform(get("/bands/" + band.getId()).with(user(reporter.getEmail()))).andExpect(status().isOk());
    }

    @Test void queueAndActionsAreRestrictedAndCsrfProtected() throws Exception {
        ContentReport report = reportBand();
        for (String role : new String[]{"USER", "EDITOR"}) {
            mvc.perform(get("/admin/moderation").with(user(reporter.getEmail()).roles(role))).andExpect(status().isForbidden());
            mvc.perform(post("/admin/moderation/reports/" + report.getId() + "/action").with(user(reporter.getEmail()).roles(role))
                    .with(csrf()).param("action", "HIDE").param("note", "Reason")).andExpect(status().isForbidden());
        }
        mvc.perform(get("/admin/moderation").with(user(admin.getEmail()).roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/admin/moderation/reports/" + report.getId()).with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("??report."))));
        mvc.perform(post("/admin/moderation/reports/" + report.getId() + "/action").with(user(admin.getEmail()).roles("ADMIN"))
                .param("action", "HIDE").param("note", "Reason")).andExpect(status().isForbidden());
        assertThat(audit.count()).isZero();
    }

    @Test void hideAndRestoreAreAuditedAndSnapshotSurvivesEditing() throws Exception {
        Long id = reportBand().getId();
        band.setDescription("Edited description"); em.flush(); em.clear();
        act(id, "REVIEW"); act(id, "HIDE");
        mvc.perform(get("/bands/" + band.getId()).with(user(reporter.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get("/bands").with(user(reporter.getEmail())))
                .andExpect(content().string(not(containsString("Reported band"))));
        assertThat(reports.findById(id).orElseThrow().getSnapshot()).contains("Original description").doesNotContain("Edited description");
        assertThat(audit.findByReportIdOrderByIdAsc(id)).extracting(ModerationAudit::getAction)
                .containsExactly(ModerationAudit.Action.REVIEW, ModerationAudit.Action.HIDE);
        mvc.perform(get("/reports/" + id).with(user(reporter.getEmail())))
                .andExpect(content().string(not(containsString("Internal justification"))));
        act(id, "RESTORE");
        mvc.perform(get("/bands/" + band.getId()).with(user(reporter.getEmail()))).andExpect(status().isOk());
        assertThat(reports.findById(id).orElseThrow().getDecision()).isEqualTo(ContentReport.Decision.RESTORED);
    }

    @Test void invalidOrDuplicateReportsAndMissingNotesMakeNoChanges() throws Exception {
        assertThatThrownBy(() -> moderation.create(reporter, ContentReport.TargetType.BAND, band.getId(), "OTHER", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("report.explanationError");
        assertThatThrownBy(() -> moderation.create(owner, ContentReport.TargetType.BAND, band.getId(), "SPAM", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("report.selfError");
        ContentReport report = reportBand();
        assertThatThrownBy(this::reportBand).isInstanceOf(IllegalArgumentException.class).hasMessage("report.duplicateError");
        mvc.perform(post("/admin/moderation/reports/" + report.getId() + "/action").with(user(admin.getEmail()).roles("ADMIN"))
                        .with(csrf()).param("action", "HIDE").param("note", " "))
                .andExpect(flash().attribute("error", "report.noteError"));
        assertThat(band.isModerationHidden()).isFalse(); assertThat(audit.count()).isZero();
        act(report.getId(), "DISMISS");
        assertThat(reports.findById(report.getId()).orElseThrow().getDecision()).isEqualTo(ContentReport.Decision.DISMISSED);
        assertThat(moderation.create(reporter, ContentReport.TargetType.BAND, band.getId(), "SPAM", "New evidence").getId()).isNotEqualTo(report.getId());
    }

    @Test void onlySuperAdminCanSuspendIncludingViaGenericActionAndExistingSessionsAreRejected() throws Exception {
        Long id = reportBand().getId();
        for (String path : new String[]{"/action", "/account/suspend"}) {
            mvc.perform(post("/admin/moderation/reports/" + id + path).with(user(admin.getEmail()).roles("ADMIN"))
                    .with(csrf()).param("action", "SUSPEND").param("note", "Reason")).andExpect(status().isForbidden());
        }
        mvc.perform(post("/admin/moderation/reports/" + id + "/account/suspend").with(user(superAdmin.getEmail()).roles("SUPER_ADMIN"))
                .with(csrf()).param("note", "Account abuse")).andExpect(status().is3xxRedirection());
        em.flush(); em.clear();
        assertThat(users.findById(owner.getId()).orElseThrow().isEnabled()).isFalse();
        mvc.perform(get("/reports").with(user(owner.getEmail()))).andExpect(redirectedUrl("/login?suspended"));
        mvc.perform(post("/admin/moderation/reports/" + id + "/account/reactivate").with(user(superAdmin.getEmail()).roles("SUPER_ADMIN"))
                .with(csrf()).param("note", "Resolved")).andExpect(status().is3xxRedirection());
        em.flush(); em.clear();
        assertThat(users.findById(owner.getId()).orElseThrow().isEnabled()).isTrue();
        mvc.perform(get("/reports").with(user(owner.getEmail()))).andExpect(status().isOk());
    }

    @Test void hidingProfileBlocksPublicPhotoButModeratorCanReviewIt() throws Exception {
        photos.save(new ProfilePhoto(owner, "image/png", new byte[]{1, 2, 3}));
        Long id = moderation.create(reporter, ContentReport.TargetType.PROFILE, owner.getId(), "ABUSE", "Photo").getId();
        act(id, "HIDE");
        mvc.perform(get("/musicians/" + owner.getId()).with(user(reporter.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get("/musicians/" + owner.getId() + "/photo").with(user(reporter.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get("/admin/moderation/reports/" + id + "/media/0").with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1, 2, 3}));
        mvc.perform(get("/admin/moderation/reports/" + id + "/media/0").with(user(reporter.getEmail()))).andExpect(status().isForbidden());
    }

    @Test void superAdminAccountsAreProtectedAndReactivationDoesNotVerifyEmail() throws Exception {
        Long protectedId = moderation.create(reporter, ContentReport.TargetType.PROFILE, superAdmin.getId(), "SPAM", "").getId();
        mvc.perform(post("/admin/moderation/reports/" + protectedId + "/account/suspend").with(user(superAdmin.getEmail()).roles("SUPER_ADMIN"))
                .with(csrf()).param("note", "Reason")).andExpect(flash().attribute("error", "report.protectedAccount"));
        owner.setEnabled(false); owner.setSuspended(true); em.flush();
        Long id = reportBand().getId();
        mvc.perform(post("/admin/moderation/reports/" + id + "/account/reactivate").with(user(superAdmin.getEmail()).roles("SUPER_ADMIN"))
                .with(csrf()).param("note", "Resolved")).andExpect(status().is3xxRedirection());
        em.flush(); em.clear();
        User stored = users.findById(owner.getId()).orElseThrow();
        assertThat(stored.isSuspended()).isFalse(); assertThat(stored.isEnabled()).isFalse();
    }

    @Test void studioHidingAppliesToSearchDetailsAndImages() throws Exception {
        Studio studio = new Studio(owner); studio.setName("Moderated studio"); studios.saveAndFlush(studio);
        StudioPhoto photo = studioPhotos.saveAndFlush(new StudioPhoto(studio, "image/png", new byte[]{1}));
        Long id = moderation.create(reporter, ContentReport.TargetType.STUDIO, studio.getId(), "SCAM", "").getId();
        act(id, "HIDE");
        mvc.perform(get("/studios").with(user(reporter.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("Moderated studio"))));
        mvc.perform(get("/studios/" + studio.getId()).with(user(reporter.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get("/studios/" + studio.getId() + "/photos/" + photo.getId()).with(user(reporter.getEmail())))
                .andExpect(status().isNotFound());
        act(id, "RESTORE");
        mvc.perform(get("/studios/" + studio.getId()).with(user(reporter.getEmail()))).andExpect(status().isOk());
    }

    @Test void concertHidingAppliesToBandCalendarAndPoster() throws Exception {
        Concert concert = new Concert(band);
        concert.update("Moderated concert", java.time.LocalDate.now(java.time.ZoneOffset.UTC), "Venue", "City", "");
        concerts.saveAndFlush(concert);
        ConcertPoster poster = new ConcertPoster(concert); poster.replace("image/png", new byte[]{1}); posters.saveAndFlush(poster);
        Long id = moderation.create(reporter, ContentReport.TargetType.CONCERT, concert.getId(), "SPAM", "").getId();
        act(id, "HIDE");
        mvc.perform(get("/bands/" + band.getId()).with(user(reporter.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("Moderated concert"))));
        mvc.perform(get("/bands/" + band.getId() + "/calendar").with(user(owner.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("Moderated concert"))));
        mvc.perform(get("/concerts/" + concert.getId()).with(user(reporter.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get("/concerts/" + concert.getId() + "/poster").with(user(reporter.getEmail()))).andExpect(status().isNotFound());
        act(id, "RESTORE");
        mvc.perform(get("/concerts/" + concert.getId()).with(user(reporter.getEmail()))).andExpect(status().isOk());
    }

    @Test void publishedArticleHidingAppliesToAnonymousReadersAndCover() throws Exception {
        Article article = new Article(); article.setAuthor(owner); article.setTitle("Moderated article");
        article.setBody("Original article"); article.setPublished(true); article.setPublishedAt(java.time.Instant.now());
        articles.saveAndFlush(article); covers.saveAndFlush(new ArticleCover(article, "image/png", new byte[]{1}));
        Long id = moderation.create(reporter, ContentReport.TargetType.ARTICLE, article.getId(), "ABUSE", "").getId();
        act(id, "HIDE");
        mvc.perform(get("/articles")).andExpect(status().isOk()).andExpect(content().string(not(containsString("Moderated article"))));
        mvc.perform(get("/articles/" + article.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/articles/" + article.getId() + "/cover")).andExpect(status().isNotFound());
        act(id, "RESTORE");
        mvc.perform(get("/articles/" + article.getId())).andExpect(status().isOk());
    }

    @Test void deletedContentRetainsItsReportAndCanStillBeDismissed() throws Exception {
        Long id = reportBand().getId();
        bands.delete(band); em.flush(); em.clear();
        mvc.perform(get("/admin/moderation/reports/" + id).with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Original description")));
        act(id, "DISMISS");
        mvc.perform(get("/admin/moderation/reports/" + id).with(user(admin.getEmail()).roles("ADMIN"))).andExpect(status().isOk());
        assertThat(reports.findById(id).orElseThrow().getSnapshot()).contains("Original description");
    }

    private ContentReport reportBand() { return moderation.create(reporter, ContentReport.TargetType.BAND, band.getId(), "SPAM", "Private complaint"); }
    private void act(Long id, String action) throws Exception {
        mvc.perform(post("/admin/moderation/reports/" + id + "/action").with(user(admin.getEmail()).roles("ADMIN"))
                .with(csrf()).param("action", action).param("note", "Internal justification"))
                .andExpect(status().is3xxRedirection()).andExpect(result -> assertThat(result.getFlashMap().containsKey("error")).isFalse());
        em.flush(); em.clear();
    }
    private User account(String name, Role role) {
        User user = new User(); user.setUsername("moderation-" + name); user.setEmail(name + "@moderation.test");
        user.setFullName(name); user.setPasswordHash("unused"); user.setEnabled(true); user.setRoles(Set.of(role));
        return users.saveAndFlush(user);
    }
}
