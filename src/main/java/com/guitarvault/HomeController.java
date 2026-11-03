package com.guitarvault;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
    private final ArticleService articles;

    public HomeController(ArticleService articles) { this.articles = articles; }

    @GetMapping("/admin")
    public String admin() { return "redirect:/admin/articles"; }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("articles", articles.search(false, "published", "", "", 0, 3).getContent());
        model.addAttribute("title", "page.home");
        model.addAttribute("message", "home.welcome");
        model.addAttribute("contentTemplate", "homepage");
        return "layout";
    }
}
