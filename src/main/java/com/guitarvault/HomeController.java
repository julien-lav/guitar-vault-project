package com.guitarvault;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("title", "Guitar Vault");
        model.addAttribute("message", "Bienvenue dans Guitar Vault");
        model.addAttribute("contentTemplate", "homepage");
        return "layout";
    }
}
