package com.guitarvault;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
public class ConcertService {
    private final ConcertRepository concerts;
    private final BandService bands;
    private final ConcertParticipationRepository participations;
    private final ConcertPosterRepository posters;
    private final NotificationService notifications;
    public ConcertService(ConcertRepository concerts, BandService bands, ConcertParticipationRepository participations, ConcertPosterRepository posters, NotificationService notifications) {
        this.concerts = concerts; this.bands = bands; this.participations = participations; this.posters = posters; this.notifications = notifications;
    }
    public Concert find(Long id) { return concerts.findById(id).orElseThrow(ConcertService::notFound); }
    public Concert editable(User actor, Long id) {
        Concert concert = find(id); requireManager(actor, concert); return concert;
    }
    @Transactional
    public Concert save(User actor, Long bandId, Long id, Map<String, String> form, MultipartFile upload, boolean removePoster) {
        Concert concert;
        if (id == null) {
            Band organizer = bands.editable(actor, bandId); ContentVisibility.require(!organizer.isModerationHidden());
            concert = new Concert(organizer);
        }
        else {
            concert = concerts.findForUpdate(id).orElseThrow(ConcertService::notFound);
            requireManager(actor, concert);
            requireScheduled(concert);
        }
        String title = form.get("title"), venue = form.get("venue"), city = form.get("city"), description = form.get("description");
        if (title.isBlank() || title.length() > 150 || venue.isBlank() || venue.length() > 200 || city.length() > 100 || description.length() > 5000) {
            throw new IllegalArgumentException("concert.fieldsError");
        }
        LocalDate date;
        try {
            String value = form.get("date");
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeParseException("Invalid date", value, 0);
            date = LocalDate.parse(value);
            if (date.getYear() < 1) throw new DateTimeParseException("Invalid year", value, 0);
        } catch (DateTimeParseException e) { throw new IllegalArgumentException("concert.dateError"); }
        PhotoData photo = upload == null || upload.isEmpty() ? null : validatePoster(upload);
        concert.update(title, date, venue, city, description);
        Concert saved = concerts.save(concert);
        if (photo != null) {
            ConcertPoster poster = posters.findByConcertId(saved.getId()).orElseGet(() -> new ConcertPoster(saved));
            poster.replace(photo.contentType(), photo.data()); posters.save(poster);
        } else if (removePoster) posters.findByConcertId(saved.getId()).ifPresent(posters::delete);
        return saved;
    }
    @Transactional
    public void cancel(User actor, Long id) {
        Concert concert = concerts.findForUpdate(id).orElseThrow(ConcertService::notFound);
        requireManager(actor, concert); requireScheduled(concert); concert.cancel(); concerts.save(concert);
    }
    @Transactional
    public void invite(User actor, Long concertId, Long bandId) {
        Concert concert = concerts.findForUpdate(concertId).orElseThrow(ConcertService::notFound);
        requireManager(actor, concert); requireScheduled(concert);
        ContentVisibility.require(!concert.isModerationHidden());
        if (concert.getOrganizer().getId().equals(bandId)) throw new IllegalArgumentException("concert.organizerError");
        Band band = bands.find(bandId);
        ContentVisibility.require(!band.isModerationHidden());
        if (!band.getCreator().isEnabled()) throw new IllegalArgumentException("concert.inactiveError");
        ConcertParticipation participation = participations.findByConcertIdAndBandId(concertId, bandId)
                .orElseGet(() -> new ConcertParticipation(concert, band));
        if (participation.getId() != null && (participation.getStatus() == ConcertParticipation.Status.PENDING || participation.getStatus() == ConcertParticipation.Status.ACCEPTED)) {
            throw new IllegalArgumentException("concert.existsError");
        }
        participation.setStatus(ConcertParticipation.Status.PENDING); participations.save(participation);
        notifications.send(actor, band.getCreator(), Notification.Kind.CONCERT_INVITATION, concert.getTitle(), "/concerts/" + concertId);
    }
    public ConcertParticipation removable(User actor, Long id) {
        ConcertParticipation p = participations.findById(id).orElseThrow(ConcertService::notFound);
        if (!manages(actor, p.getConcert()) && !p.getBand().getCreator().getId().equals(actor.getId())) throw notFound();
        return p;
    }
    @Transactional
    public Long respond(User actor, Long id, String action) {
        Long concertId = participations.concertId(id).orElseThrow(ConcertService::notFound);
        Concert concert = concerts.findForUpdate(concertId).orElseThrow(ConcertService::notFound);
        ConcertParticipation p = participations.findById(id).orElseThrow(ConcertService::notFound);
        boolean guestManager = p.getBand().getCreator().getId().equals(actor.getId());
        if (action.equals("remove") ? !guestManager && !manages(actor, concert) : !guestManager) throw notFound();
        requireScheduled(concert);
        if (action.equals("remove")) {
            if (p.getStatus() != ConcertParticipation.Status.PENDING && p.getStatus() != ConcertParticipation.Status.ACCEPTED) throw new ResponseStatusException(HttpStatus.CONFLICT);
            p.setStatus(ConcertParticipation.Status.REMOVED);
        } else {
            if (p.getStatus() != ConcertParticipation.Status.PENDING) throw new ResponseStatusException(HttpStatus.CONFLICT);
            if (action.equals("accept") && !concert.getOrganizer().getCreator().isEnabled()) throw new IllegalArgumentException("concert.inactiveError");
            p.setStatus(action.equals("accept") ? ConcertParticipation.Status.ACCEPTED : ConcertParticipation.Status.DECLINED);
        }
        participations.save(p); return concertId;
    }
    private static boolean manages(User actor, Concert concert) { return concert.getOrganizer().getCreator().getId().equals(actor.getId()); }
    private static void requireManager(User actor, Concert concert) { if (!manages(actor, concert)) throw notFound(); }
    private static void requireScheduled(Concert concert) { if (concert.isCancelled()) throw new ResponseStatusException(HttpStatus.CONFLICT); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
    private record PhotoData(String contentType, byte[] data) { }
    private static PhotoData validatePoster(MultipartFile upload) {
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
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (!Set.of("jpeg", "png").contains(format) || width > 4096 || height > 4096 || (long) width * height > 12000000 || reader.read(0) == null) throw new IOException();
                    return new PhotoData("image/" + format, data);
                } finally { reader.dispose(); }
            }
        } catch (IOException | IllegalArgumentException e) { throw new IllegalArgumentException("concert.posterError"); }
    }
}
