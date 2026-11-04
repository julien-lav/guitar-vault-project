package com.guitarvault;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BandRepository extends JpaRepository<Band, Long> {
    List<Band> findAllByOrderByIdDesc();
    List<Band> findByMembersIdOrderByNameAsc(Long userId);
}
