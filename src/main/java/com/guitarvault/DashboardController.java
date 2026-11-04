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

    public DashboardController(InstrumentService instruments) {
        this.instruments = instruments;
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
        return "layout";
    }

    private long count(List<Instrument> collection, String status) {
        return collection.stream().filter(instrument -> status.equals(instrument.getStatus())).count();
    }
}
