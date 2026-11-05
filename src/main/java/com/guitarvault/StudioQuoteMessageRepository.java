package com.guitarvault;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudioQuoteMessageRepository extends JpaRepository<StudioQuoteMessage, Long> {
    List<StudioQuoteMessage> findByQuoteIdOrderByIdAsc(Long quoteId);
    boolean existsByQuoteId(Long quoteId);
}
