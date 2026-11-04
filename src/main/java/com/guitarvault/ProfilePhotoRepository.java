package com.guitarvault;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfilePhotoRepository extends JpaRepository<ProfilePhoto, Long> {
    Optional<ProfilePhoto> findByOwnerId(Long ownerId);
    boolean existsByOwnerId(Long ownerId);
}
