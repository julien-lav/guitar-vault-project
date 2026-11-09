package com.guitarvault;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface BandDateProposalRepository extends JpaRepository<BandDateProposal, Long> {
    interface Target { Long getSenderId(); Long getRecipientId(); Long getSenderOwnerId(); Long getRecipientOwnerId(); }
    @Query("select p.sender.id as senderId, p.recipient.id as recipientId, p.sender.creator.id as senderOwnerId, p.recipient.creator.id as recipientOwnerId from BandDateProposal p where p.id = :id")
    Optional<Target> target(Long id);
    @Query("select p from BandDateProposal p where p.sender.creator.id = :actorId or p.recipient.creator.id = :actorId order by p.id desc")
    Page<BandDateProposal> forManager(Long actorId, Pageable page);
    @Query("select p from BandDateProposal p where p.id = :id and (p.sender.creator.id = :actorId or p.recipient.creator.id = :actorId)")
    Optional<BandDateProposal> forManagerById(Long id, Long actorId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from BandDateProposal p where p.id = :id")
    Optional<BandDateProposal> findForUpdate(Long id);
    @Query("select count(p) > 0 from BandDateProposal p where p.status = :status and p.proposedDate = :date and ((p.sender.id = :first and p.recipient.id = :second) or (p.sender.id = :second and p.recipient.id = :first))")
    boolean pendingForDate(Long first, Long second, java.time.LocalDate date, BandDateProposal.Status status);
}
