package com.guitarvault;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface StudioRepository extends JpaRepository<Studio, Long>, JpaSpecificationExecutor<Studio> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from Studio s where s.id = :id")
    java.util.Optional<Studio> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
    List<Studio> findAllByOrderByIdDesc();
    List<Studio> findByCreatorIdOrderByNameAsc(Long creatorId);
}
