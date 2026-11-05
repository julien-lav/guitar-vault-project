package com.guitarvault;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface ConcertRepository extends JpaRepository<Concert, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Concert c where c.id = :id")
    Optional<Concert> findForUpdate(Long id);
    @Query("""
            select c from Concert c where c.moderationHidden = false and (c.organizer.id = :bandId
            or exists (select p.id from ConcertParticipation p where p.concert = c and p.band.id = :bandId and p.status = :status))
            order by c.date desc, c.id desc
            """)
    List<Concert> forBand(Long bandId, ConcertParticipation.Status status);
    @Query("""
            select c from Concert c where c.moderationHidden = false and c.date between :start and :end and
            (c.organizer.id = :bandId or exists (select p.id from ConcertParticipation p
            where p.concert = c and p.band.id = :bandId and p.status = :status))
            order by c.date, c.id
            """)
    List<Concert> forCalendar(Long bandId, java.time.LocalDate start, java.time.LocalDate end, ConcertParticipation.Status status);
}
