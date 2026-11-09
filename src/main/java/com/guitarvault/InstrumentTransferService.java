package com.guitarvault;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InstrumentTransferService {
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private final NotificationService notifications;
    private final DocumentService documents;
    private final InstrumentRepository instruments;
    private final InstrumentTransferRepository transfers;
    private final InstrumentOwnershipRepository ownerships;
    private final UserRepository users;
    private final LuthierInterventionRepository interventions;
    public InstrumentTransferService(InstrumentRepository instruments, InstrumentTransferRepository transfers,
            InstrumentOwnershipRepository ownerships, UserRepository users, LuthierInterventionRepository interventions, NotificationService notifications, DocumentService documents) {
        this.instruments = instruments; this.transfers = transfers; this.ownerships = ownerships;
        this.users = users; this.interventions = interventions; this.notifications = notifications; this.documents = documents;
    }
    @Transactional
    public InstrumentTransfer propose(User actor, Long instrumentId, String username, boolean confirmed) {
        return propose(actor, instrumentId, username, confirmed, List.of());
    }
    @Transactional
    public InstrumentTransfer propose(User actor, Long instrumentId, String username, boolean confirmed, List<Long> documentIds) {
        Instrument instrument = instruments.findForUpdate(instrumentId).orElseThrow(InstrumentTransferService::notFound);
        if (!instrument.getOwner().getId().equals(actor.getId())) throw notFound();
        if (!confirmed || username == null || username.isBlank() || username.length() > 255) throw invalid("transfer.invalid");
        User recipient = users.findByUsername(username.strip()).filter(User::isEnabled)
                .filter(u -> !u.isModerationHidden()).filter(u -> !u.getId().equals(actor.getId()))
                .orElseThrow(() -> invalid("transfer.recipientError"));
        if (transfers.existsByInstrumentIdAndStatus(instrumentId, InstrumentTransfer.Status.PENDING)) throw conflict("transfer.pendingError");
        var selected = documents.selected(actor, instrument, documentIds);
        if (ownerships.findByInstrumentIdAndEndedAtIsNull(instrumentId).isEmpty()) {
            ownerships.save(new InstrumentOwnership(instrument, actor, instrument.getCreatedAt()));
        }
        InstrumentTransfer transfer = transfers.save(new InstrumentTransfer(instrument, actor, recipient));
        transfer.selectDocuments(selected);
        notifications.send(actor, recipient, Notification.Kind.INSTRUMENT_TRANSFER, transfer.getInstrumentName(), "/transfers/" + transfer.getId());
        return transfer;
    }
    public InstrumentTransfer visible(User actor, Long id) {
        InstrumentTransfer transfer = transfers.findById(id).orElseThrow(InstrumentTransferService::notFound);
        authorize(actor, transfer); return transfer;
    }
    private void authorize(User actor, InstrumentTransfer transfer) {
        if (!transfer.getSender().getId().equals(actor.getId()) && !transfer.getRecipient().getId().equals(actor.getId())) throw notFound();
    }
    @Transactional
    public void resolve(User actor, Long id, InstrumentTransfer.Status decision) {
        InstrumentTransfer visible = visible(actor, id);
        // All transfer mutations take the instrument lock first, including concurrent proposals.
        Instrument instrument = instruments.findForUpdate(visible.getInstrument().getId()).orElseThrow(InstrumentTransferService::notFound);
        InstrumentTransfer transfer = transfers.findForUpdate(id).orElseThrow(InstrumentTransferService::notFound);
        // The visibility check may have loaded the transfer before another transaction committed.
        entityManager.refresh(instrument);
        entityManager.refresh(transfer);
        if (decision == InstrumentTransfer.Status.CANCELLED) {
            if (!transfer.getSender().getId().equals(actor.getId())) throw notFound();
        } else if (decision == InstrumentTransfer.Status.ACCEPTED || decision == InstrumentTransfer.Status.DECLINED) {
            if (!transfer.getRecipient().getId().equals(actor.getId())) throw notFound();
        } else throw invalid("transfer.invalid");
        if (transfer.getStatus() != InstrumentTransfer.Status.PENDING
                || !instrument.getOwner().getId().equals(transfer.getSender().getId())) throw conflict("transfer.stale");
        Instant now = Instant.now();
        if (decision == InstrumentTransfer.Status.ACCEPTED) {
            if (!transfer.getSender().isEnabled() || !transfer.getRecipient().isEnabled()) throw conflict("transfer.inactive");
            if (interventions.existsByInstrumentIdAndStatus(instrument.getId(), LuthierIntervention.Status.REQUESTED)) throw conflict("transfer.workPending");
            InstrumentOwnership previous = ownerships.findByInstrumentIdAndEndedAtIsNull(instrument.getId())
                    .orElseThrow(() -> conflict("transfer.stale"));
            if (!previous.getOwner().getId().equals(transfer.getSender().getId())) throw conflict("transfer.stale");
            previous.close(now);
            // Legacy photos have no private author; preserve their privacy before changing owner.
            for (InstrumentPhoto photo : instrument.getPhotos()) photo.preservePrivateOwner(transfer.getSender());
            documents.grantSelected(transfer);
            instrument.transferTo(transfer.getRecipient());
            ownerships.save(new InstrumentOwnership(instrument, transfer.getRecipient(), now));
        }
        transfer.resolve(decision, now);
        notifications.send(actor, decision == InstrumentTransfer.Status.CANCELLED ? transfer.getRecipient() : transfer.getSender(),
                Notification.Kind.INSTRUMENT_TRANSFER_RESPONSE, transfer.getInstrumentName(), "/transfers/" + transfer.getId());
    }
    public List<InstrumentOwnership> history(User actor, Long instrumentId) {
        Instrument instrument = instruments.findById(instrumentId).orElseThrow(InstrumentTransferService::notFound);
        if (!instrument.getOwner().getId().equals(actor.getId()) && !ownerships.existsByInstrumentIdAndOwnerId(instrumentId, actor.getId())) throw notFound();
        return ownerships.findByInstrumentIdOrderByStartedAtAsc(instrumentId);
    }
    public InstrumentOwnership archive(User actor, Long id) {
        return ownerships.findById(id).filter(p -> p.getOwner().getId().equals(actor.getId()) && p.getEndedAt() != null)
                .orElseThrow(InstrumentTransferService::notFound);
    }
    private static IllegalArgumentException invalid(String key) { return new IllegalArgumentException(key); }
    private static ResponseStatusException conflict(String key) { return new ResponseStatusException(HttpStatus.CONFLICT, key); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
