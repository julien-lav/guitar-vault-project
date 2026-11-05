package com.guitarvault;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConcertPosterRepository extends JpaRepository<ConcertPoster, Long> {
    @Query("select p.concert.id from ConcertPoster p where p.concert.id in :ids")
    java.util.List<Long> concertIdsWithPoster(java.util.Collection<Long> ids);
    boolean existsByConcertId(Long concertId);
    Optional<ConcertPoster> findByConcertId(Long concertId);
}
