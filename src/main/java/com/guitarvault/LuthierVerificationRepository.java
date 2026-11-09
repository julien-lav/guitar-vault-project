package com.guitarvault;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LuthierVerificationRepository extends JpaRepository<LuthierVerification, Long> {
    List<LuthierVerification> findByProfileIdOrderByIdDesc(Long id);
}
