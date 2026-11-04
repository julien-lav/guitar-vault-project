package com.guitarvault;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BandService {
    private final BandRepository bands;
    private final UserRepository users;

    public BandService(BandRepository bands, UserRepository users) {
        this.bands = bands;
        this.users = users;
    }

    public Band find(Long id) {
        return bands.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public Band editable(User actor, Long id) {
        Band band = find(id);
        if (!band.getCreator().getId().equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return band;
    }

    @Transactional
    public Band save(User actor, Long id, Map<String, String> form) {
        Band band = id == null ? new Band(actor) : editable(actor, id);
        if (form.get("name").isBlank() || form.get("name").length() > 100
                || form.get("genre").length() > 100 || form.get("city").length() > 100
                || form.get("description").length() > 5000) {
            throw new IllegalArgumentException("band.fieldsError");
        }
        validateUrl(form.get("spotifyUrl"), Set.of("open.spotify.com"));
        validateUrl(form.get("deezerUrl"), Set.of("deezer.com", "www.deezer.com"));
        validateUrl(form.get("websiteUrl"), Set.of());
        if (form.get("members").length() > 10000) throw new IllegalArgumentException("band.membersError");
        Map<Long, User> members = new LinkedHashMap<>();
        members.put(band.getCreator().getId(), band.getCreator());
        for (String entry : form.get("members").split("[,\\r\\n]+")) {
            String username = entry.strip();
            if (username.isEmpty()) continue;
            User member = users.findByUsername(username).filter(User::isEnabled)
                    .orElseThrow(() -> new IllegalArgumentException("band.membersError"));
            members.put(member.getId(), member);
            if (members.size() > 50) throw new IllegalArgumentException("band.membersError");
        }
        // Mutate only after every field and member has been validated.
        band.setName(form.get("name"));
        band.setGenre(form.get("genre"));
        band.setCity(form.get("city"));
        band.setDescription(form.get("description"));
        band.setSpotifyUrl(form.get("spotifyUrl"));
        band.setDeezerUrl(form.get("deezerUrl"));
        band.setWebsiteUrl(form.get("websiteUrl"));
        band.getMembers().clear();
        band.getMembers().addAll(members.values());
        return bands.save(band);
    }

    private void validateUrl(String value, Set<String> hosts) {
        if (value.isEmpty()) return;
        try {
            URI uri = URI.create(value);
            if (value.length() > 2000 || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() != -1
                    || (!hosts.isEmpty() && hosts.stream().noneMatch(host -> host.equalsIgnoreCase(uri.getHost())))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("band.linksError");
        }
    }
}
