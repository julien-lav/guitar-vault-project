package com.guitarvault;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises actual committed transactions contending on the same instrument lock. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:document-races;DB_CLOSE_DELAY=-1", "spring.h2.console.enabled=false"})
class DocumentLibraryConcurrencyTests {
    @Autowired UserRepository users;
    @Autowired InstrumentRepository instruments;
    @Autowired InstrumentTransferRepository transfers;
    @Autowired InstrumentOwnershipRepository ownerships;
    @Autowired InstrumentTransferService service;
    @Autowired NotificationRepository notifications;
    @Autowired DocumentLibraryService library;
    @Autowired DocumentService uploads;
    @Autowired InstrumentDocumentRepository documents;
    @Autowired DocumentContentRepository contents;

    @Test void deletingAnUploadAndSelectingItForATransferCannotBothSucceed() throws Exception {
        String suffix = UUID.randomUUID().toString();
        User sender = account("sender-" + suffix), recipient = account("recipient-" + suffix);
        Instrument instrument = instruments.saveAndFlush(new Instrument("Document race", "Brand", 2000, sender, "Actif"));
        byte[] pdf = "%PDF-1.4\ntrailer\n<<>>\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        uploads.upload(sender, instrument.getId(), "Race certificate", "CERTIFICATE", new MockMultipartFile("file", "proof.pdf", "application/pdf", pdf));
        Long documentId = documents.transferableDocuments(instrument.getId(), sender.getId()).getFirst().getId();
        try {
            List<Integer> results = race(
                    () -> service.propose(sender, instrument.getId(), recipient.getUsername(), true, List.of(documentId)),
                    () -> { library.delete(sender, documentId, -1); return null; });
            assertThat(results).contains(200);
            assertThat(results.stream().filter(code -> code == 200).count()).isEqualTo(1);
            assertThat(results).allMatch(code -> code == 200 || code == 404 || code == 409);
            var proposals = transfers.findBySenderIdOrRecipientIdOrderByIdDesc(sender.getId(), sender.getId());
            boolean referenced = !proposals.isEmpty();
            assertThat(documents.existsById(documentId)).isEqualTo(referenced);
            assertThat(contents.existsById(documentId)).isEqualTo(referenced);
            if (referenced) {
                assertThat(proposals).hasSize(1);
                assertThat(uploads.bytes(sender, documentId)).isEqualTo(pdf);
            }
        } finally {
            transfers.deleteAll(transfers.findBySenderIdOrRecipientIdOrderByIdDesc(sender.getId(), sender.getId()));
            ownerships.deleteAll(ownerships.findByInstrumentIdOrderByStartedAtAsc(instrument.getId()));
            if (contents.existsById(documentId)) contents.deleteById(documentId);
            if (documents.existsById(documentId)) documents.deleteById(documentId);
            instruments.deleteById(instrument.getId());
            for (User person : List.of(sender, recipient))
                notifications.deleteAll(notifications.findByRecipientIdOrderByIdDesc(person.getId(), org.springframework.data.domain.Pageable.unpaged()));
            users.deleteAllById(List.of(sender.getId(), recipient.getId()));
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
