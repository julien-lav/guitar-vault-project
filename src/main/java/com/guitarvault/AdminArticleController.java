package com.guitarvault;

import org.springframework.security.core.Authentication;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/articles")
public class AdminArticleController {
    private final ArticleService articles;
    private final ArticleRepository repository;
    public AdminArticleController(ArticleService articles, ArticleRepository repository) {
        this.articles = articles; this.repository = repository;
    }

    @GetMapping
    public String index(@RequestParam(defaultValue = "") String q,
                        @RequestParam(defaultValue = "") String state,
                        @RequestParam(defaultValue = "") String category,
                        @RequestParam(defaultValue = "0") int page, Model model, Authentication authentication) {
        category = ArticleService.CATEGORIES.contains(category) ? category : "";
        state = java.util.List.of("published", "draft").contains(state) ? state : "";
        boolean publisher = ArticlePermissions.canPublish(authentication);
        if (!publisher) state = "draft";
        String authorEmail = publisher ? "" : authentication.getName();
        model.addAttribute("articlePage", articles.search(true, state, category, q, page, 12, authorEmail));
        model.addAttribute("q", q.length() > 160 ? q.substring(0, 160) : q);
        model.addAttribute("state", state); model.addAttribute("category", category);
        model.addAttribute("categories", ArticleService.CATEGORIES);
        model.addAttribute("publishedCount", publisher ? repository.countByPublished(true) : 0L);
        model.addAttribute("draftCount", publisher ? repository.countByPublished(false)
                : repository.countByAuthorEmailIgnoreCaseAndPublished(authorEmail, false));
        return view(model, "page.adminArticles", "admin-articles");
    }

    @GetMapping("/new")
    public String create(Model model) {
        return editor(null, Map.of("category", "guides", "version", "0", "action", "draft"), model);
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model, Authentication authentication) {
        Article article = articles.find(id, true);
        ArticlePermissions.requireEditable(article, authentication);
        return editor(id, Map.of("title", article.getTitle(), "summary", article.getSummary(),
                "body", article.getBody(), "category", article.getCategory(), "coverAlt", article.getCoverAlt(),
                "version", Long.toString(article.getVersion()), "action", article.isPublished() ? "publish" : "draft"), model);
    }

    @PostMapping
    public String create(@RequestParam Map<String, String> params,
                         @RequestParam(required = false) MultipartFile cover, Authentication authentication,
                         Model model, RedirectAttributes redirect) {
        return save(null, params, cover, authentication, model, redirect);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @RequestParam Map<String, String> params,
                         @RequestParam(required = false) MultipartFile cover, Authentication authentication,
                         Model model, RedirectAttributes redirect) {
        return save(id, params, cover, authentication, model, redirect);
    }

    private String save(Long id, Map<String, String> params, MultipartFile cover, Authentication authentication,
                        Model model, RedirectAttributes redirect) {
        if (id != null) ArticlePermissions.requireEditable(articles.find(id, true), authentication);
        if (!ArticlePermissions.canPublish(authentication) && !"draft".equals(params.get("action"))) {
            throw new org.springframework.security.access.AccessDeniedException("Only administrators can publish articles.");
        }
        try {
            Article article = articles.save(id, params, cover, authentication.getName(), ArticlePermissions.canPublish(authentication));
            redirect.addFlashAttribute("success", article.isPublished() ? "article.publishedSuccess" : "article.savedSuccess");
            return "redirect:/admin/articles/" + article.getId() + "/edit";
        } catch (ArticleService.InvalidArticle exception) {
            model.addAttribute("error", exception.getMessage());
        } catch (OptimisticLockingFailureException exception) {
            model.addAttribute("error", "article.error.conflict");
        }
        return editor(id, params, model);
    }

    @GetMapping("/{id}/preview")
    public String preview(@PathVariable Long id, Model model, Authentication authentication) {
        Article article = articles.find(id, true);
        ArticlePermissions.requireEditable(article, authentication);
        model.addAttribute("article", article);
        model.addAttribute("preview", true);
        return view(model, "page.articlePreview", "article-detail");
    }

    @GetMapping("/{id}/cover")
    public ResponseEntity<byte[]> cover(@PathVariable Long id, Authentication authentication) {
        ArticlePermissions.requireEditable(articles.find(id, true), authentication);
        return ArticleController.image(articles.cover(id, true));
    }

    @PostMapping("/{id}/publish")
    public String publish(@PathVariable Long id, @RequestParam String version, RedirectAttributes redirect) {
        return publication(id, version, true, redirect);
    }

    @PostMapping("/{id}/unpublish")
    public String unpublish(@PathVariable Long id, @RequestParam String version, RedirectAttributes redirect) {
        return publication(id, version, false, redirect);
    }

    private String publication(Long id, String version, boolean publish, RedirectAttributes redirect) {
        try {
            articles.publication(id, version, publish);
            redirect.addFlashAttribute("success", publish ? "article.publishedSuccess" : "article.unpublishedSuccess");
        } catch (ArticleService.InvalidArticle exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        } catch (OptimisticLockingFailureException exception) {
            redirect.addFlashAttribute("error", "article.error.conflict");
        }
        return "redirect:/admin/articles";
    }

    @GetMapping("/{id}/delete")
    public String confirmDelete(@PathVariable Long id, Model model) {
        model.addAttribute("article", articles.find(id, true));
        return view(model, "page.articleDelete", "article-delete");
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, @RequestParam String version, RedirectAttributes redirect) {
        try {
            articles.delete(id, version);
            redirect.addFlashAttribute("success", "article.deletedSuccess");
        } catch (ArticleService.InvalidArticle exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        } catch (OptimisticLockingFailureException exception) {
            redirect.addFlashAttribute("error", "article.error.conflict");
        }
        return "redirect:/admin/articles";
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String oversized(RedirectAttributes redirect) {
        redirect.addFlashAttribute("error", "article.error.upload");
        return "redirect:/admin/articles";
    }

    private String editor(Long id, Map<String, String> values, Model model) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String key : java.util.List.of("title", "summary", "body", "category", "coverAlt", "version", "action", "removeCover")) {
            form.put(key, values.getOrDefault(key, ""));
        }
        model.addAttribute("form", form); model.addAttribute("articleId", id);
        model.addAttribute("article", id == null ? null : articles.find(id, true));
        model.addAttribute("categories", ArticleService.CATEGORIES);
        return view(model, id == null ? "page.articleNew" : "page.articleEdit", "article-form");
    }

    private String view(Model model, String title, String template) {
        model.addAttribute("title", title); model.addAttribute("contentTemplate", template);
        return "layout";
    }
}
