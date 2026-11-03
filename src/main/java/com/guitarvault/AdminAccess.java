package com.guitarvault;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Administrator access is assigned by the operator, never by a signup field. */
@Component
public class AdminAccess {
    private final Set<String> emails;

    public AdminAccess(@Value("${app.admin-emails:}") String emails) {
        this.emails = Arrays.stream(emails.split(",")).map(String::strip)
                .filter(email -> !email.isEmpty()).map(email -> email.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean allows(User user) {
        Set<Role> roles = roles(user);
        return user.isEnabled() && (roles.contains(Role.ROLE_ADMIN) || roles.contains(Role.ROLE_SUPER_ADMIN));
    }

    public Set<Role> roles(User user) {
        var result = java.util.EnumSet.of(Role.ROLE_USER);
        result.addAll(user.getRoles());
        if (user.isEnabled() && emails.contains(user.getEmail().toLowerCase(Locale.ROOT))) {
            result.add(Role.ROLE_ADMIN);
        }
        return Set.copyOf(result);
    }
}
