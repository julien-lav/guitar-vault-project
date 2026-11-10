package com.guitarvault;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:availability-races;DB_CLOSE_DELAY=-1", "spring.h2.console.enabled=false"})
class AvailabilityConcurrencyTests {
    @Autowired UserRepository users;
    @Autowired LuthierProfileRepository workshops;
    @Autowired LuthierAppointmentRepository appointments;
    @Autowired AppointmentMessageRepository messages;
    @Autowired AppointmentService service;
    @Autowired AvailabilityService availability;
    @Autowired WorkshopAvailabilityRepository settings;
    @Autowired WorkshopBlockedDateRepository blocked;
    @Autowired NotificationRepository notifications;
    @Test void concurrentBookingsAndBlockingCannotBothReserveTheSameWorkshopTime() throws Exception {
        String suffix=UUID.randomUUID().toString();
        User first=account("first-"+suffix), second=account("second-"+suffix), owner=account("owner-"+suffix);
        var workshop=new LuthierProfile(owner); workshop.update("Concurrent workshop","Paris","Setups","About","contact",true); workshops.saveAndFlush(workshop);
        availability.save(owner,-1,true,"UTC",30,"09:00","12:00",Arrays.stream(DayOfWeek.values()).map(Enum::name).toList());
        LocalDate date=LocalDate.now(ZoneOffset.UTC).plusDays(7);
        var slot=availability.calendar(workshop,date).slots().getFirst();
        Map<String,String> form=Map.of("title","Shared slot","message","Visit request","slot",slot.start().toString(),"availabilityVersion","0");
        var a=service.create(first,workshop.getId(),form); var b=service.create(second,workshop.getId(),form);
        LuthierAppointment c=null;
        try {
            assertThat(race(
                    () -> { service.resolve(owner,a.getId(),0,"confirm"); return null; },
                    () -> { service.resolve(owner,b.getId(),0,"confirm"); return null; }))
                    .containsExactlyInAnyOrder(200,409);
            var savedA=appointments.findById(a.getId()).orElseThrow(); var savedB=appointments.findById(b.getId()).orElseThrow();
            assertThat(List.of(savedA.getStatus(),savedB.getStatus())).containsExactlyInAnyOrder(LuthierAppointment.Status.CONFIRMED,LuthierAppointment.Status.PENDING);
            var next=availability.calendar(workshop,date.plusDays(1)).slots().getFirst();
            c=service.create(first,workshop.getId(),Map.of("title","Block race","message","Visit","slot",next.start().toString(),"availabilityVersion","0"));
            Long third=c.getId();
            assertThat(race(
                    () -> { service.resolve(owner,third,0,"confirm"); return null; },
                    () -> { availability.block(owner,0,date.plusDays(1)); return null; }))
                    .containsExactlyInAnyOrder(200,409);
            boolean confirmed=appointments.findById(third).orElseThrow().getStatus()==LuthierAppointment.Status.CONFIRMED;
            assertThat(blocked.existsByWorkshopIdAndDate(workshop.getId(),date.plusDays(1))).isEqualTo(!confirmed);
        } finally {
            for (var appointment: c==null ? List.of(a,b) : List.of(a,b,c)) {
                messages.deleteAll(messages.findByAppointmentIdOrderByIdAsc(appointment.getId())); appointments.deleteById(appointment.getId());
            }
            blocked.deleteAll(blocked.findByWorkshopIdOrderByDateAsc(workshop.getId()));
            settings.deleteById(workshop.getId()); workshops.deleteById(workshop.getId());
            for (User person:List.of(first,second,owner))
                notifications.deleteAll(notifications.findByRecipientIdOrderByIdDesc(person.getId(),org.springframework.data.domain.Pageable.unpaged()));
            users.deleteAllById(List.of(first.getId(),second.getId(),owner.getId()));
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
