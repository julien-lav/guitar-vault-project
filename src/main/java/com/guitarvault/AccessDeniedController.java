package com.guitarvault;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class AccessDeniedController {
    @RequestMapping("/access-denied")
    public String denied(HttpServletRequest request, HttpServletResponse response, Model model) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        boolean expiredForm = request.getAttribute(WebAttributes.ACCESS_DENIED_403) instanceof CsrfException;
        model.addAttribute("expiredForm", expiredForm);
        // Only known local pages are recovery destinations; never reuse a submitted URL.
        String original = (String) request.getAttribute(jakarta.servlet.RequestDispatcher.FORWARD_REQUEST_URI);
        String context = request.getContextPath();
        if (original != null && !context.isEmpty() && original.startsWith(context + "/")) {
            original = original.substring(context.length());
        }
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Access denied: {} {} ({})",
                request.getMethod(), original == null ? "/access-denied" : original,
                expiredForm ? "invalid form token" : "insufficient permissions");
        String recovery = switch (original == null ? "" : original) {
            case "/signup" -> "/signup";
            case "/login", "/logout" -> "/login";
            case "/resend-verification" -> "/resend-verification";
            case "/forgot-password", "/reset-password" -> "/forgot-password";
            default -> original != null && original.startsWith("/admin/") ? "/admin/articles" : "/dashboard";
        };
        model.addAttribute("recoveryUrl", recovery);
        model.addAttribute("title", "access.title");
        model.addAttribute("contentTemplate", "access-denied");
        return "layout";
    }
}
