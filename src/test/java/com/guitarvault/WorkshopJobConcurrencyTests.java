package com.guitarvault;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:workshop-job-races;DB_CLOSE_DELAY=-1", "spring.h2.console.enabled=false"})
class WorkshopJobConcurrencyTests {
    @Autowired UserRepository users;
    @Autowired InstrumentRepository instruments;
    @Autowired LuthierProfileRepository profiles;
    @Autowired LuthierInterventionRepository interventions;
    @Autowired LuthierService luthiers;
    @Autowired WorkshopJobService service;
    @Autowired WorkshopJobRepository jobs;
    @Autowired WorkshopJobEventRepository events;
    @Autowired NotificationRepository notifications;
    @Test void duplicateIntakeAndApprovalRacingAQuoteRevisionCannotBothSucceed() throws Exception {
        String suffix = UUID.randomUUID().toString();
        var owner = account("repair-owner-" + suffix); var luthier = account("repair-luthier-" + suffix);
        var instrument = instruments.saveAndFlush(new Instrument("Race guitar", "Brand", 2000, owner, "Actif"));
        var workshop = new LuthierProfile(luthier); workshop.update("Workshop", "Paris", "Repairs", "About", "Contact", true); profiles.saveAndFlush(workshop);
        var intervention = luthiers.request(owner, instrument.getId(), Map.of("luthierId", "" + workshop.getId(), "kind", "REPAIR", "description", "Repair"));
        try {
            assertThat(race(
                    () -> service.act(luthier, intervention.getId(), -1, "receive", Map.of("message", "Received")),
                    () -> service.act(luthier, intervention.getId(), -1, "receive", Map.of("message", "Duplicate"))))
                    .containsExactlyInAnyOrder(200, 409);
            var job = jobs.findByInterventionId(intervention.getId()).orElseThrow();
            service.act(luthier, intervention.getId(), job.getVersion(), "quote", Map.of("amount", "100", "currency", "EUR", "message", "First quote"));
            long version = jobs.findById(job.getId()).orElseThrow().getVersion();
            assertThat(race(
                    () -> service.act(owner, intervention.getId(), version, "approve", Map.of()),
                    () -> service.act(luthier, intervention.getId(), version, "quote", Map.of("amount", "200", "currency", "EUR", "message", "Revised quote"))))
                    .containsExactlyInAnyOrder(200, 409);
            var saved = jobs.findById(job.getId()).orElseThrow();
            assertThat(saved.getVersion()).isEqualTo(version + 1);
            if (saved.getStage() == WorkshopJob.Stage.APPROVED) assertThat(saved.getAmount()).isEqualByComparingTo("100");
            else { assertThat(saved.getStage()).isEqualTo(WorkshopJob.Stage.QUOTED); assertThat(saved.getAmount()).isEqualByComparingTo("200"); }
            assertThat(events.findByJobIdOrderByIdAsc(job.getId())).hasSize(3);
        } finally {
            jobs.findByInterventionId(intervention.getId()).ifPresent(job -> {
                events.deleteAll(events.findByJobIdOrderByIdAsc(job.getId())); jobs.delete(job);
            });
            interventions.deleteById(intervention.getId()); instruments.deleteById(instrument.getId()); profiles.deleteById(workshop.getId());
            for (User person : List.of(owner, luthier)) notifications.deleteAll(notifications.findByRecipientIdOrderByIdDesc(person.getId(), org.springframework.data.domain.Pageable.unpaged()));
            users.deleteAllById(List.of(owner.getId(), luthier.getId()));
        }
    }
    @Test void changeApprovalRacingARevisedProposalCannotAuthoriseUnseenTerms() throws Exception {
        String suffix = UUID.randomUUID().toString();
        var owner = account("repair-owner-" + suffix); var luthier = account("repair-luthier-" + suffix);
        var instrument = instruments.saveAndFlush(new Instrument("Race guitar", "Brand", 2000, owner, "Actif"));
        var workshop = new LuthierProfile(luthier); workshop.update("Workshop", "Paris", "Repairs", "About", "Contact", true); profiles.saveAndFlush(workshop);
        var intervention = luthiers.request(owner, instrument.getId(), Map.of("luthierId", "" + workshop.getId(), "kind", "REPAIR", "description", "Repair"));
        try {
            service.act(luthier, intervention.getId(), -1, "receive", Map.of("message", "Received"));
            var job = jobs.findByInterventionId(intervention.getId()).orElseThrow();
            service.act(luthier, intervention.getId(), job.getVersion(), "quote", Map.of("amount", "120", "currency", "EUR", "message", "Bridge"));
            service.act(owner, intervention.getId(), jobs.findById(job.getId()).orElseThrow().getVersion(), "approve", Map.of());
            service.act(luthier, intervention.getId(), jobs.findById(job.getId()).orElseThrow().getVersion(), "start", Map.of());
            String completion = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(7).toString();
            service.act(luthier, intervention.getId(), jobs.findById(job.getId()).orElseThrow().getVersion(), "request-change",
                    Map.of("amount", "180", "scope", "Bridge and brace", "message", "Discovery", "completionOn", completion));
            long version = jobs.findById(job.getId()).orElseThrow().getVersion();
            assertThat(race(
                    () -> service.act(owner, intervention.getId(), version, "approve-change", Map.of()),
                    () -> service.act(luthier, intervention.getId(), version, "request-change", Map.of("amount", "200", "scope", "Revised", "message", "New discovery", "completionOn", completion))))
                    .containsExactlyInAnyOrder(200, 409);
            var saved = jobs.findById(job.getId()).orElseThrow();
            assertThat(saved.getVersion()).isEqualTo(version + 1);
            if (saved.getStage() == WorkshopJob.Stage.IN_PROGRESS) assertThat(saved.getAmount()).isEqualByComparingTo("180");
            else { assertThat(saved.getStage()).isEqualTo(WorkshopJob.Stage.CHANGE_PENDING); assertThat(saved.getAmount()).isEqualByComparingTo("120"); assertThat(saved.getProposedAmount()).isEqualByComparingTo("200"); }
            assertThat(events.findByJobIdOrderByIdAsc(job.getId())).hasSize(6);
        } finally {
            jobs.findByInterventionId(intervention.getId()).ifPresent(job -> {
                events.deleteAll(events.findByJobIdOrderByIdAsc(job.getId())); jobs.delete(job);
            });
            interventions.deleteById(intervention.getId()); instruments.deleteById(instrument.getId()); profiles.deleteById(workshop.getId());
            for (User person : List.of(owner, luthier)) notifications.deleteAll(notifications.findByRecipientIdOrderByIdDesc(person.getId(), org.springframework.data.domain.Pageable.unpaged()));
            users.deleteAllById(List.of(owner.getId(), luthier.getId()));
        }
    }
    private List<Integer> race(Runnable first, Runnable second) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var left = pool.submit(() -> run(first, ready, start)); var right = pool.submit(() -> run(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
        }
    }
    private int run(Runnable action, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
        try { action.run(); return 200; } catch (ResponseStatusException e) { return e.getStatusCode().value(); }
    }
    private User account(String name) { var user = new User(); user.setUsername(name); user.setEmail(name + "@example.test"); user.setPasswordHash("unused"); return users.saveAndFlush(user); }
}
