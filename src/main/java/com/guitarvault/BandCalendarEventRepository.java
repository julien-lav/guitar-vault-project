package com.guitarvault;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface BandCalendarEventRepository extends JpaRepository<BandCalendarEvent, Long> {
    Optional<BandCalendarEvent> findByIdAndBandId(Long id, Long bandId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from BandCalendarEvent e where e.id = :id and e.band.id = :bandId")
    Optional<BandCalendarEvent> findForUpdate(Long id, Long bandId);
    @Query("""
            select e from BandCalendarEvent e where e.band.id = :bandId and e.date <= :end
            and (e.date >= :start or (e.recurrence = :weekly and e.repeatUntil >= :start))
            order by e.date, e.id
            """)
    List<BandCalendarEvent> overlapping(Long bandId, LocalDate start, LocalDate end, BandCalendarEvent.Recurrence weekly);
}
