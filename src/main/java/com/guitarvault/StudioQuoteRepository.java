package com.guitarvault;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudioQuoteRepository extends JpaRepository<StudioQuote, Long> {
    List<StudioQuote> findTop3ByRequesterIdOrderByIdDesc(Long requesterId);
    List<StudioQuote> findTop3ByStudioCreatorIdAndStatusOrderByIdDesc(Long creatorId, StudioQuote.Status status);
    long countByRequesterId(Long requesterId);
    long countByRequesterIdAndStatus(Long requesterId, StudioQuote.Status status);
    long countByStudioCreatorIdAndStatus(Long creatorId, StudioQuote.Status status);
    List<StudioQuote> findByRequesterIdOrderByIdDesc(Long requesterId);
    List<StudioQuote> findByStudioCreatorIdOrderByIdDesc(Long creatorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from StudioQuote q where q.id = :id")
    Optional<StudioQuote> findForUpdate(@Param("id") Long id);
}
