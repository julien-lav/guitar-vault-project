package com.guitarvault;

import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class PasswordResetController {
    private final PasswordResetService reset;

    public PasswordResetController(PasswordResetService reset) { this.reset = reset; }

    @GetMapping("/forgot-password")
    public String forgot(Model model) {
        model.addAttribute("title", "forgot.title");
        model.addAttribute("contentTemplate", "forgot-password");
        return "layout";
    }

    @PostMapping("/forgot-password")
    public String request(@RequestParam(defaultValue = "") String email, RedirectAttributes redirect) {
        try {
            reset.request(email);
        } catch (MailException exception) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Password reset email delivery failed.");
        }
        redirect.addFlashAttribute("success", "forgot.sent");
        if (email.strip().length() <= 254) {
            redirect.addFlashAttribute("email", email.strip());
        }
        return "redirect:/forgot-password";
    }

    @GetMapping("/reset-password")
    public String form(@RequestParam(required = false) String token, Model model) {
        model.addAttribute("title", "reset.title");
        model.addAttribute("contentTemplate", "reset-password");
        model.addAttribute("token", token);
        model.addAttribute("validToken", reset.valid(token));
        return "layout";
    }

    @PostMapping("/reset-password")
    public String submit(@RequestParam(defaultValue = "") String token,
                         @RequestParam(defaultValue = "") String password,
                         @RequestParam(defaultValue = "") String confirmPassword,
                         Model model, RedirectAttributes redirect) {
        String error = PasswordPolicy.validate(password, confirmPassword);
        if (error != null) {
            model.addAttribute("error", error);
            return form(token, model);
        }
        if (!reset.reset(token, password, confirmPassword)) {
            return form(token, model);
        }
        redirect.addFlashAttribute("success", "reset.success");
        return "redirect:/login";
    }
}
