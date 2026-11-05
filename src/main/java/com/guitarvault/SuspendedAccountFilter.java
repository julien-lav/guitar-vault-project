package com.guitarvault;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class SuspendedAccountFilter extends OncePerRequestFilter {
    private final UserRepository users;
    SuspendedAccountFilter(UserRepository users) { this.users = users; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)
                && users.existsByEmailIgnoreCaseAndSuspendedTrue(authentication.getName())) {
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false); if (session != null) session.invalidate();
            response.sendRedirect(request.getContextPath() + "/login?suspended"); return;
        }
        chain.doFilter(request, response);
    }
}
