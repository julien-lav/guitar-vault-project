package com.guitarvault;

import java.nio.file.*;
import java.util.*;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class HelpTests {
    @Autowired WebApplicationContext context;
    @Autowired HelpService help;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test void publicIndexUsesTheSelectedLanguageAndNavigationWorksWithoutSigningIn() throws Exception {
        mvc.perform(get("/help")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Aide et guides")))
                .andExpect(content().string(containsString("Premiers pas")))
                .andExpect(content().string(containsString("/help/faq")));
        var result = mvc.perform(get("/help?lang=en")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Help &amp; guides")))
                .andExpect(content().string(containsString("Getting started")))
                .andExpect(content().string(not(containsString("??"))))
                .andExpect(cookie().value("guitar-vault-language", "en")).andReturn();
        mvc.perform(get("/help/documents").cookie(result.getResponse().getCookie("guitar-vault-language")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Documents, labels and archives")))
                .andExpect(content().string(containsString("/help/documents?lang=fr")));
        mvc.perform(get("/login")).andExpect(content().string(containsString("href=\"/help\"")));
    }
    @Test void everyGuideAndDownloadUseTheExactGitSourceInBothLanguages() throws Exception {
        for (String language : List.of("fr", "en")) {
            var locale = Locale.forLanguageTag(language);
            assertThat(help.all(locale)).hasSize(7);
            for (var guide : help.all(locale)) {
                String source = Files.readString(Path.of("docs/user-guide", language, guide.slug() + ".md"));
                assertThat(guide.markdown()).isEqualTo(source);
                assertThat(guide.title()).isNotBlank(); assertThat(guide.summary()).isNotBlank();
                mvc.perform(get("/help/" + guide.slug()).param("lang", language))
                        .andExpect(status().isOk()).andExpect(content().string(containsString("class=\"help-prose\"")))
                        .andExpect(content().string(not(containsString("??"))));
                mvc.perform(get("/help/" + guide.slug() + "/source").param("lang", language))
                        .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/markdown"))
                        .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + guide.slug() + ".md\""))
                        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                        .andExpect(content().bytes(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
    }
    @Test void markdownLinksWorkInGitAndAreRewrittenToExistingHelpPages() {
        for (String language : List.of("fr", "en")) {
            for (var guide : help.all(Locale.forLanguageTag(language))) {
                var document = Parser.builder().build().parse(guide.markdown());
                document.accept(new AbstractVisitor() {
                    @Override public void visit(Link link) {
                        String target = link.getDestination();
                        if (!target.startsWith("https://")) {
                            assertThat(target).endsWith(".md");
                            String slug = target.substring(0, target.length() - 3);
                            assertThat(help.guide(slug, Locale.forLanguageTag(language))).isNotNull();
                            assertThat(guide.html()).contains("href=\"/help/" + slug + "?lang=" + language + "\"");
                        }
                        visitChildren(link);
                    }
                });
            }
        }
    }
    @Test void rawHtmlAndDangerousUrlsCannotBecomeExecutableMarkup() {
        var guide = HelpService.render("example", "# Example\n\n<script>alert(1)</script>\n\n[click](javascript:alert%281%29)\n\n**Read** the `code`.\n\n1. First\n2. Second\n", "en");
        assertThat(guide.html()).doesNotContain("<script>", "href=\"javascript:", "<h1>")
                .contains("&lt;script&gt;", "<strong>Read</strong>", "<code>code</code>", "<ol>");
    }
    @Test void unknownGuidesAndNonGuideFilesAreNeverExposed() throws Exception {
        for (String slug : List.of("application.properties", "README.md", "unknown", "documents.md")) {
            mvc.perform(get("/help/" + slug)).andExpect(status().isNotFound());
            mvc.perform(get("/help/" + slug + "/source")).andExpect(status().isNotFound());
        }
        mvc.perform(get("/documents")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/profile")).andExpect(redirectedUrl("/login"));
    }
}
