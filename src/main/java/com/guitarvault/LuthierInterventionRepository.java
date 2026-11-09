package com.guitarvault;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
public interface LuthierInterventionRepository extends JpaRepository<LuthierIntervention, Long> {
    List<LuthierIntervention> findByRequesterIdOrLuthierUserIdOrderByIdDesc(Long requesterId, Long userId);
    boolean existsByInstrumentIdAndStatus(Long instrumentId, LuthierIntervention.Status status);
    List<LuthierIntervention> findByInstrumentIdAndRequesterIdOrderByIdDesc(Long instrumentId, Long requesterId);
    List<LuthierIntervention> findByInstrumentIdOrderByIdDesc(Long instrumentId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from LuthierIntervention i where i.id = :id")
    Optional<LuthierIntervention> findForUpdate(Long id);
}
