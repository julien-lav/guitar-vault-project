package com.guitarvault;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class HelpController {
    private final HelpService help;
    public HelpController(HelpService help) { this.help = help; }

    @GetMapping("/help")
    public String index(Locale locale, Model model) {
        model.addAttribute("guides", help.all(locale));
        model.addAttribute("title", "help.title");
        model.addAttribute("contentTemplate", "help-index");
        return "layout";
    }
    @GetMapping("/help/{slug}")
    public String guide(@PathVariable String slug, Locale locale, Model model) {
        var guide = help.guide(slug, locale);
        model.addAttribute("guide", guide);
        model.addAttribute("guides", help.all(locale));
        model.addAttribute("pageTitle", guide.title() + " — Guitar Vault");
        model.addAttribute("pageDescription", guide.summary());
        model.addAttribute("contentTemplate", "help-guide");
        return "layout";
    }
    @GetMapping("/help/{slug}/source")
    public ResponseEntity<byte[]> source(@PathVariable String slug, Locale locale) {
        var guide = help.guide(slug, locale);
        return ResponseEntity.ok().contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(guide.slug() + ".md").build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(guide.markdown().getBytes(StandardCharsets.UTF_8));
    }
}
