package com.guitarvault;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface WorkshopBlockedDateRepository extends JpaRepository<WorkshopBlockedDate, Long> {
    List<WorkshopBlockedDate> findByWorkshopIdOrderByDateAsc(Long id);
    boolean existsByWorkshopIdAndDate(Long id, LocalDate date);
}
