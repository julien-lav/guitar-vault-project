package com.guitarvault;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface InstrumentOwnershipRepository extends JpaRepository<InstrumentOwnership, Long> {
    List<InstrumentOwnership> findByInstrumentIdOrderByStartedAtAsc(Long id);
    Optional<InstrumentOwnership> findByInstrumentIdAndEndedAtIsNull(Long id);
    boolean existsByInstrumentIdAndOwnerId(Long instrumentId, Long ownerId);
}
