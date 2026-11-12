package com.guitarvault;

import java.util.*;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:shop-races;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "spring.h2.console.enabled=false"})
class ShopConcurrencyTests {
    @Autowired UserRepository users;
    @Autowired InstrumentRepository instruments;
    @Autowired VintageShopRepository shops;
    @Autowired ShopService service;
    @Autowired ShopListingRepository listings;
    @Autowired ShopListingPhotoRepository photos;
    @Autowired ShopContactService contacts;
    @Autowired ShopConversationRepository conversations;
    @Autowired ShopMessageRepository messages;
    @Autowired NotificationRepository notifications;
    User seller, buyer, other;
    VintageShop shop;
    Instrument instrument;
    @BeforeEach void setup() {
        String suffix = UUID.randomUUID().toString(); seller = account("shop-seller-" + suffix); buyer = account("shop-buyer-" + suffix); other = account("shop-other-" + suffix);
        service.saveProfile(seller, -1, profile()); shop = service.ownShop(seller);
        instrument = instruments.saveAndFlush(new Instrument("Race guitar", "Brand", 1960, seller, "Actif"));
    }
    @AfterEach void cleanup() {
        for (var c : conversations.inbox(seller.getId(), org.springframework.data.domain.Pageable.unpaged())) { messages.deleteAll(messages.findByConversationIdOrderByIdAsc(c.getId())); conversations.delete(c); }
        for (var l : listings.findByShopUserIdOrderByIdDesc(seller.getId(), org.springframework.data.domain.Pageable.unpaged())) { photos.deleteById(l.getId()); listings.delete(l); }
        instruments.deleteById(instrument.getId()); shops.findByUserId(seller.getId()).ifPresent(shops::delete);
        for (var actor : List.of(seller, buyer, other)) notifications.deleteAll(notifications.findByRecipientIdOrderByIdDesc(actor.getId(), org.springframework.data.domain.Pageable.unpaged()));
        users.deleteAllById(List.of(seller.getId(), buyer.getId(), other.getId()));
    }
    @Test void twoOverlappingBookingsCannotBothBeConfirmed() throws Exception {
        var first = contacts.create(buyer, shop.getId(), null, true, proposal()); var second = contacts.create(other, shop.getId(), null, true, proposal());
        assertThat(race(() -> contacts.act(seller, first.getId(), first.getVersion(), "confirm", Map.of()),
                () -> contacts.act(seller, second.getId(), second.getVersion(), "confirm", Map.of()))).containsExactlyInAnyOrder(200, 409);
        assertThat(conversations.findAllById(List.of(first.getId(), second.getId())).stream().filter(c -> c.getStatus() == ShopConversation.Status.CONFIRMED)).hasSize(1);
    }
    @Test void simultaneousPublicationCannotCreateTwoLiveListingsForOneInstrument() throws Exception {
        var form = Map.of("instrumentId", instrument.getId().toString(), "price", "4000", "currency", "EUR", "stock", "AVAILABLE", "description", "For sale", "published", "true");
        assertThat(race(() -> service.saveListing(seller, null, -1, form, null), () -> service.saveListing(seller, null, -1, form, null))).containsExactlyInAnyOrder(200, 409);
        assertThat(listings.findByShopUserIdOrderByIdDesc(seller.getId(), org.springframework.data.domain.Pageable.unpaged())).hasSize(1);
    }
    @Test void twoFirstProfileSubmissionsCannotOverwriteEachOther() throws Exception {
        shops.delete(shop);
        assertThat(race(() -> service.saveProfile(seller, -1, profile()), () -> service.saveProfile(seller, -1, profile()))).containsExactlyInAnyOrder(200, 409);
    }
    private List<Integer> race(Runnable first, Runnable second) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var left = pool.submit(() -> run(first, ready, start)); var right = pool.submit(() -> run(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown(); return List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
        }
    }
    private int run(Runnable action, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
        try { action.run(); return 200; } catch (ResponseStatusException e) { return e.getStatusCode().value(); }
    }
    private Map<String,String> profile() { return Map.of("name", "Shop", "location", "Paris", "description", "About", "contact", "Contact", "openingHours", "By appointment", "published", "true"); }
    private Map<String,String> proposal() { return Map.of("title", "Viewing", "message", "Visit", "proposedTime", LocalDate.now(ZoneOffset.UTC).plusDays(7).atTime(10,0).toString(), "timeZone", "UTC"); }
    private User account(String name) { var u = new User(); u.setUsername(name); u.setEmail(name + "@example.test"); u.setPasswordHash("unused"); return users.saveAndFlush(u); }
}
