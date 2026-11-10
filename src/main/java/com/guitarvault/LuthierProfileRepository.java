package com.guitarvault;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface LuthierProfileRepository extends JpaRepository<LuthierProfile, Long> {
    Optional<LuthierProfile> findByUserId(Long userId);
    @Query("select p.id from LuthierProfile p where p.user.id = :actor")
    Optional<Long> idForOwner(Long actor);
    List<LuthierProfile> findByPublishedTrueAndUserEnabledTrueAndUserSuspendedFalseAndUserModerationHiddenFalseOrderByNameAsc();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LuthierProfile p where p.id = :id")
    Optional<LuthierProfile> findForUpdate(Long id);
}
