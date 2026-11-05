package com.guitarvault;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BandConnectionService {
    private final BandRepository bands;
    private final BandConnectionRepository connections;
    private final NotificationService notifications;
    public BandConnectionService(BandRepository bands, BandConnectionRepository connections, NotificationService notifications) {
        this.bands = bands;
        this.connections = connections;
        this.notifications = notifications;
    }

    @Transactional
    public void request(User actor, Long senderId, Long recipientId, String typeValue, String dateValue, String venue) {
        // Check ownership before reporting any details about the target.
        Band sender = bands.findById(senderId).orElseThrow(BandConnectionService::notFound);
        requireManager(actor, sender);
        if (senderId.equals(recipientId)) throw new IllegalArgumentException("connection.selfError");
        BandConnection.Type type;
        try { type = BandConnection.Type.valueOf(typeValue); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("connection.typeError"); }
        LocalDate date = null;
        if (venue.length() > 200) throw new IllegalArgumentException("connection.venueError");
        if (!dateValue.isEmpty()) {
            try {
                if (!dateValue.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeParseException("Invalid date", dateValue, 0);
                date = LocalDate.parse(dateValue);
                if (date.getYear() < 1 || date.isAfter(LocalDate.now(ZoneOffset.UTC))) throw new IllegalArgumentException("connection.dateError");
            } catch (DateTimeParseException e) { throw new IllegalArgumentException("connection.dateError"); }
        }
        if (type == BandConnection.Type.FRIEND && (date != null || !venue.isEmpty())) {
            throw new IllegalArgumentException("connection.concertOnly");
        }
        // Always lock the pair in ID order, including when the request is sent in reverse.
        long firstId = Math.min(senderId, recipientId);
        long secondId = Math.max(senderId, recipientId);
        Band first = bands.findForUpdate(firstId).orElseThrow(BandConnectionService::notFound);
        Band second = bands.findForUpdate(secondId).orElseThrow(BandConnectionService::notFound);
        requireManager(actor, senderId.equals(firstId) ? first : second);
        ContentVisibility.require(!first.isModerationHidden() && !second.isModerationHidden());
        if (!first.getCreator().isEnabled() || !second.getCreator().isEnabled()) throw new IllegalArgumentException("connection.inactiveError");
        BandConnection connection = connections.findByFirstBandIdAndSecondBandIdAndType(firstId, secondId, type)
                .orElseGet(() -> new BandConnection(first, second, type));
        if (connection.getStatus() == BandConnection.Status.PENDING || connection.getStatus() == BandConnection.Status.ACCEPTED) {
            throw new IllegalArgumentException("connection.existsError");
        }
        connection.request(senderId, date, venue);
        connections.save(connection);
        notifications.send(actor, connection.getRecipient().getCreator(), Notification.Kind.BAND_CONNECTION_REQUEST,
                connection.getSender().getName(), "/bands/" + connection.getRecipient().getId() + "#band-network");
    }

    public BandConnection removable(User actor, Long id) {
        BandConnection connection = connections.findById(id).orElseThrow(BandConnectionService::notFound);
        if (!manages(actor, connection.getFirstBand()) && !manages(actor, connection.getSecondBand())) throw notFound();
        if (connection.getStatus() != BandConnection.Status.ACCEPTED) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return connection;
    }

    @Transactional
    public void act(User actor, Long id, String action) {
        BandConnectionRepository.Target target = connections.target(id).orElseThrow(BandConnectionService::notFound);
        boolean recipient = actor.getId().equals(target.getRequestedByFirst() ? target.getSecondOwnerId() : target.getFirstOwnerId());
        boolean sender = actor.getId().equals(target.getRequestedByFirst() ? target.getFirstOwnerId() : target.getSecondOwnerId());
        if (switch (action) {
            case "accept", "decline" -> !recipient;
            case "cancel" -> !sender;
            case "remove" -> !recipient && !sender;
            default -> true;
        }) throw notFound();
        bands.findForUpdate(target.getFirstId()).orElseThrow(BandConnectionService::notFound);
        bands.findForUpdate(target.getSecondId()).orElseThrow(BandConnectionService::notFound);
        BandConnection connection = connections.findForUpdate(id).orElseThrow(BandConnectionService::notFound);
        // A declined request may have been sent again in the opposite direction while waiting for the locks.
        boolean currentRecipient = manages(actor, connection.getRecipient());
        boolean currentSender = manages(actor, connection.getSender());
        if (switch (action) {
            case "accept", "decline" -> !currentRecipient;
            case "cancel" -> !currentSender;
            default -> !currentRecipient && !currentSender;
        }) throw notFound();
        BandConnection.Status expected = action.equals("remove") ? BandConnection.Status.ACCEPTED : BandConnection.Status.PENDING;
        if (connection.getStatus() != expected) throw new ResponseStatusException(HttpStatus.CONFLICT);
        if (action.equals("accept") && (!connection.getSender().getCreator().isEnabled() || !connection.getRecipient().getCreator().isEnabled())) {
            throw new IllegalArgumentException("connection.inactiveError");
        }
        connection.setStatus(switch (action) {
            case "accept" -> BandConnection.Status.ACCEPTED;
            case "decline" -> BandConnection.Status.DECLINED;
            case "cancel" -> BandConnection.Status.CANCELLED;
            default -> BandConnection.Status.REMOVED;
        });
        connections.save(connection);
    }

    private static boolean manages(User actor, Band band) { return band.getCreator().getId().equals(actor.getId()); }
    private static void requireManager(User actor, Band band) { if (!manages(actor, band)) throw notFound(); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
