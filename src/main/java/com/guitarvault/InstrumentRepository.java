package com.guitarvault;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select i from Instrument i where i.id = :id")
    java.util.Optional<Instrument> findForUpdate(Long id);
    List<Instrument> findByOwnerIdOrderByIdDesc(Long ownerId);
}
