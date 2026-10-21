package com.guitarvault;

import java.time.Duration;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

@Configuration
public class LanguageConfig implements WebMvcConfigurer {
    @Bean
    LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver("guitar-vault-language");
        resolver.setDefaultLocale(Locale.FRENCH);
        resolver.setCookieMaxAge(Duration.ofDays(365));
        resolver.setCookieHttpOnly(true);
        return resolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String language = request.getParameter("lang");
                if ("GET".equals(request.getMethod()) && ("fr".equals(language) || "en".equals(language))) {
                    localeResolver().setLocale(request, response, Locale.forLanguageTag(language));
                }
                return true;
            }
        });
    }
}
