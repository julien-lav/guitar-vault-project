package com.guitarvault;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BandDateProposalService {
    public static final class ChangedProposal extends ResponseStatusException {
        ChangedProposal() { super(HttpStatus.CONFLICT); }
    }
    private final BandRepository bands;
    private final BandConnectionRepository connections;
    private final BandDateProposalRepository proposals;
    private final BandDateRevisionRepository revisions;
    private final NotificationService notifications;
    public BandDateProposalService(BandRepository bands, BandConnectionRepository connections, BandDateProposalRepository proposals,
                                   BandDateRevisionRepository revisions, NotificationService notifications) {
        this.bands = bands; this.connections = connections; this.proposals = proposals; this.revisions = revisions; this.notifications = notifications;
    }
    @Transactional(readOnly = true)
    public List<Band> eligibleSenders(User actor, Long targetId) {
        return connections.forManager(actor.getId(), BandConnection.Status.ACCEPTED).stream()
                .filter(c -> c.getType() == BandConnection.Type.FRIEND)
                .filter(c -> c.getFirstBand().getId().equals(targetId) || c.getSecondBand().getId().equals(targetId))
                .filter(c -> available(c.getFirstBand()) && available(c.getSecondBand()))
                .filter(c -> !c.getFirstBand().getCreator().getId().equals(c.getSecondBand().getCreator().getId()))
                .map(c -> c.other(targetId)).filter(b -> manages(actor, b)).toList();
    }
    @Transactional(readOnly = true)
    public BandDateProposal visible(User actor, Long id) {
        return proposals.forManagerById(id, actor.getId()).orElseThrow(BandDateProposalService::notFound);
    }
    @Transactional
    public BandDateProposal create(User actor, Long senderId, Long recipientId, String dateValue, String venue, String message) {
        if (!bands.existsByIdAndCreatorId(senderId, actor.getId())) throw notFound();
        requireFriendship(senderId, recipientId);
        Band sender = bands.findById(senderId).orElseThrow(BandDateProposalService::notFound);
        Band recipient = bands.findById(recipientId).orElseThrow(BandDateProposalService::notFound);
        LocalDate date = validate(dateValue, venue, message);
        if (proposals.pendingForDate(senderId, recipientId, date, BandDateProposal.Status.PENDING)) throw new IllegalArgumentException("proposal.duplicateError");
        BandDateProposal proposal = proposals.save(new BandDateProposal(sender, recipient, date, venue, message));
        revisions.save(new BandDateRevision(proposal, actor)); notifyOther(actor, proposal, Notification.Kind.DATE_PROPOSAL);
        return proposal;
    }
    @Transactional
    public void act(User actor, Long id, int revision, String action, String dateValue, String venue, String message) {
        var initial = proposals.target(id).orElseThrow(BandDateProposalService::notFound);
        if (!actor.getId().equals(initial.getSenderOwnerId()) && !actor.getId().equals(initial.getRecipientOwnerId())) throw notFound();
        // Match the lock order used by friendship mutations, then serialize this discussion.
        lockBands(initial.getSenderId(), initial.getRecipientId());
        BandDateProposal proposal = proposals.findForUpdate(id).orElseThrow(BandDateProposalService::notFound);
        if (!manages(actor, proposal.getSender()) && !manages(actor, proposal.getRecipient())) throw notFound();
        if (proposal.getStatus() != BandDateProposal.Status.PENDING || proposal.getRevision() != revision) throw new ChangedProposal();
        if (message.length() > 2000) throw new IllegalArgumentException("proposal.fieldsError");
        if (action.equals("cancel")) {
            if (!manages(actor, proposal.getSender())) throw notFound();
            proposal.decide(BandDateProposal.Status.CANCELLED, message);
        } else {
            if (!manages(actor, proposal.getResponder())) throw notFound();
            switch (action) {
                case "counter" -> {
                    requireFriendship(proposal.getSender().getId(), proposal.getRecipient().getId());
                    LocalDate date = validate(dateValue, venue, message);
                    if (date.equals(proposal.getProposedDate()) && venue.equals(proposal.getVenue())) throw new IllegalArgumentException("proposal.unchangedError");
                    if (proposals.pendingForDate(proposal.getSender().getId(), proposal.getRecipient().getId(), date, BandDateProposal.Status.PENDING)
                            && !date.equals(proposal.getProposedDate())) throw new IllegalArgumentException("proposal.duplicateError");
                    proposal.propose(manages(actor, proposal.getSender()), date, venue, message);
                    revisions.save(new BandDateRevision(proposal, actor));
                }
                case "accept" -> {
                    requireFriendship(proposal.getSender().getId(), proposal.getRecipient().getId());
                    if (proposal.getProposedDate().isBefore(LocalDate.now(ZoneOffset.UTC))) throw new IllegalArgumentException("proposal.dateError");
                    proposal.decide(BandDateProposal.Status.ACCEPTED, message);
                }
                case "decline" -> proposal.decide(BandDateProposal.Status.DECLINED, message);
                default -> throw notFound();
            }
        }
        proposals.save(proposal);
        notifyOther(actor, proposal, action.equals("counter") ? Notification.Kind.DATE_PROPOSAL : Notification.Kind.DATE_PROPOSAL_RESPONSE);
    }
    private void requireFriendship(Long firstId, Long secondId) {
        if (firstId.equals(secondId)) throw new IllegalArgumentException("proposal.friendError");
        lockBands(firstId, secondId);
        BandConnection connection = connections.findByFirstBandIdAndSecondBandIdAndType(Math.min(firstId, secondId), Math.max(firstId, secondId), BandConnection.Type.FRIEND)
                .orElseThrow(() -> new IllegalArgumentException("proposal.friendError"));
        if (connection.getStatus() != BandConnection.Status.ACCEPTED) throw new IllegalArgumentException("proposal.friendError");
        if (!available(connection.getFirstBand()) || !available(connection.getSecondBand())) throw new IllegalArgumentException("proposal.inactiveError");
        if (connection.getFirstBand().getCreator().getId().equals(connection.getSecondBand().getCreator().getId())) throw new IllegalArgumentException("proposal.friendError");
    }
    private void lockBands(Long firstId, Long secondId) {
        bands.findForUpdate(Math.min(firstId, secondId)).orElseThrow(BandDateProposalService::notFound);
        bands.findForUpdate(Math.max(firstId, secondId)).orElseThrow(BandDateProposalService::notFound);
    }
    private void notifyOther(User actor, BandDateProposal proposal, Notification.Kind kind) {
        Band other = manages(actor, proposal.getSender()) ? proposal.getRecipient() : proposal.getSender();
        Band own = manages(actor, proposal.getSender()) ? proposal.getSender() : proposal.getRecipient();
        notifications.send(actor, other.getCreator(), kind, own.getName(), "/date-proposals/" + proposal.getId());
    }
    private static LocalDate validate(String value, String venue, String message) {
        if (venue.length() > 200 || message.length() > 2000) throw new IllegalArgumentException("proposal.fieldsError");
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeParseException("Invalid date", value, 0);
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1 || date.isBefore(LocalDate.now(ZoneOffset.UTC))) throw new IllegalArgumentException("proposal.dateError");
            return date;
        } catch (DateTimeParseException e) { throw new IllegalArgumentException("proposal.dateError"); }
    }
    private static boolean available(Band band) { return !band.isModerationHidden() && band.getCreator().isEnabled(); }
    private static boolean manages(User actor, Band band) { return actor.getId().equals(band.getCreator().getId()); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
