package com.guitarvault;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BandDateRevisionRepository extends JpaRepository<BandDateRevision, Long> {
    List<BandDateRevision> findByProposalIdOrderByNumberAsc(Long id);
}
