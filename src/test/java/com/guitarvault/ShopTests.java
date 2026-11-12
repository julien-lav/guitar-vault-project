package com.guitarvault;

import java.util.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class ShopTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired InstrumentRepository instruments;
    @Autowired VintageShopRepository shops;
    @Autowired ShopService service;
    @Autowired ShopListingRepository listings;
    @Autowired ShopContactService contacts;
    @Autowired ShopConversationRepository conversations;
    @Autowired ShopMessageRepository messages;
    @Autowired NotificationRepository notifications;
    @Autowired InstrumentTransferService transfers;
    @MockitoBean JavaMailSender mail;
    User seller, buyer, outsider, admin;
    VintageShop shop;
    Instrument instrument;
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        seller = account("shop-seller"); buyer = account("shop-buyer"); outsider = account("shop-outsider"); admin = account("shop-admin");
        admin.setRoles(Set.of(Role.ROLE_ADMIN)); users.flush();
        service.saveProfile(seller, -1, profile()); shop = service.ownShop(seller);
        instrument = new Instrument("Vintage Strat", "Fender", 1965, seller, "Actif");
        instrument.setNotes("PRIVATE PURCHASE PRICE"); instrument.setSerialNumber("PRIVATE SERIAL"); instrument.setHistory("PRIVATE HISTORY"); instruments.saveAndFlush(instrument);
    }
    @Test void publishedDirectoryAndListingExposeOnlyExplicitSaleFieldsInBothLanguages() throws Exception {
        var listing = listing(true);
        for (String lang : List.of("en", "fr")) {
            mvc.perform(get("/shops").param("lang", lang)).andExpect(status().isOk()).andExpect(content().string(containsString("Vintage store"))).andExpect(content().string(not(containsString("??"))));
            mvc.perform(get("/shops/" + shop.getId()).param("lang", lang)).andExpect(status().isOk()).andExpect(content().string(containsString("4995.50 EUR")));
            mvc.perform(get(path(listing)).param("lang", lang)).andExpect(status().isOk()).andExpect(content().string(not(containsString("PRIVATE")))).andExpect(content().string(not(containsString("??"))));
        }
        mvc.perform(get("/shops/" + shop.getId() + "/contact")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/shops").param("q", "Paris")).andExpect(content().string(containsString("Vintage store")));
        mvc.perform(get("/shops").param("q", "unmatched")).andExpect(content().string(not(containsString("Vintage store"))));
        mvc.perform(get("/shops").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/shops/" + shop.getId()).param("q", "Gibson")).andExpect(content().string(not(containsString("Vintage Strat"))));
    }
    @Test void draftListingsAndTheirExplicitPhotosStayPrivateAndCanBePublishedOrRemoved() throws Exception {
        byte[] png = png();
        var form = new HashMap<>(listingForm()); form.put("published", "false");
        var listing = service.saveListing(seller, null, -1, form, new MockMultipartFile("photo", "listing.png", "image/png", png));
        mvc.perform(get(path(listing))).andExpect(status().isNotFound());
        mvc.perform(get(path(listing) + "/photo")).andExpect(status().isNotFound());
        mvc.perform(get(path(listing) + "/photo").with(user(seller.getEmail()))).andExpect(status().isOk()).andExpect(content().bytes(png));
        mvc.perform(get(path(listing) + "/photo").with(user(admin.getEmail()))).andExpect(status().isNotFound());
        form.put("published", "true"); service.saveListing(seller, listing.getId(), listing.getVersion(), form, null);
        mvc.perform(get(path(listing) + "/photo")).andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(content().bytes(png));
        long version = listing.getVersion(); form.put("removePhoto", "true"); service.saveListing(seller, listing.getId(), version, form, null);
        assertThat(listing.isPhoto()).isFalse(); mvc.perform(get(path(listing) + "/photo")).andExpect(status().isNotFound());
    }
    @Test void ownerCanAddANewInstrumentWithPriceThroughAProtectedForm() throws Exception {
        mvc.perform(get("/my-shop/listings/new").with(user(seller.getEmail())).param("lang", "en")).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\""))).andExpect(content().string(not(containsString("??"))));
        mvc.perform(multipart("/my-shop/listings").with(user(seller.getEmail())).with(csrf()).param("version", "-1")
                        .param("title", "New stock guitar").param("brand", "Gibson").param("year", "1970").param("price", "3500").param("currency", "EUR")
                        .param("stock", "AVAILABLE").param("description", "Original finish, wear described").param("published", "true").param("userId", buyer.getId().toString()))
                .andExpect(redirectedUrl("/my-shop"));
        var listing = listings.findAll().getFirst(); assertThat(listing.getInstrument().getOwner().getId()).isEqualTo(seller.getId());
        assertThat(listing.getPrice()).isEqualByComparingTo("3500"); assertThat(listing.getInstrument().getName()).isEqualTo("New stock guitar");
        mvc.perform(get("/my-shop").with(user(seller.getEmail())).param("lang", "en")).andExpect(status().isOk()).andExpect(content().string(not(containsString("??"))));
    }
    @Test void stockAndPublicationChangesPreserveHistoryAndSoldItemsLeaveTheCatalogue() throws Exception {
        var listing = listing(true); var form = new HashMap<>(listingForm());
        form.put("stock", "RESERVED"); service.saveListing(seller, listing.getId(), listing.getVersion(), form, null);
        mvc.perform(get(path(listing)).param("lang", "en")).andExpect(content().string(containsString("Reserved")));
        form.put("stock", "SOLD"); service.saveListing(seller, listing.getId(), listing.getVersion(), form, null);
        mvc.perform(get("/shops/" + shop.getId())).andExpect(content().string(not(containsString("Vintage Strat"))));
        mvc.perform(get(path(listing)).param("lang", "en")).andExpect(status().isOk()).andExpect(content().string(containsString("Sold")))
                .andExpect(content().string(not(containsString("listingId="))));
        assertThatThrownBy(() -> contacts.create(buyer, shop.getId(), listing.getId(), false, contactForm())).isInstanceOf(ResponseStatusException.class);
    }
    @Test void staleFormsForgeryAndInvalidUploadsCannotOverwriteInventory() throws Exception {
        var listing = listing(true); long version = listing.getVersion(); var form = new HashMap<>(listingForm()); form.put("price", "5100");
        service.saveListing(seller, listing.getId(), version, form, null);
        for (User actor : List.of(buyer, admin)) {
            mvc.perform(get("/my-shop/listings/" + listing.getId() + "/edit").with(user(actor.getEmail()))).andExpect(status().isNotFound());
            assertThatThrownBy(() -> service.saveListing(actor, listing.getId(), listing.getVersion(), form, null)).isInstanceOf(ResponseStatusException.class);
        }
        mvc.perform(multipart("/my-shop/listings/" + listing.getId()).with(user(seller.getEmail())).with(csrf()).param("version", "" + version)
                        .param("price", "5200").param("currency", "EUR").param("stock", "AVAILABLE").param("description", "Changed")).andExpect(status().isConflict());
        mvc.perform(post("/my-shop").with(user(seller.getEmail())).param("version", "0")).andExpect(status().isForbidden());
        var foreign = new HashMap<>(listingForm()); foreign.put("instrumentId", instruments.saveAndFlush(new Instrument("Other", "Brand", 2000, buyer, "Actif")).getId().toString());
        assertThatThrownBy(() -> service.saveListing(seller, null, -1, foreign, null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.saveListing(seller, listing.getId(), listing.getVersion(), form, new MockMultipartFile("photo", "bad.png", "image/png", "bad".getBytes())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("shop.photoError");
        assertThat(listing.getPrice()).isEqualByComparingTo("5100");
    }
    @Test void invalidPricesDoNotCreateOrPublishAnInstrument() {
        long count = instruments.count();
        for (String price : List.of("-1", "1.001", "NaN", "1000000000", "1e3")) {
            var form = new HashMap<>(listingForm()); form.remove("instrumentId"); form.put("title", "New"); form.put("brand", "Brand"); form.put("price", price);
            assertThatThrownBy(() -> service.saveListing(seller, null, -1, form, null)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(instruments.count()).isEqualTo(count); assertThat(listings.count()).isZero();
    }
    @Test void enquiriesNeedNoInstrumentOrTimeAndCanBecomeConfirmedPrivateAppointments() throws Exception {
        var listing = listing(true);
        var c = contacts.create(buyer, shop.getId(), listing.getId(), false, contactForm());
        assertThat(c.isScheduled()).isFalse(); assertThat(c.getAskingPrice()).isEqualByComparingTo("4995.50");
        assertThat(notifications.countByRecipientIdAndReadAtIsNull(seller.getId())).isEqualTo(1);
        contacts.act(seller, c.getId(), c.getVersion(), "proposals", proposal());
        assertThatThrownBy(() -> contacts.act(seller, c.getId(), c.getVersion(), "confirm", Map.of())).isInstanceOf(ResponseStatusException.class);
        contacts.act(buyer, c.getId(), c.getVersion(), "confirm", Map.of());
        contacts.act(seller, c.getId(), c.getVersion(), "messages", Map.of("message", "PRIVATE: bring your amplifier <script>"));
        for (String lang : List.of("en", "fr")) mvc.perform(get(thread(c)).with(user(buyer.getEmail())).param("lang", lang)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store")).andExpect(content().string(containsString("&lt;script&gt;"))).andExpect(content().string(not(containsString("??"))));
        assertThat(messages.findByConversationIdOrderByIdAsc(c.getId())).hasSize(4);
    }
    @Test void onlyParticipantsCanReadOrMutateEvenForAdministrators() throws Exception {
        var c = contacts.create(buyer, shop.getId(), null, true, proposal());
        for (User actor : List.of(outsider, admin)) {
            mvc.perform(get(thread(c)).with(user(actor.getEmail()))).andExpect(status().isNotFound());
            mvc.perform(get("/shop-conversations").with(user(actor.getEmail()))).andExpect(content().string(not(containsString("Private shop visit"))));
            mvc.perform(post(thread(c) + "/confirm").with(user(actor.getEmail())).with(csrf()).param("version", "0")).andExpect(status().isNotFound());
        }
        mvc.perform(post(thread(c) + "/confirm").with(user(seller.getEmail())).param("version", "0")).andExpect(status().isForbidden());
        mvc.perform(get(thread(c))).andExpect(redirectedUrl("/login"));
    }
    @Test void staleProposalsAndOverlappingConfirmationsAreRejectedAndCancellationFreesTheSlot() {
        var first = contacts.create(buyer, shop.getId(), null, true, proposal());
        var second = contacts.create(outsider, shop.getId(), null, true, proposal());
        long stale = first.getVersion(); contacts.act(seller, first.getId(), stale, "proposals", proposal());
        assertThatThrownBy(() -> contacts.act(buyer, first.getId(), stale, "confirm", Map.of())).isInstanceOf(ResponseStatusException.class);
        contacts.act(buyer, first.getId(), first.getVersion(), "confirm", Map.of());
        assertThatThrownBy(() -> contacts.act(seller, second.getId(), second.getVersion(), "confirm", Map.of())).isInstanceOf(ResponseStatusException.class);
        contacts.act(buyer, first.getId(), first.getVersion(), "cancel", Map.of());
        contacts.act(seller, second.getId(), second.getVersion(), "confirm", Map.of());
        assertThat(second.getStatus()).isEqualTo(ShopConversation.Status.CONFIRMED);
    }
    @Test void unpublishingOrTransferringHidesInventoryButPreservesExistingEnquiries() throws Exception {
        var listing = listing(true); var c = contacts.create(buyer, shop.getId(), listing.getId(), false, contactForm());
        var updated = new HashMap<>(listingForm()); updated.put("price", "6000"); service.saveListing(seller, listing.getId(), listing.getVersion(), updated, null);
        assertThat(c.getAskingPrice()).isEqualByComparingTo("4995.50");
        var profile = new HashMap<>(profile()); profile.put("published", "false"); service.saveProfile(seller, shop.getVersion(), profile);
        mvc.perform(get(path(listing))).andExpect(status().isNotFound());
        contacts.act(seller, c.getId(), c.getVersion(), "messages", Map.of("message", "Still discussing"));
        profile.put("published", "true"); service.saveProfile(seller, shop.getVersion(), profile);
        var transfer = transfers.propose(seller, instrument.getId(), buyer.getUsername(), true); transfers.resolve(buyer, transfer.getId(), InstrumentTransfer.Status.ACCEPTED);
        mvc.perform(get(path(listing))).andExpect(status().isNotFound());
        mvc.perform(get(thread(c)).with(user(seller.getEmail()))).andExpect(status().isOk());
        mvc.perform(get(thread(c)).with(user(outsider.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get("/shops/" + shop.getId())).andExpect(content().string(not(containsString("Vintage Strat"))));
    }
    @Test void invalidClockTimesSelfRequestsAndHiddenOrInactiveShopsAreRejected() {
        for (var entry : List.of(Map.entry("proposedTime", "2020-01-01T10:00"), Map.entry("timeZone", "Invalid/Zone"), Map.entry("proposedTime", "bad"))) {
            var form = new HashMap<>(proposal()); form.put(entry.getKey(), entry.getValue());
            assertThatThrownBy(() -> contacts.create(buyer, shop.getId(), null, true, form)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> contacts.create(seller, shop.getId(), null, false, contactForm())).isInstanceOf(ResponseStatusException.class);
        seller.setModerationHidden(true); users.flush();
        assertThatThrownBy(() -> contacts.create(buyer, shop.getId(), null, false, contactForm())).isInstanceOf(ResponseStatusException.class);
        seller.setModerationHidden(false); seller.setEnabled(false); users.flush();
        assertThatThrownBy(() -> service.publicShop(shop.getId())).isInstanceOf(ResponseStatusException.class);
    }
    private ShopListing listing(boolean published) { var form = new HashMap<>(listingForm()); form.put("published", "" + published); return service.saveListing(seller, null, -1, form, null); }
    private Map<String,String> listingForm() { return Map.of("instrumentId", instrument.getId().toString(), "description", "Public sale description", "price", "4995.50", "currency", "EUR", "stock", "AVAILABLE", "published", "true"); }
    private Map<String,String> profile() { return Map.of("name", "Vintage store", "location", "Paris", "description", "Vintage guitars", "contact", "shop@example.test", "openingHours", "By appointment", "published", "true"); }
    private Map<String,String> contactForm() { return Map.of("title", "Private shop visit", "message", "Private enquiry about a guitar"); }
    private Map<String,String> proposal() { return Map.of("title", "Private shop visit", "message", "Private enquiry", "proposedTime", LocalDate.now(ZoneOffset.UTC).plusDays(7).atTime(10,0).toString(), "timeZone", "Europe/Paris"); }
    private String path(ShopListing listing) { return "/shop-listings/" + listing.getId(); }
    private String thread(ShopConversation c) { return "/shop-conversations/" + c.getId(); }
    private User account(String name) { var u = new User(); u.setUsername(name); u.setEmail(name + "@example.test"); u.setPasswordHash("unused"); return users.saveAndFlush(u); }
    private byte[] png() throws Exception { var bytes = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes); return bytes.toByteArray(); }
}
