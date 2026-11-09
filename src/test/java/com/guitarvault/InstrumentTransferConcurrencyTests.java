package com.guitarvault;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;

/** Separate database: worker transactions must see committed records, not the test transaction. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:transfer-races;DB_CLOSE_DELAY=-1", "spring.h2.console.enabled=false"})
class InstrumentTransferConcurrencyTests {
    @Autowired UserRepository users;
    @Autowired InstrumentRepository instruments;
    @Autowired InstrumentTransferRepository transfers;
    @Autowired InstrumentOwnershipRepository ownerships;
    @Autowired InstrumentTransferService service;
    @Autowired NotificationRepository notifications;

    @Test void concurrentProposalsAndAcceptCancelRaceHaveExactlyOneWinner() throws Exception {
        String suffix = UUID.randomUUID().toString();
        User sender = account("sender-" + suffix), recipient = account("recipient-" + suffix), other = account("other-" + suffix);
        Instrument instrument = instruments.saveAndFlush(new Instrument("Race passport", "Brand", 2000, sender, "Actif"));
        try {
            List<Integer> proposals = race(
                    () -> service.propose(sender, instrument.getId(), recipient.getUsername(), true),
                    () -> service.propose(sender, instrument.getId(), other.getUsername(), true));
            assertThat(proposals).containsExactlyInAnyOrder(200, 409);
            InstrumentTransfer transfer = transfers.findBySenderIdOrRecipientIdOrderByIdDesc(sender.getId(), sender.getId()).getFirst();
            User winnerRecipient = transfer.getRecipient().getId().equals(recipient.getId()) ? recipient : other;
            List<Integer> resolutions = race(
                    () -> { service.resolve(winnerRecipient, transfer.getId(), InstrumentTransfer.Status.ACCEPTED); return null; },
                    () -> { service.resolve(sender, transfer.getId(), InstrumentTransfer.Status.CANCELLED); return null; });
            assertThat(resolutions).containsExactlyInAnyOrder(200, 409);
            InstrumentTransfer saved = transfers.findById(transfer.getId()).orElseThrow();
            boolean accepted = saved.getStatus() == InstrumentTransfer.Status.ACCEPTED;
            assertThat(saved.getStatus()).isIn(InstrumentTransfer.Status.ACCEPTED, InstrumentTransfer.Status.CANCELLED);
            assertThat(instruments.findById(instrument.getId()).orElseThrow().getOwner().getId())
                    .isEqualTo(accepted ? winnerRecipient.getId() : sender.getId());
            assertThat(ownerships.findByInstrumentIdOrderByStartedAtAsc(instrument.getId())).hasSize(accepted ? 2 : 1);
        } finally {
            transfers.deleteAll(transfers.findBySenderIdOrRecipientIdOrderByIdDesc(sender.getId(), sender.getId()));
            ownerships.deleteAll(ownerships.findByInstrumentIdOrderByStartedAtAsc(instrument.getId()));
            instruments.deleteById(instrument.getId());
            for (User person : List.of(sender, recipient, other))
                notifications.deleteAll(notifications.findByRecipientIdOrderByIdDesc(person.getId(), org.springframework.data.domain.Pageable.unpaged()));
            users.deleteAllById(List.of(sender.getId(), recipient.getId(), other.getId()));
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
