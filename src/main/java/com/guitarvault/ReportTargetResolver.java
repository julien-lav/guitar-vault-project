package com.guitarvault;

import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ReportTargetResolver {
    private final UserRepository users;
    private final BandRepository bands;
    private final StudioRepository studios;
    private final ConcertRepository concerts;
    private final ArticleRepository articles;
    public ReportTargetResolver(UserRepository users, BandRepository bands, StudioRepository studios, ConcertRepository concerts, ArticleRepository articles) {
        this.users = users; this.bands = bands; this.studios = studios; this.concerts = concerts; this.articles = articles;
    }
    public record Target(Object entity, User owner, String label, String url, String text, boolean hidden, boolean publicContent) { }
    public Optional<Target> find(ContentReport.TargetType type, Long id, boolean lock) {
        return switch (type) {
            case PROFILE -> (lock ? users.findForUpdate(id) : users.findById(id)).map(u -> new Target(u, u, u.getUsername(), "/musicians/" + id,
                    join(u.getUsername(), u.getDisplayName(), u.getInstrument(), u.getCity(), u.getCountry(), u.getBio(), u.getSpotifyUrl(), u.getDeezerUrl(), u.getWebsiteUrl()), u.isModerationHidden(), u.isEnabled()));
            case BAND -> (lock ? bands.findForUpdate(id) : bands.findById(id)).map(b -> new Target(b, b.getCreator(), b.getName(), "/bands/" + id,
                    join(b.getName(), String.join(", ", b.getDisplayGenres()), b.getRegion(), b.getCity(), b.getDescription(), b.getSpotifyUrl(), b.getDeezerUrl(), b.getWebsiteUrl()), b.isModerationHidden(), true));
            case STUDIO -> (lock ? studios.findForUpdate(id) : studios.findById(id)).map(s -> new Target(s, s.getCreator(), s.getName(), "/studios/" + id,
                    join(s.getName(), s.getCity(), s.getCountry(), s.getDescription(), s.getServices(), s.getEquipment(), s.getWebsiteUrl()), s.isModerationHidden(), true));
            case CONCERT -> (lock ? concerts.findForUpdate(id) : concerts.findById(id)).map(c -> new Target(c, c.getOrganizer().getCreator(), c.getTitle(), "/concerts/" + id,
                    join(c.getTitle(), c.getDate(), c.getVenue(), c.getCity(), c.getDescription()), c.isModerationHidden(), true));
            case ARTICLE -> (lock ? articles.findForUpdate(id) : articles.findById(id)).map(a -> new Target(a, a.getAuthor(), a.getTitle(), "/articles/" + id,
                    join(a.getTitle(), a.getSummary(), a.getBody()), a.isModerationHidden(), a.isPublished()));
        };
    }
    public void hidden(Target target, boolean value) {
        if (target.entity() instanceof User e) e.setModerationHidden(value);
        else if (target.entity() instanceof Band e) e.setModerationHidden(value);
        else if (target.entity() instanceof Studio e) e.setModerationHidden(value);
        else if (target.entity() instanceof Concert e) e.setModerationHidden(value);
        else if (target.entity() instanceof Article e) e.setModerationHidden(value);
    }
    private static String join(Object... values) {
        StringBuilder text = new StringBuilder();
        for (Object value : values) if (value != null && !value.toString().isBlank()) text.append(value).append('\n');
        return text.length() > 60000 ? text.substring(0, 60000) : text.toString();
    }
}
