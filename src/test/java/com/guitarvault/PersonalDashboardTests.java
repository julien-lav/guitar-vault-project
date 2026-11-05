package com.guitarvault;

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
class PersonalDashboardTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired BandRepository bands;
    @Autowired StudioRepository studios;
    @Autowired WishlistRepository wishes;
    @Autowired StudioQuoteRepository quotes;
    @MockitoBean JavaMailSender mail;
    private MockMvc mvc;
    private User alice;
    private User bob;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        alice = account("personal-alice");
        bob = account("personal-bob");
    }

    @Test
    void emptyPersonalSpaceHasZeroCountsUsefulActionsAndTranslatedLabels() throws Exception {
        mvc.perform(get("/dashboard").with(user(alice.getEmail())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("overview", Map.of("bands", 0L, "studios", 0L, "wishlist", 0L, "projects", 0L)))
                .andExpect(model().attribute("toAnswerCount", 0L)).andExpect(model().attribute("answersCount", 0L))
                .andExpect(model().attribute("myBands", hasSize(0))).andExpect(model().attribute("myStudios", hasSize(0)))
                .andExpect(model().attribute("projects", hasSize(0))).andExpect(model().attribute("wishes", hasSize(0)))
                .andExpect(content().string(containsString("Votre collection commence ici")))
                .andExpect(content().string(containsString("href=\"/studios/new\"")))
                .andExpect(content().string(containsString("href=\"/bands/new\"")));
        mvc.perform(get("/dashboard").with(user(alice.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("My studio projects")))
                .andExpect(content().string(containsString("Discover")))
                .andExpect(content().string(not(containsString("??"))));
    }

    @Test
    void showsMembershipsManagedStudiosPrivateWishesAndOnlyParticipatingRequests() throws Exception {
        User outsider = account("personal-outsider");
        Band membership = band(bob, "Alice <script>band</script>", alice);
        band(bob, "Unrelated band");
        Studio own = studio(alice, "My studio");
        Studio other = studio(bob, "Other studio");
        WishlistItem wish = wishes.saveAndFlush(new WishlistItem(alice, "My wish", "Fender", null, ""));
        wishes.saveAndFlush(new WishlistItem(bob, "Bob private wish", "Gibson", null, ""));
        StudioQuote pending = quote(alice, other, "My pending project");
        StudioQuote answered = quote(alice, other, "My answered project");
        answered.answer("An offer", null);
        StudioQuote incoming = quote(bob, own, "Received project");
        StudioQuote oldIncoming = quote(outsider, own, "Already handled");
        oldIncoming.answer("Done", null);
        quote(outsider, other, "Unrelated private project");
        quotes.flush();
        mvc.perform(get("/dashboard").with(user(alice.getEmail())).param("ownerId", bob.getId().toString()))
                .andExpect(status().isOk()).andExpect(model().attribute("myBands", contains(membership)))
                .andExpect(model().attribute("myStudios", contains(own))).andExpect(model().attribute("wishes", contains(wish)))
                .andExpect(model().attribute("projects", contains(answered, pending)))
                .andExpect(model().attribute("pendingRequests", contains(incoming)))
                .andExpect(model().attribute("overview", Map.of("bands", 1L, "studios", 1L, "wishlist", 1L, "projects", 2L)))
                .andExpect(model().attribute("toAnswerCount", 1L)).andExpect(model().attribute("answersCount", 1L))
                .andExpect(content().string(containsString("Alice &lt;script&gt;band&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("Unrelated band"))))
                .andExpect(content().string(not(containsString("Bob private wish"))))
                .andExpect(content().string(not(containsString("Unrelated private project"))))
                .andExpect(content().string(not(containsString("Already handled"))))
                .andExpect(content().string(containsString("/quotes/" + incoming.getId())));
    }

    @Test
    void previewsAreLimitedToThreeButCountersIncludeAllPersonalItems() throws Exception {
        Studio other = studio(bob, "Receiving studio");
        Studio own = studio(alice, "Own studio 0");
        for (int i = 0; i < 5; i++) {
            band(alice, "Band " + i);
            if (i > 0) studio(alice, "Own studio " + i);
            wishes.saveAndFlush(new WishlistItem(alice, "Wish " + i, "Fender", null, ""));
            quote(alice, other, "Outgoing " + i);
            quote(bob, own, "Incoming " + i);
        }
        mvc.perform(get("/dashboard").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("myBands", hasSize(3)))
                .andExpect(model().attribute("myStudios", hasSize(3))).andExpect(model().attribute("wishes", hasSize(3)))
                .andExpect(model().attribute("projects", hasSize(3))).andExpect(model().attribute("pendingRequests", hasSize(3)))
                .andExpect(model().attribute("overview", Map.of("bands", 5L, "studios", 5L, "wishlist", 5L, "projects", 5L)))
                .andExpect(model().attribute("toAnswerCount", 5L))
                .andExpect(content().string(containsString("Outgoing 4")))
                .andExpect(content().string(not(containsString("Outgoing 0"))));
    }

    @Test
    void answeringARequestUpdatesBothParticipantsDashboardCounts() throws Exception {
        Studio own = studio(alice, "Studio");
        StudioQuote request = quote(bob, own, "Project");
        mvc.perform(get("/dashboard").with(user(alice.getEmail())))
                .andExpect(model().attribute("toAnswerCount", 1L));
        mvc.perform(post("/quotes/" + request.getId() + "/reply").with(user(alice.getEmail())).with(csrf())
                        .param("message", "Available"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/dashboard").with(user(alice.getEmail())))
                .andExpect(model().attribute("toAnswerCount", 0L)).andExpect(model().attribute("pendingRequests", hasSize(0)));
        mvc.perform(get("/dashboard").with(user(bob.getEmail())))
                .andExpect(model().attribute("answersCount", 1L)).andExpect(model().attribute("projects", contains(request)));
        assertThat(request.getStatus()).isEqualTo(StudioQuote.Status.ANSWERED);
    }

    @Test
    void navigationSeparatesDiscoveryFromPersonalLinksAndKeepsAccessibleLandmarks() throws Exception {
        String publicHtml = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(publicHtml).doesNotContain("<summary>Mon espace</summary>");
        assertThat(menu(publicHtml, "Découvrir")).contains("href=\"/studios\"");
        assertThat(publicHtml).contains("href=\"#main-content\"", "id=\"main-content\"");
        String html = mvc.perform(get("/dashboard").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String personal = menu(html, "Mon espace");
        assertThat(personal).contains("href=\"/dashboard\"", "aria-current=\"page\"", "href=\"/quotes\"",
                "href=\"/dashboard#dashboard-bands\"", "href=\"/dashboard#dashboard-studios\"");
        assertThat(menu(html, "Découvrir")).contains("href=\"/articles\"", "href=\"/bands\"", "href=\"/studios\"");
        String navigation = html.substring(html.indexOf("<nav"), html.indexOf("</nav>"));
        assertThat(navigation).contains("action=\"/logout\"", "name=\"_csrf\"").doesNotContain("href=\"/admin/articles\"");
    }

    private static String menu(String html, String title) {
        var matcher = java.util.regex.Pattern.compile(
                "<details class=\"nav-menu\"[^>]*>\\s*<summary[^>]*>" + java.util.regex.Pattern.quote(title)
                        + "</summary>(.*?)</details>", java.util.regex.Pattern.DOTALL).matcher(html);
        assertThat(matcher.find()).as("Navigation menu %s", title).isTrue();
        return matcher.group(1);
    }

    private Studio studio(User owner, String name) {
        Studio studio = new Studio(owner);
        studio.setName(name);
        return studios.saveAndFlush(studio);
    }
    private Band band(User creator, String name, User... members) {
        Band band = new Band(creator);
        band.setName(name);
        band.getMembers().add(creator);
        band.getMembers().addAll(List.of(members));
        return bands.saveAndFlush(band);
    }
    private StudioQuote quote(User requester, Studio studio, String title) {
        return quotes.saveAndFlush(new StudioQuote(studio, requester, title, "Project description", null, null, null));
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
