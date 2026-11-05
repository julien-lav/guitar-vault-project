package com.guitarvault;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {
    private final InstrumentService instruments;
    private final BandRepository bands;
    private final StudioRepository studios;
    private final WishlistRepository wishlist;
    private final StudioQuoteRepository quotes;
    private final ProfileService profiles;
    private final BandInvitationRepository invitations;

    public DashboardController(InstrumentService instruments, BandRepository bands, StudioRepository studios,
                               WishlistRepository wishlist, StudioQuoteRepository quotes, ProfileService profiles, BandInvitationRepository invitations) {
        this.instruments = instruments;
        this.bands = bands;
        this.studios = studios;
        this.wishlist = wishlist;
        this.quotes = quotes;
        this.profiles = profiles;
        this.invitations = invitations;
    }

    @GetMapping("/dashboard")
    public String dashboard(Principal principal, Model model) {
        User owner = instruments.currentUser(principal);
        List<Instrument> collection = instruments.instrumentsFor(owner);
        model.addAttribute("title", "page.dashboard");
        model.addAttribute("contentTemplate", "dashboard");
        model.addAttribute("displayName", owner.getDisplayName() != null && !owner.getDisplayName().isBlank()
                ? owner.getDisplayName() : (owner.getFullName() == null || owner.getFullName().isBlank()
                ? owner.getUsername() : owner.getFullName()));
        model.addAttribute("stats", List.of(
                Map.of("label", "stats.total", "value", collection.size()),
                Map.of("label", "stats.active", "value", count(collection, "Actif")),
                Map.of("label", "status.repair", "value", count(collection, "À réparer")),
                Map.of("label", "status.sale", "value", count(collection, "En vente"))));
        model.addAttribute("instruments", collection.stream().limit(5).toList());
        Long id = owner.getId();
        model.addAttribute("dashboardProfile", profiles.view(id));
        model.addAttribute("myBands", bands.findTop3ByMembersIdOrderByNameAsc(id));
        model.addAttribute("myStudios", studios.findTop3ByCreatorIdOrderByNameAsc(id));
        model.addAttribute("wishes", wishlist.findTop3ByOwnerIdOrderByIdDesc(id));
        model.addAttribute("projects", quotes.findTop3ByRequesterIdOrderByIdDesc(id));
        model.addAttribute("pendingRequests", quotes.findTop3ByStudioCreatorIdAndStatusOrderByIdDesc(id, StudioQuote.Status.PENDING));
        model.addAttribute("overview", Map.of("bands", bands.countByMembersId(id), "studios", studios.countByCreatorId(id),
                "wishlist", wishlist.countByOwnerId(id), "projects", quotes.countByRequesterId(id)));
        model.addAttribute("toAnswerCount", quotes.countByStudioCreatorIdAndStatus(id, StudioQuote.Status.PENDING));
        model.addAttribute("answersCount", quotes.countByRequesterIdAndStatus(id, StudioQuote.Status.ANSWERED));
        model.addAttribute("bandInvitations", invitations.findTop3ByInviteeIdAndStatusOrderByIdDesc(id, BandInvitation.Status.PENDING));
        model.addAttribute("invitationCount", invitations.countByInviteeIdAndStatus(id, BandInvitation.Status.PENDING));
        return "layout";
    }

    private long count(List<Instrument> collection, String status) {
        return collection.stream().filter(instrument -> status.equals(instrument.getStatus())).count();
    }
}
