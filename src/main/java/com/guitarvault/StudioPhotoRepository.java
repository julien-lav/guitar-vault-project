package com.guitarvault;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudioPhotoRepository extends JpaRepository<StudioPhoto, Long> {
    // Load photo identifiers for galleries without loading the image data.
    interface Summary { Long getId(); }
    List<Summary> findByStudioIdOrderByIdAsc(Long studioId);
    Optional<StudioPhoto> findByIdAndStudioId(Long id, Long studioId);
    void deleteByStudioIdAndIdIn(Long studioId, Collection<Long> ids);
}
