package com.guitarvault;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:appointment-races;DB_CLOSE_DELAY=-1", "spring.h2.console.enabled=false"})
class AppointmentConcurrencyTests {
    @Autowired UserRepository users;
    @Autowired LuthierProfileRepository workshops;
    @Autowired LuthierAppointmentRepository appointments;
    @Autowired AppointmentMessageRepository messages;
    @Autowired AppointmentService service;
    @Autowired NotificationRepository notifications;
    @Test void confirmationAndAnotherTimeProposalCannotBothUseTheSameVersion() throws Exception {
        String suffix = UUID.randomUUID().toString();
        User musician = account("musician-" + suffix), luthier = account("luthier-" + suffix);
        var workshop = new LuthierProfile(luthier); workshop.update("Race workshop", "Paris", "Setups", "About", "contact", true);
        workshops.saveAndFlush(workshop);
        Map<String,String> form = Map.of("title", "Race visit", "message", "Arrange a visit", "timeZone", "UTC",
                "proposedTime", LocalDate.now(ZoneOffset.UTC).plusDays(7).atTime(10,0).toString());
        var a = service.create(musician, workshop.getId(), form);
        try {
            var outcomes = race(
                    () -> { service.resolve(luthier, a.getId(), 0, "confirm"); return null; },
                    () -> { service.propose(musician, a.getId(), 0, form); return null; });
            assertThat(outcomes).containsExactlyInAnyOrder(200, 409);
            var saved = appointments.findById(a.getId()).orElseThrow();
            assertThat(saved.getVersion()).isEqualTo(1);
            assertThat(saved.getStatus()).isIn(LuthierAppointment.Status.PENDING, LuthierAppointment.Status.CONFIRMED);
            assertThat(messages.findByAppointmentIdOrderByIdAsc(a.getId())).hasSize(2);
        } finally {
            messages.deleteAll(messages.findByAppointmentIdOrderByIdAsc(a.getId()));
            appointments.deleteById(a.getId()); workshops.deleteById(workshop.getId());
            for (User person : List.of(musician, luthier))
                notifications.deleteAll(notifications.findByRecipientIdOrderByIdDesc(person.getId(), org.springframework.data.domain.Pageable.unpaged()));
            users.deleteAllById(List.of(musician.getId(), luthier.getId()));
        }
    }
    private List<Integer> race(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> a = () -> run(first, ready, start);
            Callable<Integer> b = () -> run(second, ready, start);
            Future<Integer> left = pool.submit(a), right = pool.submit(b);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS));
        }
    }
    private int run(Callable<?> action, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
        try { action.call(); return 200; }
        catch (ResponseStatusException e) { return e.getStatusCode().value(); }
    }
    private User account(String name) {
        User u = new User(); u.setUsername(name); u.setEmail(name + "@example.org"); u.setPasswordHash("unused");
        return users.saveAndFlush(u);
    }
}
