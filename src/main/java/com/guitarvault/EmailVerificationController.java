package com.guitarvault;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.ui.Model;
import org.springframework.mail.MailException;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class EmailVerificationController {
    private final RegistrationService registration;

    public EmailVerificationController(RegistrationService registration) {
        this.registration = registration;
    }

    @GetMapping("/resend-verification")
    public String resendForm(Model model) {
        model.addAttribute("title", "resend.title");
        model.addAttribute("contentTemplate", "resend-verification");
        return "layout";
    }

    @PostMapping("/resend-verification")
    public String resend(@RequestParam(defaultValue = "") String email, RedirectAttributes redirect) {
        try {
            registration.resend(email);
        } catch (MailException exception) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Verification email redelivery failed.");
        }
        redirect.addFlashAttribute("success", "resend.sent");
        if (email.strip().length() <= 254) {
            redirect.addFlashAttribute("email", email.strip());
        }
        return "redirect:/resend-verification";
    }

    @GetMapping("/verify-email")
    public String verify(@RequestParam(required = false) String token, RedirectAttributes redirect) {
        if (registration.verify(token)) {
            redirect.addFlashAttribute("success", "verify.success");
        } else {
            redirect.addFlashAttribute("verificationError", "verify.invalid");
        }
        return "redirect:/login";
    }
}
