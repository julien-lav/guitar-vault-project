package com.guitarvault;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    org.springframework.security.core.session.SessionRegistry sessionRegistry() {
        return new org.springframework.security.core.session.SessionRegistryImpl();
    }

    @Bean
    org.springframework.security.web.session.HttpSessionEventPublisher httpSessionEventPublisher() {
        return new org.springframework.security.web.session.HttpSessionEventPublisher();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(UserRepository users, AdminAccess admins) {
        return email -> {
            User user = users.findByEmailIgnoreCase(email.strip())
                    .orElseThrow(() -> new UsernameNotFoundException("Identifiants incorrects"));
            return org.springframework.security.core.userdetails.User
                    .withUsername(user.getEmail())
                    .password(user.getPasswordHash())
                    .authorities(admins.roles(user).stream().map(Role::name).toArray(String[]::new))
                    .disabled(!user.isEnabled())
                    .build();
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, UserRepository users) throws Exception {
        http.addFilterBefore(new SuspendedAccountFilter(users), org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        http.sessionManagement(session -> session.maximumSessions(-1).sessionRegistry(sessionRegistry())
                        .expiredUrl("/login?expired"))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/admin/moderation/reports/*/account/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/admin/articles/*/publish", "/admin/articles/*/unpublish", "/admin/articles/*/delete").hasAnyRole("SUPER_ADMIN", "ADMIN")
                        .requestMatchers("/admin", "/admin/articles", "/admin/articles/**").hasAnyRole("SUPER_ADMIN", "ADMIN", "EDITOR")
                        .requestMatchers("/admin/**").hasAnyRole("SUPER_ADMIN", "ADMIN")
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/help", "/help/**", "/articles", "/articles/**", "/luthiers", "/luthiers/*").permitAll()
                        .requestMatchers("/", "/signup", "/verify-email", "/resend-verification", "/login", "/error", "/access-denied", "/favicon.ico", "/api/hello", "/css/**", "/js/**", "/forgot-password", "/reset-password").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.accessDeniedPage("/access-denied"))
                .formLogin(form -> form.loginPage("/login")
                        .usernameParameter("email")
                        .defaultSuccessUrl("/dashboard", true)
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"));
        return http.build();
    }
}
