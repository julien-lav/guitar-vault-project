package com.guitarvault;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("title", "page.home");
        model.addAttribute("message", "home.welcome");
        model.addAttribute("contentTemplate", "homepage");
        return "layout";
    }
}
