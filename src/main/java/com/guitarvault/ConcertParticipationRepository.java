package com.guitarvault;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface ConcertParticipationRepository extends JpaRepository<ConcertParticipation, Long> {
    Optional<ConcertParticipation> findByConcertIdAndBandId(Long concertId, Long bandId);
    List<ConcertParticipation> findByConcertIdAndStatusOrderByBandNameAsc(Long concertId, ConcertParticipation.Status status);
    List<ConcertParticipation> findByBandCreatorIdAndStatusAndConcertCancelledFalseOrderByIdDesc(Long userId, ConcertParticipation.Status status);
    List<ConcertParticipation> findByConcertIdAndBandCreatorIdAndStatus(Long concertId, Long userId, ConcertParticipation.Status status);
    @Query("select p.concert.id from ConcertParticipation p where p.id = :id")
    Optional<Long> concertId(Long id);
}
