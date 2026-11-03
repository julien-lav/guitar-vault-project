package com.guitarvault;

import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class ArticleController {
    private final ArticleService articles;
    public ArticleController(ArticleService articles) { this.articles = articles; }

    @GetMapping("/articles")
    public String index(@RequestParam(defaultValue = "") String q,
                        @RequestParam(defaultValue = "") String category,
                        @RequestParam(defaultValue = "0") int page, Model model) {
        category = ArticleService.CATEGORIES.contains(category) ? category : "";
        model.addAttribute("articlePage", articles.search(false, "published", category, q, page, 9));
        model.addAttribute("q", q.length() > 160 ? q.substring(0, 160) : q);
        model.addAttribute("category", category);
        model.addAttribute("categories", ArticleService.CATEGORIES);
        model.addAttribute("title", "page.articles");
        model.addAttribute("contentTemplate", "articles");
        return "layout";
    }

    @GetMapping("/articles/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Article article = articles.find(id, false);
        model.addAttribute("article", article);
        model.addAttribute("preview", false);
        model.addAttribute("pageTitle", article.getTitle() + " — Guitar Vault");
        model.addAttribute("pageDescription", article.getSummary());
        model.addAttribute("contentTemplate", "article-detail");
        return "layout";
    }

    @GetMapping("/articles/{id}/cover")
    public ResponseEntity<byte[]> cover(@PathVariable Long id) { return image(articles.cover(id, false)); }

    static ResponseEntity<byte[]> image(ArticleCover cover) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(cover.getContentType()))
                .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
                .body(cover.getData());
    }
}
