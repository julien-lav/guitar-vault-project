package com.guitarvault;

import java.time.LocalDate;
import java.time.ZoneOffset;
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
class StudioQuoteTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired StudioRepository studios;
    @Autowired StudioQuoteRepository quotes;
    @Autowired StudioQuoteMessageRepository messages;
    @Autowired EntityManager entityManager;
    @MockitoBean JavaMailSender mail;
    private MockMvc mvc;
    private User alice;
    private User bob;
    private User mallory;
    private Studio studio;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        alice = account("quote-alice");
        bob = account("quote-bob");
        mallory = account("quote-mallory");
        studio = studio(bob, "Bob studio");
    }

    @Test
    void exposesRequestActionLocalizedFormAndPrivateEmptyInbox() throws Exception {
        String formPath = "/studios/" + studio.getId() + "/quotes/new";
        mvc.perform(get("/studios/" + studio.getId()).with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString(formPath)));
        mvc.perform(get("/studios/" + studio.getId()).with(user(bob.getEmail())))
                .andExpect(content().string(not(containsString(formPath))));
        mvc.perform(get(formPath).with(user(alice.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Request a quote")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/quotes").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("sentQuotes", hasSize(0)))
                .andExpect(model().attribute("receivedQuotes", hasSize(0)));
        mvc.perform(get("/dashboard").with(user(alice.getEmail())))
                .andExpect(content().string(containsString("href=\"/quotes\"")));
    }

    @Test
    void persistsRequestWithTrustedParticipantsDatesAndExactTotalBudget() throws Exception {
        LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(2);
        LocalDate end = start.plusDays(1);
        mvc.perform(post("/studios/" + studio.getId() + "/quotes").with(user(alice.getEmail())).with(csrf())
                        .param("title", "  Mon EP  ").param("description", "  Recording <script>hello</script> ")
                        .param("startDate", start.toString()).param("endDate", end.toString()).param("budget", " 750,50 ")
                        .param("requesterId", mallory.getId().toString()).param("studioId", "999999")
                        .param("status", "ANSWERED").param("reply", "Forged reply"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("success", "quote.sent"));
        quotes.flush();
        entityManager.clear();
        StudioQuote quote = quotes.findByRequesterIdOrderByIdDesc(alice.getId()).getFirst();
        assertThat(quote.getTitle()).isEqualTo("Mon EP");
        assertThat(quote.getDescription()).isEqualTo("Recording <script>hello</script>");
        assertThat(quote.getStudio().getId()).isEqualTo(studio.getId());
        assertThat(quote.getStartDate()).isEqualTo(start);
        assertThat(quote.getEndDate()).isEqualTo(end);
        assertThat(quote.getBudget()).isEqualByComparingTo("750.50");
        assertThat(quote.getStatus()).isEqualTo(StudioQuote.Status.PENDING);
        assertThat(quote.getReply()).isNull();
        assertThat(quotes.findByRequesterIdOrderByIdDesc(mallory.getId())).isEmpty();
        mvc.perform(get("/quotes/" + quote.getId()).with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("&lt;script&gt;hello&lt;/script&gt;")))
                .andExpect(content().string(containsString("750,50 €")))
                .andExpect(content().string(not(containsString(bob.getEmail()))));
    }

    @Test
    void managerCanSendSuccessiveRepliesAndRequesterCanReadTheirHistory() throws Exception {
        StudioQuote quote = request(alice, studio, "My EP");
        String path = "/quotes/" + quote.getId();
        mvc.perform(get(path).with(user(bob.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString(path + "/reply")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(get(path).with(user(alice.getEmail())))
                .andExpect(content().string(containsString("action=\"" + path + "/reply\"")))
                .andExpect(content().string(not(containsString("name=\"amount\""))));
        mvc.perform(post(path + "/reply").with(user(bob.getEmail())).with(csrf())
                        .param("message", "  Available <script>reply</script> ").param("amount", "650,25"))
                .andExpect(redirectedUrl(path)).andExpect(flash().attribute("success", "quote.replySent"));
        quotes.flush();
        entityManager.clear();
        StudioQuote saved = quotes.findById(quote.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(StudioQuote.Status.ANSWERED);
        assertThat(saved.getReply()).isEqualTo("Available <script>reply</script>");
        assertThat(saved.getProposedAmount()).isEqualByComparingTo("650.25");
        assertThat(saved.getAnsweredAt()).isNotNull();
        mvc.perform(get(path).with(user(alice.getEmail())))
                .andExpect(content().string(containsString("&lt;script&gt;reply&lt;/script&gt;")))
                .andExpect(content().string(containsString("650,25 €")));
        mvc.perform(post(path + "/reply").with(user(bob.getEmail())).with(csrf()).param("message", "A second proposal").param("amount", "600"))
                .andExpect(redirectedUrl(path));
        messages.flush();
        entityManager.clear();
        var history = messages.findByQuoteIdOrderByIdAsc(quote.getId());
        assertThat(history).extracting(StudioQuoteMessage::getBody).containsExactly("Available <script>reply</script>", "A second proposal");
        assertThat(history.getFirst().getProposedAmount()).isEqualByComparingTo("650.25");
        assertThat(history.getLast().getProposedAmount()).isEqualByComparingTo("600");
        mvc.perform(get(path).with(user(alice.getEmail())))
                .andExpect(content().string(containsString("650,25 €")))
                .andExpect(content().string(containsString("600,00 €")));
    }

    @Test
    void supportsFlexibleDatesAndOptionalBudgetAndReplyAmount() throws Exception {
        mvc.perform(post("/studios/" + studio.getId() + "/quotes").with(user(alice.getEmail())).with(csrf())
                        .param("title", "Flexible project").param("description", "We need mixing"))
                .andExpect(status().is3xxRedirection());
        StudioQuote quote = quotes.findByRequesterIdOrderByIdDesc(alice.getId()).getFirst();
        assertThat(quote.getBudget()).isNull();
        assertThat(quote.getStartDate()).isNull();
        assertThat(quote.getEndDate()).isNull();
        mvc.perform(get("/quotes/" + quote.getId()).with(user(alice.getEmail())))
                .andExpect(content().string(containsString("Dates à définir")));
        mvc.perform(post("/quotes/" + quote.getId() + "/reply").with(user(bob.getEmail())).with(csrf())
                        .param("message", "Let us discuss the details"))
                .andExpect(status().is3xxRedirection());
        assertThat(quote.getProposedAmount()).isNull();
        assertThat(quote.getStatus()).isEqualTo(StudioQuote.Status.ANSWERED);
    }

    @Test
    void acceptsTodaySameDayAndZeroBudget() throws Exception {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        mvc.perform(post("/studios/" + studio.getId() + "/quotes").with(user(alice.getEmail())).with(csrf())
                        .param("title", "Today").param("description", "A project")
                        .param("startDate", today).param("endDate", today).param("budget", "0"))
                .andExpect(status().is3xxRedirection());
        StudioQuote quote = quotes.findByRequesterIdOrderByIdDesc(alice.getId()).getFirst();
        assertThat(quote.getBudget()).isEqualByComparingTo("0");
        assertThat(quote.getStartDate()).isEqualTo(quote.getEndDate());
    }

    @Test
    void rejectsInvalidProjectsDatesAndAmountsAndKeepsFormValues() throws Exception {
        for (var entry : List.of(Map.entry("title", " "), Map.entry("title", "x".repeat(151)),
                Map.entry("description", " "), Map.entry("description", "x".repeat(5001)))) {
            invalid(entry.getKey(), entry.getValue(), "quote.projectError");
        }
        for (String date : List.of("invalid", "2026-02-30", "0000-01-01", "99999-01-01", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString())) {
            invalid("startDate", date, "quote.datesError");
        }
        invalid("endDate", LocalDate.now(ZoneOffset.UTC).plusDays(2).toString(), "quote.datesError");
        mvc.perform(post("/studios/" + studio.getId() + "/quotes").with(user(alice.getEmail())).with(csrf())
                        .param("title", "Title").param("description", "Description")
                        .param("startDate", LocalDate.now(ZoneOffset.UTC).plusDays(3).toString())
                        .param("endDate", LocalDate.now(ZoneOffset.UTC).plusDays(2).toString()))
                .andExpect(model().attribute("error", "quote.datesError"));
        for (String amount : List.of("-1", "NaN", "1e2", "1.234", "1000000", "9".repeat(100))) {
            invalid("budget", amount, "quote.amountError");
        }
        assertThat(quotes.count()).isZero();
    }

    @Test
    void invalidReplyLeavesRequestPendingAndPreservesText() throws Exception {
        StudioQuote quote = request(alice, studio, "Project");
        for (String message : List.of(" ", "x".repeat(5001))) {
            mvc.perform(post("/quotes/" + quote.getId() + "/reply").with(user(bob.getEmail())).with(csrf()).param("message", message))
                    .andExpect(status().isOk()).andExpect(model().attribute("error", "quote.replyError"));
        }
        for (String amount : List.of("-1", "NaN", "1e2", "1.234", "1000000")) {
            mvc.perform(post("/quotes/" + quote.getId() + "/reply").with(user(bob.getEmail())).with(csrf())
                            .param("message", "Keep this reply").param("amount", amount))
                    .andExpect(status().isOk()).andExpect(model().attribute("error", "quote.amountError"))
                    .andExpect(content().string(containsString("Keep this reply")));
        }
        assertThat(quote.getReply()).isNull();
        assertThat(quote.getProposedAmount()).isNull();
        assertThat(quote.getStatus()).isEqualTo(StudioQuote.Status.PENDING);
    }

    @Test
    void inboxesAreRestrictedToSenderAndManagersOwnStudios() throws Exception {
        Studio second = studio(bob, "Second Bob studio");
        Studio other = studio(mallory, "Other studio");
        request(alice, studio, "First");
        request(mallory, second, "Second");
        request(alice, other, "Third");
        mvc.perform(get("/quotes").with(user(alice.getEmail())))
                .andExpect(model().attribute("sentQuotes", hasSize(2))).andExpect(model().attribute("receivedQuotes", hasSize(0)))
                .andExpect(content().string(not(containsString("Second Bob studio"))));
        mvc.perform(get("/quotes").with(user(bob.getEmail())))
                .andExpect(model().attribute("sentQuotes", hasSize(0))).andExpect(model().attribute("receivedQuotes", hasSize(2)))
                .andExpect(content().string(not(containsString("Other studio"))));
        mvc.perform(get("/quotes").with(user(mallory.getEmail())))
                .andExpect(model().attribute("sentQuotes", hasSize(1))).andExpect(model().attribute("receivedQuotes", hasSize(1)));
    }

    @Test
    void outsidersCannotReadOrReplyAndRequesterCannotReplyAsStudio() throws Exception {
        StudioQuote quote = request(alice, studio, "Private project");
        String path = "/quotes/" + quote.getId();
        mvc.perform(get(path).with(user(mallory.getEmail()))).andExpect(status().isNotFound());
        for (User actor : List.of(mallory)) {
            mvc.perform(post(path + "/reply").with(user(actor.getEmail())).with(csrf()).param("message", "Forged reply"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(post(path + "/reply").with(user(alice.getEmail())).with(csrf())
                        .param("message", "Forged offer").param("amount", "500"))
                .andExpect(model().attribute("error", "quote.studioAmountOnly"));
        assertThat(messages.findByQuoteIdOrderByIdAsc(quote.getId())).isEmpty();
        for (String suffix : List.of("", "/reply")) {
            if (suffix.isEmpty()) mvc.perform(get("/quotes/9223372036854775807").with(user(alice.getEmail()))).andExpect(status().isNotFound());
            else mvc.perform(post("/quotes/9223372036854775807/reply").with(user(bob.getEmail())).with(csrf())).andExpect(status().isNotFound());
        }
        assertThat(quote.getStatus()).isEqualTo(StudioQuote.Status.PENDING);
    }

    @Test
    void requiresLoginCsrfAndAnActiveOtherStudioManager() throws Exception {
        StudioQuote quote = request(alice, studio, "Project");
        String createPath = "/studios/" + studio.getId() + "/quotes";
        for (String path : List.of("/quotes", "/quotes/" + quote.getId(), createPath + "/new")) {
            mvc.perform(get(path)).andExpect(redirectedUrl("/login"));
        }
        mvc.perform(post(createPath).with(csrf())).andExpect(redirectedUrl("/login"));
        mvc.perform(post(createPath).with(user(alice.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(post("/quotes/" + quote.getId() + "/reply").with(user(bob.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(get(createPath + "/new").with(user(bob.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(post(createPath).with(user(bob.getEmail())).with(csrf())).andExpect(status().isNotFound());
        bob.setEnabled(false);
        mvc.perform(get(createPath + "/new").with(user(alice.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(post(createPath).with(user(alice.getEmail())).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/quotes/" + quote.getId() + "/reply").with(user(bob.getEmail())).with(csrf())).andExpect(status().isUnauthorized());
    }


    @Test
    void requesterCanClarifyProjectWithTrustedAuthorshipAndReopenStudioAction() throws Exception {
        StudioQuote quote = request(alice, studio, "Project");
        String path = "/quotes/" + quote.getId();
        mvc.perform(post(path + "/reply").with(user(bob.getEmail())).with(csrf())
                        .param("message", "An initial offer").param("amount", "800"))
                .andExpect(redirectedUrl(path));
        mvc.perform(post(path + "/reply").with(user(alice.getEmail())).with(csrf())
                        .param("message", "  Could we add <script>vocals</script>?  ")
                        .param("authorId", bob.getId().toString()).param("quoteId", "999")
                        .param("status", "ANSWERED").param("proposedAmount", "1"))
                .andExpect(redirectedUrl(path));
        messages.flush();
        entityManager.clear();
        var history = messages.findByQuoteIdOrderByIdAsc(quote.getId());
        assertThat(history).hasSize(2);
        assertThat(history.getLast().getAuthor().getId()).isEqualTo(alice.getId());
        assertThat(history.getLast().getBody()).isEqualTo("Could we add <script>vocals</script>?");
        assertThat(history.getLast().getProposedAmount()).isNull();
        assertThat(history.getLast().isFromStudio()).isFalse();
        assertThat(history.getFirst().isFromStudio()).isTrue();
        assertThat(quotes.findById(quote.getId()).orElseThrow().getStatus()).isEqualTo(StudioQuote.Status.PENDING);
        mvc.perform(get("/dashboard").with(user(bob.getEmail())))
                .andExpect(model().attribute("toAnswerCount", 1L));
        mvc.perform(get("/dashboard").with(user(alice.getEmail())))
                .andExpect(model().attribute("answersCount", 0L));
        mvc.perform(get(path).with(user(bob.getEmail())))
                .andExpect(content().string(containsString("&lt;script&gt;vocals&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>vocals</script>"))));
        mvc.perform(post(path + "/reply").with(user(bob.getEmail())).with(csrf())
                        .param("message", "Yes, with vocals").param("amount", "950"))
                .andExpect(redirectedUrl(path));
        assertThat(messages.findByQuoteIdOrderByIdAsc(quote.getId())).hasSize(3);
        mvc.perform(get("/dashboard").with(user(bob.getEmail())))
                .andExpect(model().attribute("toAnswerCount", 0L));
        mvc.perform(get("/dashboard").with(user(alice.getEmail())))
                .andExpect(model().attribute("answersCount", 1L));
    }

    @Test
    void legacyReplyIsVisibleWithoutWritingAndArchivedExactlyOnceWhenDiscussionContinues() throws Exception {
        StudioQuote quote = request(alice, studio, "Legacy project");
        quote.answer("Original studio offer", new java.math.BigDecimal("725.50"));
        quotes.flush();
        var originalTime = quote.getAnsweredAt();
        String path = "/quotes/" + quote.getId();
        mvc.perform(get(path).with(user(alice.getEmail())))
                .andExpect(content().string(containsString("Original studio offer")))
                .andExpect(content().string(containsString("725,50 €")));
        assertThat(messages.findByQuoteIdOrderByIdAsc(quote.getId())).isEmpty();
        mvc.perform(post(path + "/reply").with(user(alice.getEmail())).with(csrf())
                        .param("message", "Can we record on Sunday?"))
                .andExpect(redirectedUrl(path));
        mvc.perform(post(path + "/reply").with(user(bob.getEmail())).with(csrf())
                        .param("message", "Yes, revised offer").param("amount", "700"))
                .andExpect(redirectedUrl(path));
        messages.flush();
        entityManager.clear();
        var history = messages.findByQuoteIdOrderByIdAsc(quote.getId());
        assertThat(history).extracting(StudioQuoteMessage::getBody)
                .containsExactly("Original studio offer", "Can we record on Sunday?", "Yes, revised offer");
        assertThat(history.getFirst().getCreatedAt()).isCloseTo(originalTime, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS));
        assertThat(history.getFirst().getProposedAmount()).isEqualByComparingTo("725.50");
        assertThat(history.getLast().getProposedAmount()).isEqualByComparingTo("700");
    }

    @Test
    void managerCanContinueLegacyReplyWithoutReplacingItsHistory() throws Exception {
        StudioQuote quote = request(alice, studio, "Legacy project");
        quote.answer("Old reply", null);
        quotes.flush();
        mvc.perform(post("/quotes/" + quote.getId() + "/reply").with(user(bob.getEmail())).with(csrf())
                        .param("message", "New reply"))
                .andExpect(status().is3xxRedirection());
        assertThat(messages.findByQuoteIdOrderByIdAsc(quote.getId())).extracting(StudioQuoteMessage::getBody)
                .containsExactly("Old reply", "New reply");
    }

    @Test
    void requesterMaySendFirstMessageButInvalidMessagesDoNotChangeAnsweredStatusOrHistory() throws Exception {
        StudioQuote quote = request(alice, studio, "Project");
        String path = "/quotes/" + quote.getId();
        mvc.perform(post(path + "/reply").with(user(alice.getEmail())).with(csrf())
                        .param("message", "One more detail"))
                .andExpect(redirectedUrl(path));
        mvc.perform(post(path + "/reply").with(user(bob.getEmail())).with(csrf())
                        .param("message", "Noted"))
                .andExpect(redirectedUrl(path));
        for (String message : List.of(" ", "x".repeat(5001))) {
            mvc.perform(post(path + "/reply").with(user(alice.getEmail())).with(csrf()).param("message", message))
                    .andExpect(model().attribute("error", "quote.replyError"));
        }
        assertThat(quote.getStatus()).isEqualTo(StudioQuote.Status.ANSWERED);
        assertThat(messages.findByQuoteIdOrderByIdAsc(quote.getId())).hasSize(2);
        mvc.perform(get(path).with(user(alice.getEmail())).param("lang", "en"))
                .andExpect(content().string(containsString("Conversation and proposals")))
                .andExpect(content().string(containsString("Continue the conversation")))
                .andExpect(content().string(not(containsString("??"))));
    }

    private void invalid(String field, String value, String error) throws Exception {
        var form = new java.util.HashMap<>(Map.of("title", "My project", "description", "Details to keep"));
        form.put(field, value);
        var request = post("/studios/" + studio.getId() + "/quotes").with(user(alice.getEmail())).with(csrf());
        form.forEach(request::param);
        mvc.perform(request).andExpect(status().isOk()).andExpect(model().attribute("error", error))
                .andExpect(model().attribute("form", hasEntry(field, value.strip())))
                .andExpect(content().string(containsString("role=\"alert\"")));
    }

    private StudioQuote request(User actor, Studio target, String title) {
        return quotes.saveAndFlush(new StudioQuote(target, actor, title, "Private project details", null, null, null));
    }

    private Studio studio(User owner, String name) {
        Studio value = new Studio(owner);
        value.setName(name);
        return studios.saveAndFlush(value);
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
