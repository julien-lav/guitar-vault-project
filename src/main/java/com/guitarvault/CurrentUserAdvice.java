package com.guitarvault;

import java.security.Principal;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UriComponentsBuilder;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class CurrentUserAdvice {
    @ModelAttribute("isAdmin")
    public boolean isAdmin(org.springframework.security.core.Authentication authentication) {
        return ArticlePermissions.canPublish(authentication);
    }

    @ModelAttribute("canWriteArticles")
    public boolean canWriteArticles(org.springframework.security.core.Authentication authentication) {
        return ArticlePermissions.canWrite(authentication);
    }

    @ModelAttribute("optionKeys")
    public Map<String, String> optionKeys() {
        return Map.ofEntries(
                Map.entry("Actif", "status.active"), Map.entry("À réparer", "status.repair"),
                Map.entry("En vente", "status.sale"), Map.entry("Guitare", "choice.guitar"),
                Map.entry("Basse", "choice.bass"), Map.entry("Ukulélé", "choice.ukulele"),
                Map.entry("Autre", "choice.other"), Map.entry("Débutant", "choice.beginner"),
                Map.entry("Intermédiaire", "choice.intermediate"), Map.entry("Avancé", "choice.advanced"));
    }

    @ModelAttribute("languageUrls")
    public Map<String, String> languageUrls(HttpServletRequest request) {
        return Map.of("fr", languageUrl(request, "fr"), "en", languageUrl(request, "en"));
    }

    private String languageUrl(HttpServletRequest request, String language) {
        var builder = UriComponentsBuilder.fromPath(request.getRequestURI());
        if (request.getRequestURI().endsWith("/access-denied")) {
            return builder.queryParam("lang", language).build().encode().toUriString();
        }
        if ("GET".equals(request.getMethod())) {
            request.getParameterMap().forEach((key, values) -> {
                if (!java.util.Set.of("password", "confirmPassword", "_csrf").contains(key)) {
                    builder.queryParam(key, (Object[]) values);
                }
            });
        } else {
            builder.query(request.getQueryString());
            if (request.getRequestURI().endsWith("/reset-password") && request.getParameter("token") != null) {
                builder.replaceQueryParam("token", request.getParameter("token"));
            }
        }
        return builder.replaceQueryParam("lang", language).build().encode().toUriString();
    }

    @ModelAttribute("currentUsername")
    public String currentUsername(Principal principal) {
        return principal == null ? null : principal.getName();
    }
}
