package com.guitarvault;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class SignupController {
    private final UserRepository userRepository;
    private final RegistrationService registrationService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public SignupController(UserRepository userRepository, RegistrationService registrationService) {
        this.userRepository = userRepository;
        this.registrationService = registrationService;
    }

    @GetMapping("/signup")
    public String signupForm(Model model) {
        model.addAttribute("title", "page.signup");
        model.addAttribute("contentTemplate", "signup");
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", Map.of());
        }
        return "layout";
    }

    @PostMapping("/signup")
    public String signupSubmit(@RequestParam Map<String, String> params, Model model,
                               RedirectAttributes redirectAttributes) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String field : Set.of("fullName", "username", "email", "instrument", "level", "city", "country", "bio")) {
            form.put(field, params.getOrDefault(field, "").strip());
        }
        form.compute("email", (key, value) -> value.toLowerCase(Locale.ROOT));
        String password = params.getOrDefault("password", "");
        String error = validate(form, password, params.getOrDefault("confirmPassword", ""));
        if (error == null && userRepository.existsByEmailIgnoreCase(form.get("email"))) {
            error = "signup.emailUsed";
        }
        if (error == null && userRepository.existsByUsernameIgnoreCase(form.get("username"))) {
            error = "signup.usernameUsed";
        }
        if (error != null) {
            return showError(model, form, error);
        }

        User user = new User();
        user.setFullName(form.get("fullName"));
        user.setUsername(form.get("username"));
        user.setEmail(form.get("email"));
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setInstrument(form.get("instrument"));
        user.setLevel(form.get("level"));
        user.setCity(form.get("city"));
        user.setCountry(form.get("country"));
        user.setBio(form.get("bio"));
        try {
            registrationService.register(user);
        } catch (DataIntegrityViolationException exception) {
            return showError(model, form, "signup.saveError");
        } catch (org.springframework.mail.MailException exception) {
            return showError(model, form, "signup.mailError");
        }
        redirectAttributes.addFlashAttribute("success", "signup.success");
        return "redirect:/login";
    }

    private String showError(Model model, Map<String, String> form, String error) {
        model.addAttribute("form", form);
        model.addAttribute("error", error);
        return signupForm(model);
    }

    private String validate(Map<String, String> form, String password, String confirmation) {
        if (form.get("fullName").isBlank() || form.get("fullName").length() > 100) {
            return "signup.fullNameError";
        }
        if (!form.get("username").matches("[a-zA-Z0-9_-]{3,30}")) {
            return "signup.usernameError";
        }
        if (form.get("email").length() > 254 || !form.get("email").matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            return "signup.emailError";
        }
        String passwordError = PasswordPolicy.validate(password, confirmation);
        if (passwordError != null) {
            return passwordError;
        }
        if (!Set.of("", "Guitare", "Basse", "Ukulélé", "Autre").contains(form.get("instrument"))
                || !Set.of("", "Débutant", "Intermédiaire", "Avancé").contains(form.get("level"))) {
            return "signup.choiceError";
        }
        if (form.get("city").length() > 100 || form.get("country").length() > 100 || form.get("bio").length() > 1000) {
            return "signup.profileError";
        }
        return null;
    }
}
