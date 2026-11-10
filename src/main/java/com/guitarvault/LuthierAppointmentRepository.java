package com.guitarvault;

import java.util.Optional;
import java.util.List;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface LuthierAppointmentRepository extends JpaRepository<LuthierAppointment, Long> {
    @Query("select a from LuthierAppointment a where a.requester.id = :actor or a.workshop.user.id = :actor order by a.id desc")
    Page<LuthierAppointment> inbox(Long actor, Pageable page);
    @Query("select a.workshop.id from LuthierAppointment a where a.id = :id and (a.requester.id = :actor or a.workshop.user.id = :actor)")
    Optional<Long> visibleWorkshopId(Long id, Long actor);
    @Query("select a from LuthierAppointment a where a.workshop.id = :workshop and a.status = :status and a.proposedAt >= :start and a.proposedAt < :end order by a.proposedAt")
    List<LuthierAppointment> bookings(Long workshop, LuthierAppointment.Status status, Instant start, Instant end);
    @Query("select a.id from LuthierAppointment a where a.status = com.guitarvault.LuthierAppointment.Status.CONFIRMED and a.requester.enabled = true and a.requester.suspended = false and a.workshop.user.enabled = true and a.workshop.user.suspended = false and a.proposedAt > :now and a.proposedAt <= :until and ((a.requesterReminder = true and a.requesterRemindedAt is null) or (a.workshopReminder = true and a.workshopRemindedAt is null)) order by a.proposedAt")
    List<Long> dueReminders(Instant now, Instant until, Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LuthierAppointment a where a.id = :id and (a.requester.id = :actor or a.workshop.user.id = :actor)")
    Optional<LuthierAppointment> findForUpdate(Long id, Long actor);
}
