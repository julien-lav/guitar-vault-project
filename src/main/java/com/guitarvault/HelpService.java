package com.guitarvault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Only reviewed, packaged user guides are public; no request value becomes a resource path. */
@Service
public class HelpService {
    static final List<String> SLUGS = List.of("getting-started", "instruments", "documents", "luthiers",
            "transfers", "bands-and-studios", "faq");
    public record Guide(String slug, String title, String summary, String html, String markdown) { }
    private final Map<String, List<Guide>> guides;

    public HelpService() {
        Map<String, List<Guide>> loaded = new HashMap<>();
        for (String language : List.of("fr", "en")) {
            loaded.put(language, SLUGS.stream().map(slug -> load(language, slug)).toList());
        }
        guides = Map.copyOf(loaded);
    }
    private static String language(Locale locale) { return "en".equals(locale.getLanguage()) ? "en" : "fr"; }
    public List<Guide> all(Locale locale) { return guides.get(language(locale)); }
    public Guide guide(String slug, Locale locale) {
        return all(locale).stream().filter(g -> g.slug().equals(slug)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    private Guide load(String language, String slug) {
        try {
            String source = new ClassPathResource("help/" + language + "/" + slug + ".md")
                    .getContentAsString(StandardCharsets.UTF_8);
            return render(slug, source, language);
        } catch (IOException e) {
            throw new IllegalStateException("Missing packaged user guide: " + language + "/" + slug, e);
        }
    }
    static Guide render(String slug, String source, String language) {
        Node document = Parser.builder().build().parse(source);
        if (!(document.getFirstChild() instanceof Heading heading) || heading.getLevel() != 1)
            throw new IllegalStateException("Guide must begin with a level-one title: " + slug);
        String title = plainText(heading);
        heading.unlink(); // The template provides the single page heading.
        String summary = document.getFirstChild() instanceof Paragraph paragraph ? plainText(paragraph) : "";
        document.accept(new AbstractVisitor() {
            @Override public void visit(Link link) {
                String destination = link.getDestination();
                // Relative Markdown links work in Git; the same links target Help pages on the site.
                for (String target : SLUGS) {
                    if (destination.equals(target + ".md")) {
                        link.setDestination("/help/" + target + "?lang=" + language);
                        break;
                    }
                }
                visitChildren(link);
            }
        });
        String html = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build().render(document);
        return new Guide(slug, title, summary, html, source);
    }
    private static String plainText(Node node) {
        StringBuilder text = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override public void visit(Text word) { text.append(word.getLiteral()); }
            @Override public void visit(Code code) { text.append(code.getLiteral()); }
            @Override public void visit(SoftLineBreak line) { text.append(' '); }
        });
        return text.toString();
    }
}
