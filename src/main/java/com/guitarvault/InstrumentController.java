package com.guitarvault;

import java.security.Principal;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class InstrumentController {
    private final InstrumentService instruments;

    public InstrumentController(InstrumentService instruments) {
        this.instruments = instruments;
    }

    @GetMapping("/instruments")
    public String instruments(Principal principal, Model model) {
        model.addAttribute("instruments", instruments.instrumentsFor(instruments.currentUser(principal)));
        model.addAttribute("title", "page.instruments");
        model.addAttribute("contentTemplate", "instruments");
        return "layout";
    }

    @GetMapping("/instruments/new")
    public String newInstrument(Model model) {
        model.addAttribute("title", "page.instrumentNew");
        model.addAttribute("contentTemplate", "instrument-form");
        model.addAttribute("currentYear", Year.now().getValue());
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", Map.of("status", "Actif"));
        }
        return "layout";
    }

    @PostMapping("/instruments")
    public String addInstrument(Principal principal, @RequestParam Map<String, String> params,
                                Model model, RedirectAttributes redirect) {
        User owner = instruments.currentUser(principal);
        Map<String, String> form = new LinkedHashMap<>();
        for (String field : Set.of("name", "brand", "year", "status")) {
            form.put(field, params.getOrDefault(field, "").strip());
        }
        Integer year = null;
        String error = null;
        if (form.get("name").isBlank() || form.get("name").length() > 100
                || form.get("brand").isBlank() || form.get("brand").length() > 100) {
            error = "instrument.nameError";
        } else if (!Set.of("Actif", "À réparer", "En vente").contains(form.get("status"))) {
            error = "instrument.statusError";
        } else if (!form.get("year").isEmpty()) {
            try {
                year = Integer.valueOf(form.get("year"));
                if (year < 1 || year > Year.now().getValue()) {
                    error = "instrument.futureYearError";
                }
            } catch (NumberFormatException exception) {
                error = "instrument.yearError";
            }
        }
        if (error != null) {
            model.addAttribute("form", form);
            model.addAttribute("error", error);
            return newInstrument(model);
        }
        instruments.add(owner, form.get("name"), form.get("brand"), year, form.get("status"));
        redirect.addFlashAttribute("success", "instrument.success");
        return "redirect:/dashboard";
    }
}
