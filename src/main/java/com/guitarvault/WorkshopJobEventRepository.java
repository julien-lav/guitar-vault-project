package com.guitarvault;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkshopJobEventRepository extends JpaRepository<WorkshopJobEvent, Long> {
    List<WorkshopJobEvent> findByJobIdOrderByIdAsc(Long jobId);
}
