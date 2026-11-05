package com.guitarvault;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BandInvitationRepository extends JpaRepository<BandInvitation, Long> {
    List<BandInvitation> findByBandIdAndStatusOrderByIdDesc(Long bandId, BandInvitation.Status status);
    List<BandInvitation> findByInviteeIdAndStatusOrderByIdDesc(Long userId, BandInvitation.Status status);
    List<BandInvitation> findTop3ByInviteeIdAndStatusOrderByIdDesc(Long userId, BandInvitation.Status status);
    List<BandInvitation> findByBandCreatorIdAndStatusOrderByIdDesc(Long creatorId, BandInvitation.Status status);
    long countByInviteeIdAndStatus(Long userId, BandInvitation.Status status);
    Optional<BandInvitation> findByBandIdAndInviteeId(Long bandId, Long userId);

    interface Target { Long getBandId(); Long getInviteeId(); }
    @Query("select i.band.id as bandId, i.invitee.id as inviteeId from BandInvitation i where i.id = :id")
    Optional<Target> target(@Param("id") Long id);
}
