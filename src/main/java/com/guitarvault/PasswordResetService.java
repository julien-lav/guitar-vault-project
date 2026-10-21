package com.guitarvault;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetService {
    private final UserRepository users;
    private final MailMessages mail;
    private final PasswordEncoder encoder;
    private final SessionRegistry sessions;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(UserRepository users, MailMessages mail, PasswordEncoder encoder, SessionRegistry sessions) {
        this.users = users;
        this.mail = mail;
        this.encoder = encoder;
        this.sessions = sessions;
    }

    @Transactional
    public void request(String email) {
        if (email == null || email.length() > 254) { return; }
        User user = users.findByEmailForUpdate(email.strip()).filter(User::isEnabled).orElse(null);
        if (user == null || (user.getPasswordResetRequestedAt() != null
                && user.getPasswordResetRequestedAt().isAfter(Instant.now().minusSeconds(60)))) { return; }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        user.setPasswordResetHash(RegistrationService.hash(token));
        user.setPasswordResetExpiresAt(Instant.now().plus(30, ChronoUnit.MINUTES));
        user.setPasswordResetRequestedAt(Instant.now());
        mail.send(user, "reset", "/reset-password", token);
    }

    @Transactional
    public boolean valid(String token) {
        return findValid(token) != null;
    }

    @Transactional
    public boolean reset(String token, String password, String confirmation) {
        if (PasswordPolicy.validate(password, confirmation) != null) { return false; }
        User user = findValid(token);
        if (user == null) { return false; }
        user.setPasswordHash(encoder.encode(password));
        user.setPasswordResetHash(null);
        user.setPasswordResetExpiresAt(null);
        sessions.getAllPrincipals().stream()
                .filter(principal -> principal instanceof UserDetails details && details.getUsername().equalsIgnoreCase(user.getEmail()))
                .forEach(principal -> sessions.getAllSessions(principal, false).forEach(session -> session.expireNow()));
        return true;
    }

    private User findValid(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) { return null; }
        return users.findByPasswordResetHash(RegistrationService.hash(token))
                .filter(User::isEnabled)
                .filter(user -> user.getPasswordResetExpiresAt() != null && user.getPasswordResetExpiresAt().isAfter(Instant.now()))
                .orElse(null);
    }
}
