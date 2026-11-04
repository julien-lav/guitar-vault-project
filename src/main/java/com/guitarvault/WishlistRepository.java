package com.guitarvault;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WishlistRepository extends JpaRepository<WishlistItem, Long> {
    List<WishlistItem> findByOwnerIdOrderByIdDesc(Long ownerId);
    Optional<WishlistItem> findByIdAndOwnerId(Long id, Long ownerId);
}
