package com.guitarvault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {
    private final UserRepository users;
    private final MailMessages mail;
    private final SecureRandom random = new SecureRandom();

    public RegistrationService(UserRepository users, MailMessages mail) {
        this.users = users;
        this.mail = mail;
    }

    @Transactional
    public void register(User user) {
        user.setRoles(java.util.Set.of(Role.ROLE_USER));
        user.setEnabled(false);
        issueVerification(user);
    }

    @Transactional
    public void resend(String email) {
        if (email == null || email.strip().length() > 254) { return; }
        User user = users.findByEmailForUpdate(email.strip())
                .filter(account -> !account.isEnabled() && account.getVerificationTokenHash() != null)
                .orElse(null);
        if (user == null || (user.getVerificationRequestedAt() != null
                && user.getVerificationRequestedAt().isAfter(Instant.now().minusSeconds(60)))) { return; }
        issueVerification(user);
    }

    private void issueVerification(User user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        user.setVerificationTokenHash(hash(token));
        user.setVerificationExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        user.setVerificationRequestedAt(Instant.now());
        users.saveAndFlush(user);
        // Sending errors roll back both the account and token updates.
        mail.send(user, "verify", "/verify-email", token);
    }

    @Transactional
    public boolean verify(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            return false;
        }
        User user = users.findByVerificationTokenHash(hash(token)).orElse(null);
        if (user == null || user.getVerificationExpiresAt() == null
                || !user.getVerificationExpiresAt().isAfter(Instant.now())) {
            return false;
        }
        user.setEnabled(true);
        user.setVerificationTokenHash(null);
        user.setVerificationExpiresAt(null);
        return true;
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
