package com.guitarvault;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;

public interface BandConnectionRepository extends JpaRepository<BandConnection, Long> {
    Optional<BandConnection> findByFirstBandIdAndSecondBandIdAndType(Long first, Long second, BandConnection.Type type);
    interface Target {
        Long getFirstId();
        Long getSecondId();
        Long getFirstOwnerId();
        Long getSecondOwnerId();
        boolean getRequestedByFirst();
    }
    @Query("select c.firstBand.id as firstId, c.secondBand.id as secondId, c.firstBand.creator.id as firstOwnerId, c.secondBand.creator.id as secondOwnerId, c.requestedByFirst as requestedByFirst from BandConnection c where c.id = :id")
    Optional<Target> target(Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from BandConnection c where c.id = :id")
    Optional<BandConnection> findForUpdate(Long id);
    @Query("select c from BandConnection c where c.firstBand.moderationHidden = false and c.secondBand.moderationHidden = false and (c.firstBand.id = :bandId or c.secondBand.id = :bandId) and c.status = :status order by c.updatedAt desc, c.id desc")
    List<BandConnection> forBand(Long bandId, BandConnection.Status status);
    @Query("select c from BandConnection c where (c.firstBand.creator.id = :userId or c.secondBand.creator.id = :userId) and c.status = :status order by c.updatedAt desc, c.id desc")
    List<BandConnection> forManager(Long userId, BandConnection.Status status);
}
