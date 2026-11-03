package com.guitarvault.fixtures;

import java.nio.file.Path;

public final class FixtureTarget {
    private FixtureTarget() { }

    public static void validate(String url, boolean production) {
        if (production) throw new IllegalStateException("Le profil fixtures ne peut pas être associé à prod ou production.");
        if (url != null && url.startsWith("jdbc:h2:mem:")) return;
        String prefix = "jdbc:h2:file:";
        if (url != null && url.startsWith(prefix)) {
            String file = url.substring(prefix.length()).split(";", 2)[0];
            Path target = Path.of(file).toAbsolutePath().normalize();
            Path demo = Path.of("data/guitarvault-demo").toAbsolutePath().normalize();
            if (target.equals(demo)) return;
        }
        throw new IllegalStateException("Fixtures refusées : utiliser la base dédiée data/guitarvault-demo ou une base H2 en mémoire.");
    }
}
