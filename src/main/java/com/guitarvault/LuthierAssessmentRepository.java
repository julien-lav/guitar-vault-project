package com.guitarvault;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LuthierAssessmentRepository extends JpaRepository<LuthierAssessment, Long> {
    List<LuthierAssessment> findByInterventionInstrumentIdOrderByRecordedAtDesc(Long instrumentId);
    List<LuthierAssessment> findByInterventionIdOrderByRevisionDesc(Long id);
}
