package com.guitarvault;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BandService {
    private final BandRepository bands;
    private final UserRepository users;
    private final BandInvitationRepository invitations;
    private final NotificationService notifications;

    public BandService(BandRepository bands, UserRepository users, BandInvitationRepository invitations, NotificationService notifications) {
        this.bands = bands;
        this.users = users;
        this.invitations = invitations;
        this.notifications = notifications;
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

    public List<Band> search(String genre, String region, String city) {
        if (genre.length() > 100 || region.length() > 100 || city.length() > 100) {
            throw new IllegalArgumentException("band.searchError");
        }
        return bands.search(genre.toLowerCase(Locale.ROOT), containsPattern(region), containsPattern(city));
    }

    public List<String> genreOptions() {
        var options = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        options.addAll(bands.genreOptions());
        options.addAll(bands.legacyGenreOptions());
        return List.copyOf(options);
    }

    private static String containsPattern(String value) {
        if (value.isEmpty()) return "";
        return "%" + value.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
    }

    private static Set<String> parseGenres(String value) {
        if (value.length() > 807) throw new IllegalArgumentException("band.genresError");
        Map<String, String> genres = new LinkedHashMap<>();
        for (String entry : value.split(",")) {
            String genre = entry.strip().replaceAll("\\s+", " ");
            if (genre.isEmpty()) continue;
            if (genre.length() > 100) throw new IllegalArgumentException("band.genresError");
            genres.putIfAbsent(genre.toLowerCase(Locale.ROOT), genre);
        }
        if (genres.size() > 8) throw new IllegalArgumentException("band.genresError");
        return new java.util.LinkedHashSet<>(genres.values());
    }

    @Transactional
    public Band save(User actor, Long id, Map<String, String> form) {
        Band band = id == null ? new Band(actor) : lockedEditable(actor, id);
        if (form.get("name").isBlank() || form.get("name").length() > 100
                || form.get("genre").length() > 100 || form.get("city").length() > 100
                || form.getOrDefault("region", "").length() > 100
                || form.get("description").length() > 5000) {
            throw new IllegalArgumentException("band.fieldsError");
        }
        validateUrl(form.get("spotifyUrl"), Set.of("open.spotify.com"));
        validateUrl(form.get("deezerUrl"), Set.of("deezer.com", "www.deezer.com"));
        validateUrl(form.get("websiteUrl"), Set.of());
        Set<String> genres = parseGenres(form.getOrDefault("genres", form.get("genre")));
        var recipients = prepareInvitations(band, form.getOrDefault("members", ""));
        // Mutate only after every field and member has been validated.
        band.setName(form.get("name"));
        band.setGenre(genres.stream().findFirst().orElse(""));
        band.getGenres().clear();
        band.getGenres().addAll(genres);
        band.setRegion(form.getOrDefault("region", ""));
        band.setCity(form.get("city"));
        band.setDescription(form.get("description"));
        band.setSpotifyUrl(form.get("spotifyUrl"));
        band.setDeezerUrl(form.get("deezerUrl"));
        band.setWebsiteUrl(form.get("websiteUrl"));
        if (id == null) band.getMembers().add(band.getCreator());
        Band saved = bands.save(band);
        sendInvitations(saved, recipients);
        return saved;
    }

    @Transactional
    public void invite(User actor, Long bandId, String usernames) {
        Band band = lockedEditable(actor, bandId);
        ContentVisibility.require(!band.isModerationHidden());
        if (usernames.isBlank()) throw new IllegalArgumentException("band.membersError");
        sendInvitations(band, prepareInvitations(band, usernames));
    }

    private List<User> prepareInvitations(Band band, String names) {
        if (names.length() > 10000) throw new IllegalArgumentException("band.membersError");
        var occupied = new HashSet<Long>();
        occupied.add(band.getCreator().getId());
        band.getMembers().forEach(member -> occupied.add(member.getId()));
        if (band.getId() != null) invitations.findByBandIdAndStatusOrderByIdDesc(band.getId(), BandInvitation.Status.PENDING)
                .forEach(invitation -> occupied.add(invitation.getInvitee().getId()));
        Map<Long, User> recipients = new LinkedHashMap<>();
        for (String entry : names.split("[,\\r\\n]+")) {
            String username = entry.strip();
            if (username.isEmpty()) continue;
            User recipient = users.findByUsername(username).filter(User::isEnabled)
                    .orElseThrow(() -> new IllegalArgumentException("band.membersError"));
            if (occupied.add(recipient.getId())) recipients.put(recipient.getId(), recipient);
            if (occupied.size() > 50) throw new IllegalArgumentException("band.membersError");
        }
        return List.copyOf(recipients.values());
    }

    private void sendInvitations(Band band, List<User> recipients) {
        for (User recipient : recipients) {
            var invitation = invitations.findByBandIdAndInviteeId(band.getId(), recipient.getId())
                    .orElseGet(() -> new BandInvitation(band, recipient));
            invitation.setStatus(BandInvitation.Status.PENDING);
            invitations.save(invitation);
            notifications.send(band.getCreator(), recipient, Notification.Kind.BAND_INVITATION, band.getName(), "/bands/" + band.getId());
        }
    }

    @Transactional
    public void respond(User actor, Long invitationId, boolean accept) {
        var target = invitations.target(invitationId).orElseThrow(BandService::notFound);
        if (!target.getInviteeId().equals(actor.getId())) throw notFound();
        Band band = bands.findForUpdate(target.getBandId()).orElseThrow(BandService::notFound);
        BandInvitation invitation = invitations.findById(invitationId).orElseThrow(BandService::notFound);
        requirePending(invitation);
        if (accept) {
            if (!band.getCreator().isEnabled()) throw new IllegalArgumentException("invitation.inactiveCreator");
            boolean alreadyMember = band.getMembers().stream().anyMatch(user -> user.getId().equals(actor.getId()));
            if (!alreadyMember) {
                if (band.getMembers().size() >= 50) throw new IllegalArgumentException("invitation.full");
                band.getMembers().add(users.findById(actor.getId()).filter(User::isEnabled).orElseThrow(BandService::notFound));
            }
        }
        invitation.setStatus(accept ? BandInvitation.Status.ACCEPTED : BandInvitation.Status.DECLINED);
        invitations.save(invitation);
        bands.save(band);
    }

    @Transactional
    public void cancel(User actor, Long invitationId) {
        var target = invitations.target(invitationId).orElseThrow(BandService::notFound);
        lockedEditable(actor, target.getBandId());
        BandInvitation invitation = invitations.findById(invitationId).orElseThrow(BandService::notFound);
        requirePending(invitation);
        invitation.setStatus(BandInvitation.Status.CANCELLED);
        invitations.save(invitation);
    }

    public User removableMember(User actor, Long bandId, Long memberId) {
        Band band = editable(actor, bandId);
        if (band.getCreator().getId().equals(memberId)) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return band.getMembers().stream().filter(member -> member.getId().equals(memberId)).findFirst().orElseThrow(BandService::notFound);
    }

    @Transactional
    public void removeMember(User actor, Long bandId, Long memberId) {
        Band band = lockedEditable(actor, bandId);
        removableMember(actor, bandId, memberId);
        band.getMembers().removeIf(member -> member.getId().equals(memberId));
        bands.save(band);
    }

    public Band leaveable(User actor, Long bandId) {
        Band band = find(bandId);
        if (band.getCreator().getId().equals(actor.getId())) throw new ResponseStatusException(HttpStatus.CONFLICT);
        if (band.getMembers().stream().noneMatch(member -> member.getId().equals(actor.getId()))) throw notFound();
        return band;
    }

    @Transactional
    public void leave(User actor, Long bandId) {
        Band band = bands.findForUpdate(bandId).orElseThrow(BandService::notFound);
        leaveable(actor, bandId);
        band.getMembers().removeIf(member -> member.getId().equals(actor.getId()));
        bands.save(band);
    }

    private Band lockedEditable(User actor, Long id) {
        return bands.findForUpdate(id).filter(band -> band.getCreator().getId().equals(actor.getId())).orElseThrow(BandService::notFound);
    }
    private static void requirePending(BandInvitation invitation) {
        if (invitation.getStatus() != BandInvitation.Status.PENDING) throw new ResponseStatusException(HttpStatus.CONFLICT);
    }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }

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
