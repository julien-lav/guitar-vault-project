package com.guitarvault;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {
    private PasswordPolicy() { }

    public static String validate(String password, String confirmation) {
        if (password == null || password.length() < 8 || !password.matches("(?s).*[\\p{P}\\p{S}].*")) {
            return "password.invalid";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return "password.tooLong";
        }
        return password.equals(confirmation) ? null : "password.mismatch";
    }
}
