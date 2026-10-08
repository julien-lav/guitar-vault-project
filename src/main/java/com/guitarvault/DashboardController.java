package com.guitarvault;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("title", "Dashboard Guitar Vault");

        model.addAttribute("stats", List.of(
                Map.of("label", "Instruments", "value", "128"),
                Map.of("label", "Propriétaires", "value", "42"),
                Map.of("label", "Transferts", "value", "17"),
                Map.of("label", "Entretien", "value", "9")
        ));

        model.addAttribute("recentInstruments", List.of(
                Map.of("name", "Fender Stratocaster", "owner", "Julien", "status", "Actif"),
                Map.of("name", "Gibson Les Paul", "owner", "Thomas", "status", "À réparer"),
                Map.of("name", "PRS Custom 24", "owner", "Emma", "status", "En vente"),
                Map.of("name", "Yamaha Pacifica", "owner", "Léo", "status", "Actif")
        ));

        model.addAttribute("recentEvents", List.of(
                "18/10 — Réglage chez le luthier",
                "15/10 — Vente de la Gibson Les Paul",
                "11/10 — Nouveau transfert de propriété",
                "06/10 — Contrôle d'entretien" 
        ));

        return "dashboard";
    }
}
