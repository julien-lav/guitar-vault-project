package com.guitarvault;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface VintageShopRepository extends JpaRepository<VintageShop, Long> {
    Optional<VintageShop> findByUserId(Long userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from VintageShop s where s.id = :id")
    Optional<VintageShop> findForUpdate(Long id);
    @Query("select s from VintageShop s where s.published = true and s.user.enabled = true and s.user.suspended = false and s.user.moderationHidden = false and (:q = '' or locate(lower(:q), lower(s.name)) > 0 or locate(lower(:q), lower(s.location)) > 0) order by s.name, s.id")
    Page<VintageShop> directory(String q, Pageable page);
}
