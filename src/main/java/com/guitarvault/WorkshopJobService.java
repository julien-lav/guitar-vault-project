package com.guitarvault;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkshopJobService {
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private final LuthierInterventionRepository interventions;
    private final InstrumentRepository instruments;
    private final WorkshopJobRepository jobs;
    private final WorkshopJobEventRepository events;
    private final NotificationService notifications;
    public WorkshopJobService(LuthierInterventionRepository interventions, InstrumentRepository instruments,
            WorkshopJobRepository jobs, WorkshopJobEventRepository events, NotificationService notifications) {
        this.interventions = interventions; this.instruments = instruments; this.jobs = jobs; this.events = events; this.notifications = notifications;
    }
    @Transactional
    public void act(User actor, Long id, long version, String action, Map<String, String> form) {
        // Instrument first: intake and quote acceptance serialize with ownership transfers.
        var initial = interventions.findById(id).orElseThrow(WorkshopJobService::missing);
        if (!actor.isEnabled()) throw missing();
        boolean luthier = initial.getLuthier().getUser().getId().equals(actor.getId());
        if (!luthier && !initial.getRequester().getId().equals(actor.getId())) throw missing();
        var instrument = instruments.findForUpdate(initial.getInstrument().getId()).orElseThrow(WorkshopJobService::missing);
        var intervention = interventions.findForUpdate(id).orElseThrow(WorkshopJobService::missing);
        entityManager.refresh(instrument);
        entityManager.refresh(intervention);
        if (!intervention.getRequester().isEnabled() || !intervention.getLuthier().getUser().isEnabled()) throw conflict();
        var job = jobs.findByInterventionId(id).orElse(null);
        String message = form.getOrDefault("message", "").strip();
        if (message.length() > 3000) throw invalid();
        if (action.equals("receive")) {
            if (!luthier || job != null || jobs.hasActiveJob(instrument.getId()) || version != -1 || intervention.getStatus() != LuthierIntervention.Status.REQUESTED
                    || !instrument.getOwner().getId().equals(intervention.getRequester().getId())) throw conflict();
            if (message.isBlank()) throw invalid();
            job = jobs.saveAndFlush(new WorkshopJob(intervention));
        } else {
            if (job == null || job.getVersion() != version || job.isClosed()) throw conflict();
            var stage = job.getStage();
            switch (action) {
                case "quote" -> {
                    if (!luthier || !Set.of(WorkshopJob.Stage.RECEIVED, WorkshopJob.Stage.QUOTED, WorkshopJob.Stage.QUOTE_DECLINED).contains(stage)) throw conflict();
                    if (message.isBlank()) throw invalid();
                    String currency = form.getOrDefault("currency", "");
                    if (!Set.of("EUR", "USD", "GBP", "CAD", "CHF").contains(currency)) throw invalid();
                    job.quote(amount(form), currency, message);
                }
                case "approve", "decline" -> {
                    if (luthier || stage != WorkshopJob.Stage.QUOTED || !instrument.getOwner().getId().equals(actor.getId())) throw conflict();
                    job.move(action.equals("approve") ? WorkshopJob.Stage.APPROVED : WorkshopJob.Stage.QUOTE_DECLINED);
                }
                case "request-change" -> {
                    if (!luthier || !Set.of(WorkshopJob.Stage.IN_PROGRESS, WorkshopJob.Stage.CHANGE_PENDING, WorkshopJob.Stage.CHANGE_DECLINED).contains(stage)) throw conflict();
                    String scope = form.getOrDefault("scope", "").strip();
                    if (message.isBlank() || scope.isBlank() || scope.length() > 3000) throw changeInvalid();
                    java.time.LocalDate completion;
                    try {
                        String raw = form.getOrDefault("completionOn", "");
                        if (!raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw changeInvalid();
                        completion = java.time.LocalDate.parse(raw);
                        if (completion.isBefore(java.time.LocalDate.now(java.time.ZoneOffset.UTC))) throw changeInvalid();
                    } catch (java.time.DateTimeException e) { throw changeInvalid(); }
                    job.proposeChange(amount(form), scope, message, completion);
                }
                case "approve-change", "decline-change" -> {
                    if (luthier || stage != WorkshopJob.Stage.CHANGE_PENDING || !instrument.getOwner().getId().equals(actor.getId())) throw conflict();
                    if (action.equals("approve-change")) {
                        if (job.getProposedCompletionOn().isBefore(java.time.LocalDate.now(java.time.ZoneOffset.UTC))) throw conflict();
                        job.approveChange();
                    }
                    else job.move(WorkshopJob.Stage.CHANGE_DECLINED);
                }
                case "resume-original" -> {
                    if (!luthier || !Set.of(WorkshopJob.Stage.CHANGE_PENDING, WorkshopJob.Stage.CHANGE_DECLINED).contains(stage)) throw conflict();
                    if (message.isBlank()) throw changeInvalid();
                    job.move(WorkshopJob.Stage.IN_PROGRESS);
                }
                case "start" -> { if (!luthier || stage != WorkshopJob.Stage.APPROVED) throw conflict(); job.move(WorkshopJob.Stage.IN_PROGRESS); }
                case "ready" -> { if (!luthier || stage != WorkshopJob.Stage.IN_PROGRESS) throw conflict(); if (message.isBlank()) throw invalid(); job.move(WorkshopJob.Stage.READY); }
                case "collect" -> { if (!luthier || stage != WorkshopJob.Stage.READY) throw conflict(); job.move(WorkshopJob.Stage.COLLECTED); intervention.complete(); }
                case "cancel" -> {
                    if (!luthier && !Set.of(WorkshopJob.Stage.RECEIVED, WorkshopJob.Stage.QUOTED, WorkshopJob.Stage.QUOTE_DECLINED).contains(stage)) throw conflict();
                    if (message.isBlank()) throw invalid(); job.move(WorkshopJob.Stage.CANCELLED); if (intervention.getStatus() == LuthierIntervention.Status.REQUESTED) intervention.cancel();
                }
                case "update" -> { if (!luthier || stage != WorkshopJob.Stage.IN_PROGRESS) throw conflict(); if (message.isBlank()) throw invalid(); }
                default -> throw invalid();
            }
        }
        job.touch();
        jobs.saveAndFlush(job);
        events.save(new WorkshopJobEvent(job, actor, action, message));
        notifications.send(actor, luthier ? intervention.getRequester() : intervention.getLuthier().getUser(),
                Notification.Kind.WORKSHOP_JOB, intervention.getInstrument().getName(), "/interventions/" + id);
    }
    private static BigDecimal amount(Map<String, String> form) {
        String raw = form.getOrDefault("amount", "");
        if (!raw.matches("[0-9]{1,9}(\\.[0-9]{1,2})?")) throw invalid();
        return new BigDecimal(raw).setScale(2);
    }
    private static IllegalArgumentException changeInvalid() { return new IllegalArgumentException("job.change.invalid"); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("job.invalid"); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "job.conflict"); }
}
