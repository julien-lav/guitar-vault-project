package com.guitarvault.fixtures;

import com.guitarvault.*;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("fixtures")
public class AppFixtures implements ApplicationRunner {
    public static final String DATASET = "guitar-vault-demo-v1";
    private final UserRepository users;
    private final InstrumentRepository instruments;
    private final ArticleService articles;
    private final FixtureLoadRepository loads;
    private final PasswordEncoder encoder;
    private final Environment environment;
    private final String password;

    public AppFixtures(UserRepository users, InstrumentRepository instruments, ArticleService articles,
                       FixtureLoadRepository loads, PasswordEncoder encoder, Environment environment,
                       @Value("${app.fixtures.password}") String password) {
        this.users = users; this.instruments = instruments; this.articles = articles;
        this.loads = loads; this.encoder = encoder; this.environment = environment; this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        FixtureTarget.validate(environment.getProperty("spring.datasource.url"),
                environment.acceptsProfiles(Profiles.of("prod", "production")));
        if (loads.existsById(DATASET)) {
            org.slf4j.LoggerFactory.getLogger(getClass()).info("Fixtures déjà chargées : données et modifications conservées.");
            return;
        }
        if (PasswordPolicy.validate(password, password) != null) {
            throw new IllegalStateException("Le mot de passe des fixtures doit respecter la politique des mots de passe.");
        }

        User superAdmin = account("superadmin", "Camille · Super administrateur", Role.ROLE_SUPER_ADMIN);
        User admin = account("admin", "Alex · Administration", Role.ROLE_ADMIN);
        User editor = account("editor", "Charlie · Rédaction", Role.ROLE_EDITOR);
        User superUser = account("superuser", "Lou · Collection avancée", Role.ROLE_SUPER_USER);
        User member = account("user", "Sam · Collection", Role.ROLE_USER);

        instrument(superAdmin, "Telecaster de démonstration", "Fender", 2016, "Actif", "DEMO-SA-001");
        instrument(admin, "Les Paul de démonstration", "Gibson", 2010, "Actif", "DEMO-AD-001");
        instrument(editor, "FG de démonstration", "Yamaha", 2021, "Actif", "DEMO-ED-001");
        instrument(superUser, "Jazz Bass de démonstration", "Fender", 2018, "Actif", "DEMO-SU-001");
        instrument(superUser, "Électrique de démonstration", "Ibanez", 2005, "À réparer", "DEMO-SU-002");
        instrument(member, "Folk de démonstration", "Cort", 2022, "Actif", "DEMO-US-001");
        instrument(member, "Classique de démonstration", "Yamaha", 2015, "En vente", "DEMO-US-002");

        article(admin, "Prendre le temps de découvrir sa guitare", "guides", true,
                "Un carnet de départ pour observer son instrument et noter ce qui compte pour soi.",
                "Cette page est un exemple du journal Guitar Vault.\n\nCommencez par décrire votre guitare avec vos propres mots : sa forme, son toucher et les sons que vous aimez.\n\nAjoutez ensuite vos souvenirs et vos questions dans son passeport numérique. Ce carnet vous appartient et peut évoluer avec votre pratique.");
        article(admin, "Une routine de jeu à votre rythme", "technique", true,
                "Quelques pistes pour construire une séance personnelle et garder une trace de ses envies.",
                "Choisissez un morceau que vous avez envie de jouer, puis un passage sur lequel vous souhaitez travailler.\n\nNotez votre point de départ et ce que vous aimeriez essayer à la prochaine séance. L’objectif de cet exemple est de montrer la lecture d’un article en plusieurs paragraphes.\n\nAdaptez votre routine à votre disponibilité et à votre plaisir de jouer.");
        article(superAdmin, "Chaque instrument a une histoire", "stories", true,
                "Une première guitare, une rencontre, un concert : les souvenirs font aussi partie d’une collection.",
                "Dans cette histoire fictive, une guitare accompagne ses propriétaires d’un petit studio à une scène de quartier.\n\nLes morceaux, les concerts et les personnes rencontrées composent son parcours. Guitar Vault permet de rassembler ces souvenirs dans une même fiche.\n\nCet article fait partie des données de démonstration et peut être modifié librement.");
        article(editor, "Préparer sa prochaine répétition", "guides", false,
                "Brouillon de la rédaction : une liste d’idées à compléter avant publication.",
                "Brouillon de démonstration.\n\nPréparer la liste des morceaux.\nNoter les accessoires à emporter.\nAjouter les retours du groupe après la séance.");
        article(admin, "Choisir les accessoires de sa collection", "gear", false,
                "Un deuxième brouillon pour tester les filtres et le travail de publication.",
                "Contenu à compléter.\n\nPrésenter les accessoires utilisés et expliquer leur place dans son quotidien musical.");

        loads.saveAndFlush(new FixtureLoad(DATASET));
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Fixtures chargées : 5 comptes, 5 articles et 7 instruments dans la base de démonstration.");
    }

    private User account(String name, String fullName, Role role) {
        String email = name + "@guitar-vault.test";
        if (users.existsByEmailIgnoreCase(email)) {
            throw new IllegalStateException("Un compte utilise déjà une adresse des fixtures. Aucun compte existant n’a été modifié.");
        }
        User user = new User();
        user.setUsername("demo_" + name); user.setEmail(email); user.setFullName(fullName);
        user.setPasswordHash(encoder.encode(password)); user.setEnabled(true); user.setRoles(Set.of(role));
        user.setInstrument("Guitare"); user.setLevel("Intermédiaire"); user.setCity("Ville de démonstration");
        user.setBio("Compte fictif créé par AppFixtures pour explorer Guitar Vault.");
        return users.saveAndFlush(user);
    }

    private void instrument(User owner, String name, String brand, int year, String status, String serial) {
        Instrument instrument = new Instrument(name, brand, year, owner, status);
        instrument.setSerialNumber(serial); instrument.setPreviousOwner("Propriétaire fictif");
        instrument.setHistory("Instrument fictif ajouté pour explorer sa collection sans utiliser de données personnelles.");
        instrument.setNotes("Exemple librement modifiable dans la base de démonstration.");
        instrument.getRecordings().add("Maquette de démonstration · Studio maison");
        instrument.getConcerts().add("Concert fictif · Salle de démonstration");
        instruments.save(instrument);
    }

    private void article(User author, String title, String category, boolean published, String summary, String body) {
        articles.save(null, Map.of("title", title, "category", category, "summary", summary, "body", body,
                "action", published ? "publish" : "draft"), null, author.getEmail());
    }
}
