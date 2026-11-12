package com.guitarvault;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkshopJobRepository extends JpaRepository<WorkshopJob, Long> {
    @org.springframework.data.jpa.repository.Query("select count(j) > 0 from WorkshopJob j where j.intervention.instrument.id = :instrumentId and j.stage not in (com.guitarvault.WorkshopJob.Stage.COLLECTED, com.guitarvault.WorkshopJob.Stage.CANCELLED)")
    boolean hasActiveJob(Long instrumentId);
    java.util.List<WorkshopJob> findByInterventionIn(java.util.List<LuthierIntervention> interventions);
    Optional<WorkshopJob> findByInterventionId(Long interventionId);
    @org.springframework.data.jpa.repository.Query("select j from WorkshopJob j where j.intervention.requester.id = :actor and j.stage in (com.guitarvault.WorkshopJob.Stage.QUOTED, com.guitarvault.WorkshopJob.Stage.CHANGE_PENDING) order by j.id desc")
    java.util.List<WorkshopJob> awaitingOwner(Long actor, org.springframework.data.domain.Pageable page);

}
