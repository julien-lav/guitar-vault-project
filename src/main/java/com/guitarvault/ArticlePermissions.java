package com.guitarvault;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

public final class ArticlePermissions {
    private ArticlePermissions() { }

    public static boolean canPublish(Authentication authentication) {
        return hasRole(authentication, Role.ROLE_ADMIN) || hasRole(authentication, Role.ROLE_SUPER_ADMIN);
    }

    public static boolean canWrite(Authentication authentication) {
        return canPublish(authentication) || hasRole(authentication, Role.ROLE_EDITOR);
    }

    public static void requireEditable(Article article, Authentication authentication) {
        if (!canPublish(authentication) && (!canWrite(authentication) || article.isPublished()
                || !article.getAuthor().getEmail().equalsIgnoreCase(authentication.getName()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private static boolean hasRole(Authentication authentication, Role role) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals(role.name()));
    }
}
