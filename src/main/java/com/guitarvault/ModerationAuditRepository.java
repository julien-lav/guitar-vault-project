package com.guitarvault;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModerationAuditRepository extends JpaRepository<ModerationAudit, Long> {
    List<ModerationAudit> findByReportIdOrderByIdAsc(Long reportId);
}
