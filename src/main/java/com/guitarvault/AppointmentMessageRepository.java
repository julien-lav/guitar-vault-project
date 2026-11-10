package com.guitarvault;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppointmentMessageRepository extends JpaRepository<AppointmentMessage, Long> {
    List<AppointmentMessage> findByAppointmentIdOrderByIdAsc(Long id);
}
