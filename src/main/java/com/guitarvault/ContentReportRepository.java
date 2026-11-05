package com.guitarvault;

import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;

public interface ContentReportRepository extends JpaRepository<ContentReport, Long> {
    Page<ContentReport> findByReporterIdOrderByIdDesc(Long reporterId, Pageable page);
    Page<ContentReport> findByStatusOrderByIdDesc(ContentReport.Status status, Pageable page);
    Optional<ContentReport> findByIdAndReporterId(Long id, Long reporterId);
    boolean existsByReporterIdAndTargetTypeAndTargetIdAndStatusNot(Long userId, ContentReport.TargetType type, Long targetId, ContentReport.Status status);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from ContentReport r where r.id = :id")
    Optional<ContentReport> findForUpdate(Long id);
}
