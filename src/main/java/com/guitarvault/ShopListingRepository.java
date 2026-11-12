package com.guitarvault;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface ShopListingRepository extends JpaRepository<ShopListing, Long> {
    Page<ShopListing> findByShopUserIdOrderByIdDesc(Long userId, Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select l from ShopListing l where l.id = :id")
    Optional<ShopListing> findForUpdate(Long id);
    @Query("select count(l) > 0 from ShopListing l where l.instrument.id = :instrument and l.published = true and l.stock <> com.guitarvault.ShopListing.Stock.SOLD and (:id is null or l.id <> :id)")
    boolean hasOtherLiveListing(Long instrument, Long id);
    @Query("select l from ShopListing l where l.shop.id = :shop and l.published = true and l.stock <> com.guitarvault.ShopListing.Stock.SOLD and l.instrument.owner = l.shop.user and (:q = '' or locate(lower(:q), lower(l.title)) > 0 or locate(lower(:q), lower(l.brand)) > 0) order by l.id desc")
    Page<ShopListing> catalogue(Long shop, String q, Pageable page);
}
