package com.guitarvault;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DashboardOverviewTests {
    @Autowired DashboardOverviewService overview;
    @Autowired UserRepository users;
    @Autowired InstrumentRepository instruments;
    @Autowired VintageShopRepository shops;
    @Autowired ShopConversationRepository conversations;
    @Autowired LuthierProfileRepository workshops;
    @Autowired LuthierAppointmentRepository appointments;
    @MockitoBean JavaMailSender mail;

    @Test void previewsExcludeFormerOwnerPhotosAndForeignCollections() {
        var owner = account("preview-owner"); var former = account("preview-former");
        var guitar = new Instrument("Guitar", "Brand", 2000, former, "Actif");
        guitar.getPhotos().add(new InstrumentPhoto(guitar, "image/png", new byte[]{1}));
        guitar.transferTo(owner);
        var visible = new InstrumentPhoto(guitar, "image/png", new byte[]{2});
        guitar.getPhotos().add(visible); instruments.saveAndFlush(guitar);
        var foreign = new Instrument("Foreign", "Brand", 2000, former, "Actif");
        foreign.getPhotos().add(new InstrumentPhoto(foreign, "image/png", new byte[]{3}));
        instruments.saveAndFlush(foreign);
        assertThat(overview.forUser(owner, List.of(guitar, foreign)).photos()).containsOnlyKeys(guitar.getId()).containsEntry(guitar.getId(), visible.getId());
        assertThat(overview.forUser(former, List.of(guitar)).photos()).isEmpty();
    }

    @Test void upcomingVisitsAreMergedByTimeAndOnlyParticipantsSeeTasks() {
        var owner = account("visit-owner"); var professional = account("visit-pro"); var outsider = account("visit-outsider");
        var shop = new VintageShop(professional); shop.update("Shop", "Paris", "Vintage", "contact", "By appointment", true); shops.saveAndFlush(shop);
        var workshop = new LuthierProfile(professional); workshop.update("Workshop", "Paris", "Guitars", "Repairs", "contact", true); workshops.saveAndFlush(workshop);
        var early = Instant.now().plusSeconds(86400);
        var visit = new ShopConversation(shop, owner, "Shop visit", null); visit.propose(owner, early, "Europe/Paris"); visit.resolve(ShopConversation.Status.CONFIRMED); conversations.saveAndFlush(visit);
        var repair = new LuthierAppointment(owner, workshop, "Workshop visit", early.plusSeconds(3600), "UTC"); repair.resolve(LuthierAppointment.Status.CONFIRMED); appointments.saveAndFlush(repair);
        var proposal = new ShopConversation(shop, owner, "Time proposal", null); proposal.propose(professional, early.plusSeconds(7200), "UTC"); conversations.saveAndFlush(proposal);
        var past = new LuthierAppointment(owner, workshop, "Past visit", early.minusSeconds(172800), "UTC"); past.resolve(LuthierAppointment.Status.CONFIRMED); appointments.saveAndFlush(past);
        assertThat(overview.forUser(owner, List.of()).visits()).extracting(DashboardOverviewService.Visit::title).containsExactly("Shop visit", "Workshop visit");
        assertThat(overview.forUser(owner, List.of()).tasks()).extracting(DashboardOverviewService.Task::title).containsExactly("Time proposal");
        assertThat(overview.forUser(professional, List.of()).tasks()).isEmpty();
        assertThat(overview.forUser(outsider, List.of()).tasks()).isEmpty();
        assertThat(overview.forUser(outsider, List.of()).visits()).isEmpty();
    }

    private User account(String name) {
        var user = new User(); user.setUsername(name); user.setEmail(name + "@example.org");
        user.setPasswordHash("unused-test-hash"); user.setEnabled(true); return users.saveAndFlush(user);
    }
}
