package com.guitarvault;

import java.net.URI;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StudioService {
    private final StudioRepository studios;
    private final StudioPhotoRepository photos;

    public StudioService(StudioRepository studios, StudioPhotoRepository photos) {
        this.studios = studios;
        this.photos = photos;
    }

    public Studio find(Long id) {
        return studios.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public Studio editable(User actor, Long id) {
        Studio studio = find(id);
        if (!studio.getCreator().getId().equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return studio;
    }

    public List<Studio> search(Map<String, String> filters) {
        String city = filters.getOrDefault("city", "").strip();
        String country = filters.getOrDefault("country", "").strip();
        if (city.length() > 100 || country.length() > 100) {
            throw new IllegalArgumentException("studio.locationError");
        }
        BigDecimal min = price(filters.getOrDefault("minPrice", ""), "studio.searchPriceError");
        BigDecimal max = price(filters.getOrDefault("maxPrice", ""), "studio.searchPriceError");
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException("studio.priceRangeError");
        }
        return studios.findAll((root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            if (!city.isEmpty()) predicates.add(builder.like(builder.lower(root.get("city")), contains(city), '!'));
            if (!country.isEmpty()) predicates.add(builder.like(builder.lower(root.get("country")), contains(country), '!'));
            // SQL comparisons exclude unknown prices whenever a price bound is set.
            if (min != null) predicates.add(builder.greaterThanOrEqualTo(root.get("hourlyRate"), min));
            if (max != null) predicates.add(builder.lessThanOrEqualTo(root.get("hourlyRate"), max));
            return builder.and(predicates.toArray(Predicate[]::new));
        }, Sort.by(Sort.Direction.DESC, "id"));
    }

    private static String contains(String value) {
        return "%" + value.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static BigDecimal price(String input, String error) {
        String value = input.strip();
        if (value.isEmpty()) return null;
        if (!value.matches("[0-9]{1,6}([.,][0-9]{1,2})?")) throw new IllegalArgumentException(error);
        return new BigDecimal(value.replace(',', '.'));
    }

    @Transactional
    public Studio save(User actor, Long id, Map<String, String> form) {
        return save(actor, id, form, List.of(), List.of());
    }

    @Transactional
    public Studio save(User actor, Long id, Map<String, String> form, List<MultipartFile> uploads, List<Long> removePhotos) {
        Studio studio = id == null ? new Studio(actor) : studios.findForUpdate(id)
                .filter(item -> item.getCreator().getId().equals(actor.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (form.get("name").isBlank() || form.get("name").length() > 100
                || form.get("city").length() > 100 || form.get("country").length() > 100
                || List.of("description", "services", "equipment").stream().anyMatch(field -> form.get(field).length() > 5000)) {
            throw new IllegalArgumentException("studio.fieldsError");
        }
        String email = form.get("contactEmail");
        if (!email.isEmpty() && (email.length() > 254 || !email.matches("[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+"))) {
            throw new IllegalArgumentException("studio.emailError");
        }
        validateUrl(form.get("websiteUrl"));
        BigDecimal hourlyRate = price(form.getOrDefault("hourlyRate", ""), "studio.rateError");
        BigDecimal mixingRate = price(form.getOrDefault("mixingRate", ""), "studio.serviceRateError");
        BigDecimal masteringRate = price(form.getOrDefault("masteringRate", ""), "studio.serviceRateError");
        BigDecimal dayRate = price(form.getOrDefault("dayRate", ""), "studio.serviceRateError");
        String pricingNotes = form.getOrDefault("pricingNotes", "");
        if (pricingNotes.length() > 1000) throw new IllegalArgumentException("studio.pricingNotesError");
        Integer capacity = null;
        String capacityText = form.getOrDefault("capacity", "");
        if (!capacityText.isEmpty()) {
            if (!capacityText.matches("[0-9]{1,4}")) throw new IllegalArgumentException("studio.capacityError");
            capacity = Integer.valueOf(capacityText);
            if (capacity < 1 || capacity > 1000) throw new IllegalArgumentException("studio.capacityError");
        }
        var existingIds = id == null ? Set.<Long>of() : photos.findByStudioIdOrderByIdAsc(id).stream()
                .map(StudioPhotoRepository.Summary::getId).collect(java.util.stream.Collectors.toSet());
        var removals = Set.copyOf(removePhotos);
        if (!existingIds.containsAll(removals)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var files = uploads.stream().filter(file -> !file.isEmpty()).toList();
        if (existingIds.size() - removals.size() + files.size() > 5) throw new IllegalArgumentException("studio.photosError");
        var validatedPhotos = files.stream().map(StudioService::validatePhoto).toList();
        // Validate every field before changing the persisted profile.
        studio.setName(form.get("name"));
        studio.setCity(form.get("city"));
        studio.setCountry(form.get("country"));
        studio.setDescription(form.get("description"));
        studio.setServices(form.get("services"));
        studio.setEquipment(form.get("equipment"));
        studio.setWebsiteUrl(form.get("websiteUrl"));
        studio.setContactEmail(email);
        studio.setHourlyRate(hourlyRate);
        studio.setCapacity(capacity);
        studio.setMixingRate(mixingRate);
        studio.setMasteringRate(masteringRate);
        studio.setDayRate(dayRate);
        studio.setPricingNotes(pricingNotes);
        Studio saved = studios.save(studio);
        if (!removals.isEmpty()) photos.deleteByStudioIdAndIdIn(saved.getId(), removals);
        for (var photo : validatedPhotos) photos.save(new StudioPhoto(saved, photo.contentType(), photo.data()));
        return saved;
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
                    if (!Set.of("jpeg", "png").contains(format) || reader.getWidth(0) > 10000 || reader.getHeight(0) > 10000
                            || (long) reader.getWidth(0) * reader.getHeight(0) > 25000000 || reader.read(0) == null) throw new IOException();
                    return new PhotoData("image/" + format, data);
                } finally { reader.dispose(); }
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("studio.photosError");
        }
    }

    private void validateUrl(String value) {
        if (value.isEmpty()) return;
        try {
            URI uri = URI.create(value);
            if (value.length() > 2000 || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() != -1) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("studio.linkError");
        }
    }
}
