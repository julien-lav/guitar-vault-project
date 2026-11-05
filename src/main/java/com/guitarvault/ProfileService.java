package com.guitarvault;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {
    private final UserRepository users;
    private final ProfilePhotoRepository photos;

    public ProfileService(UserRepository users, ProfilePhotoRepository photos) {
        this.users = users;
        this.photos = photos;
    }

    public void requireVisible(Long id) { ContentVisibility.require(!enabledUser(id).isModerationHidden()); }

    // Only these fields are exposed to the member profile template.
    public record View(Long id, String username, String displayName, String instrument, String level,
                       String city, String country, String bio, String spotifyUrl, String deezerUrl,
                       String websiteUrl, List<ProfileActivity> activities, boolean hasPhoto) { }

    @Transactional(readOnly = true)
    public View view(Long id) {
        User user = enabledUser(id);
        return new View(id, user.getUsername(), blank(user.getDisplayName()).isBlank() ? user.getUsername() : user.getDisplayName(),
                blank(user.getInstrument()), blank(user.getLevel()), blank(user.getCity()), blank(user.getCountry()),
                blank(user.getBio()), blank(user.getSpotifyUrl()), blank(user.getDeezerUrl()), blank(user.getWebsiteUrl()),
                user.getActivities().stream().sorted().toList(), photos.existsByOwnerId(id));
    }

    @Transactional(readOnly = true)
    public Map<String, String> form(Long id) {
        User user = enabledUser(id);
        return Map.of("displayName", blank(user.getDisplayName()), "instrument", blank(user.getInstrument()),
                "level", blank(user.getLevel()), "city", blank(user.getCity()), "country", blank(user.getCountry()),
                "bio", blank(user.getBio()), "spotifyUrl", blank(user.getSpotifyUrl()),
                "deezerUrl", blank(user.getDeezerUrl()), "websiteUrl", blank(user.getWebsiteUrl()));
    }

    public ProfilePhoto photo(Long id) {
        enabledUser(id);
        return photos.findByOwnerId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public void save(Long ownerId, Map<String, String> form, List<String> activityNames,
                     MultipartFile upload, boolean removePhoto) {
        User user = enabledUser(ownerId);
        if (form.get("displayName").length() > 100 || form.get("instrument").length() > 255
                || form.get("city").length() > 100 || form.get("country").length() > 100 || form.get("bio").length() > 1000) {
            throw new IllegalArgumentException("profile.fieldsError");
        }
        if (!Set.of("", "Débutant", "Intermédiaire", "Avancé").contains(form.get("level"))) {
            throw new IllegalArgumentException("profile.levelError");
        }
        var activities = EnumSet.noneOf(ProfileActivity.class);
        try {
            for (String value : activityNames) activities.add(ProfileActivity.valueOf(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("profile.activityError");
        }
        validateLink(form.get("spotifyUrl"), Set.of("open.spotify.com"));
        validateLink(form.get("deezerUrl"), Set.of("deezer.com", "www.deezer.com"));
        validateLink(form.get("websiteUrl"), Set.of());
        boolean hasUpload = upload != null && !upload.isEmpty();
        if (hasUpload && removePhoto) throw new IllegalArgumentException("profile.photoConflict");
        PhotoData photo = hasUpload ? validatePhoto(upload) : null;

        // All validation completes before any part of the profile is changed.
        user.setDisplayName(form.get("displayName"));
        user.setInstrument(form.get("instrument"));
        user.setLevel(form.get("level"));
        user.setCity(form.get("city"));
        user.setCountry(form.get("country"));
        user.setBio(form.get("bio"));
        user.setSpotifyUrl(form.get("spotifyUrl"));
        user.setDeezerUrl(form.get("deezerUrl"));
        user.setWebsiteUrl(form.get("websiteUrl"));
        user.setActivities(activities);
        if (photo != null) {
            var existing = photos.findByOwnerId(ownerId);
            if (existing.isPresent()) existing.get().replace(photo.contentType(), photo.data());
            else photos.save(new ProfilePhoto(user, photo.contentType(), photo.data()));
        } else if (removePhoto) {
            photos.findByOwnerId(ownerId).ifPresent(photos::delete);
        }
        users.save(user);
    }

    private User enabledUser(Long id) {
        return users.findById(id).filter(User::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String blank(String value) { return value == null ? "" : value; }

    private static void validateLink(String value, Set<String> hosts) {
        if (value.isEmpty()) return;
        try {
            URI uri = URI.create(value);
            if (value.length() > 2000 || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getPort() != -1
                    || (!hosts.isEmpty() && hosts.stream().noneMatch(host -> host.equalsIgnoreCase(uri.getHost())))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("profile.linksError");
        }
    }

    private record PhotoData(String contentType, byte[] data) { }

    private static PhotoData validatePhoto(MultipartFile upload) {
        try {
            if (upload.getSize() > 5 * 1024 * 1024) throw new IOException();
            byte[] data = upload.getBytes();
            try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new IOException();
                var reader = readers.next();
                try {
                    reader.setInput(stream);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!Set.of("jpeg", "png").contains(format) || reader.getWidth(0) > 4096 || reader.getHeight(0) > 4096
                            || (long) reader.getWidth(0) * reader.getHeight(0) > 12000000 || reader.read(0) == null) {
                        throw new IOException();
                    }
                    return new PhotoData("image/" + format, data);
                } finally { reader.dispose(); }
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("profile.photoError");
        }
    }
}
